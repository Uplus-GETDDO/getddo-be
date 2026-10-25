#!/usr/bin/env python3
"""실행 앱을 재시작하지 않고 기존 release를 보존한 새 ECR 기준 기록으로 current만 원자 전환한다."""
import argparse
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

spec = importlib.util.spec_from_file_location('prepare_image', Path(__file__).with_name('prepare-image.py'))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)
metadata = prepare.metadata


def snapshot(root, document):
    """컨테이너 ID·재시작 횟수·실행 ID·revision·env 해시만 비교하고 비밀 env 본문은 반환하지 않는다."""
    container = prepare.command(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=getddo',
                                 '--filter', 'label=com.docker.compose.service=app']).strip()
    if not re.fullmatch(r'[a-f0-9]{12,64}', container):
        raise ValueError('Expected exactly one running app')
    rows = json.loads(prepare.command(['docker', 'inspect', container]))
    if len(rows) != 1:
        raise ValueError('Unexpected container inspection')
    row = rows[0]
    if (not row['State']['Running'] or row['Image'] != document['image_id'] or
            row.get('Config', {}).get('Labels', {}).get('org.opencontainers.image.revision') != document['source_sha']):
        raise ValueError('Running baseline identity mismatch')
    env = Path('/etc/getddo/app.env')
    if env.is_symlink() or not env.is_file() or (env.stat().st_mode & 0o777) != 0o600 or env.stat().st_uid != 0:
        raise ValueError('Expected root-only app env')
    prepare.command(['curl', '--disable', '--fail', '--silent', '--output', '/dev/null', '--max-time', '5',
                     'http://127.0.0.1:8080/v3/api-docs'])
    return {'container_id': row['Id'], 'restart_count': row['RestartCount'], 'image_id': row['Image'],
            'revision': document['source_sha'], 'env_sha256': hashlib.sha256(env.read_bytes()).hexdigest()}


def convert(root, metadata_path, name):
    """root 전용·잠금 보유·기존 image ID 동일성 확인 후 공개 파일만 복사한다. 실패 시 이전 current로 돌린다."""
    if os.geteuid() != 0 or not re.fullmatch(r'baseline-[A-Za-z0-9][A-Za-z0-9_.-]{0,100}', name):
        raise ValueError('Root and unique baseline release name required')
    root = Path(root).resolve(strict=True)
    document = metadata.read(metadata_path, baseline=True)
    if not document['image_tag'].startswith('baseline-'):
        raise ValueError('Baseline migration tag required')
    locks = []
    try:
        for filename in ('orchestration.lock', 'deploy.lock'):
            lock = (root / filename).open('a')
            locks.append(lock)
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if (root / 'deploy-in-progress').exists() or (root / 'deploy-in-progress').is_symlink() or list(Path('/etc/getddo').glob('.deploy-env.*/context')):
            raise ValueError('Unresolved deployment; preserve state')
        current = root / 'current'
        if not current.is_symlink() or (root / 'current.next').exists() or (root / 'current.next').is_symlink():
            raise ValueError('Expected current symlink without pending transition')
        previous = current.resolve(strict=True)
        if previous.parent != root / 'releases' or (previous / 'image-id.txt').read_text().strip() != document['image_id']:
            raise ValueError('Existing release/image ID mismatch')
        destination = root / 'releases' / name
        if destination.exists() or destination.is_symlink():
            raise ValueError('Baseline destination already exists')
        # pull과 RepoDigests/ID/revision/platform 검증은 별도 승인된 이전 절차에서 완료해야 한다.
        prepare.verify(document)
        before = snapshot(root, document)
        destination.mkdir(mode=0o700)
        for filename in ('compose.deploy.yaml', 'fetch-app-env.py'):
            source = previous / filename
            if source.is_symlink() or not source.is_file():
                raise ValueError('Invalid baseline public file')
            shutil.copyfile(source, destination / filename)
        (destination / 'image-metadata.json').write_text(json.dumps(document, indent=2) + '\n')
        for filename, value in (('image-id.txt', document['image_id']), ('image-ref.txt', metadata.reference(document)),
                                ('source-sha.txt', document['source_sha'])):
            (destination / filename).write_text(value + '\n')
        (destination / 'baseline-evidence.json').write_text(json.dumps(before, indent=2) + '\n')
        prepare.command(['sync', '-f', str(destination)])
        if snapshot(root, document) != before or current.resolve(strict=True) != previous:
            raise ValueError('Baseline changed before atomic transition')
        pending = root / 'current.next'
        pending.symlink_to(destination)
        os.replace(pending, current)
        try:
            prepare.command(['sync', '-f', str(root)])
            if snapshot(root, document) != before:
                raise ValueError('Application changed during transition')
        except BaseException:
            pending.symlink_to(previous)
            os.replace(pending, current)
            prepare.command(['sync', '-f', str(root)])
            raise
        print('BASELINE_CONVERTED: ' + document['source_sha'] + ' ' + metadata.reference(document) + ' ' + document['image_id'])
        print(json.dumps(before, sort_keys=True))
    finally:
        for lock in reversed(locks):
            lock.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', default='/opt/getddo')
    parser.add_argument('--metadata', required=True)
    parser.add_argument('--release-name', required=True)
    args = parser.parse_args()
    try:
        convert(args.root, args.metadata, args.release_name)
    except (ValueError, KeyError, OSError, subprocess.SubprocessError):
        raise SystemExit('Baseline conversion failed; inspect current and preserved releases before retry') from None
