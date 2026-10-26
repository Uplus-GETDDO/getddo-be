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
import sys

spec = importlib.util.spec_from_file_location('prepare_image', Path(__file__).with_name('prepare-image.py'))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)
metadata = prepare.metadata


def snapshot(document):
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


def safe_error(error):
    """설정값·명령 출력 없이 실패 종류와 공개 진단만 반환한다."""
    if isinstance(error, subprocess.CalledProcessError):
        return type(error).__name__ + " exit=" + str(error.returncode)
    if isinstance(error, subprocess.TimeoutExpired):
        return type(error).__name__ + " timeout=" + str(error.timeout)
    if isinstance(error, OSError):
        return type(error).__name__ + " errno=" + str(error.errno)
    if isinstance(error, (ValueError, KeyError)):
        return type(error).__name__ + ": " + str(error)
    return type(error).__name__


def convert(root, metadata_path, name):
    """호출자는 S3 전역 잠금을 보유한다. 함수가 서버 orchestration/deploy flock을 직접 획득하며 원복 불명확 시 마커를 보존한다."""
    if os.geteuid() != 0 or not re.fullmatch(r'baseline-[A-Za-z0-9][A-Za-z0-9_.-]{0,100}', name):
        raise ValueError('Root and unique baseline release name required')
    root = Path(root).resolve(strict=True)
    document = metadata.read(metadata_path, baseline=True)
    if not document['image_tag'].startswith('baseline-'):
        raise ValueError('Baseline migration tag required')
    locks = []
    destination = root / 'releases' / name
    current, pending, marker = root / 'current', root / 'current.next', root / 'deploy-in-progress'
    created = False
    transaction = False
    previous = None
    before = None

    def clear_marker():
        """확인된 current를 먼저 영속화한다. 삭제 실패 시 기존 디렉터리가 다음 배포를 막는다."""
        prepare.command(['sync', '-f', str(root)])
        (marker / 'context').unlink(missing_ok=True)
        marker.rmdir()

    try:
        for filename in ('orchestration.lock', 'deploy.lock'):
            lock = (root / filename).open('a')
            locks.append(lock)
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if marker.exists() or marker.is_symlink() or list(Path('/etc/getddo').glob('.deploy-env.*/context')):
            raise ValueError('Unresolved deployment; preserve state')
        if not current.is_symlink() or pending.exists() or pending.is_symlink():
            raise ValueError('Expected current symlink without pending transition')
        previous = current.resolve(strict=True)
        if previous.parent != root / 'releases' or (previous / 'image-id.txt').read_text().strip() != document['image_id']:
            raise ValueError('Existing release/image ID mismatch')
        if destination.exists() or destination.is_symlink():
            raise ValueError('Baseline destination already exists')
        # ECR pull은 별도 승인된 이전 절차에서 완료해야 한다. 여기서는 로컬 동일성만 검사한다.
        prepare.verify(document)
        before = snapshot(document)
        destination.mkdir(mode=0o700)
        created = True
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
        if snapshot(document) != before or current.resolve(strict=True) != previous:
            raise ValueError('Baseline changed before atomic transition')
        # 원자 전환 전에 기존 엔진이 인식하는 마커를 영속화한다. 강제 종료도 후속 배포를 차단한다.
        marker.mkdir(mode=0o700)
        transaction = True
        (marker / 'context').write_text(json.dumps(dict(operation='baseline', previous=str(previous), destination=str(destination))) + '\n')
        prepare.command(['sync', '-f', str(marker)])
        prepare.command(['sync', '-f', str(root)])
        pending.symlink_to(destination)
        os.replace(pending, current)
        prepare.command(['sync', '-f', str(root)])
        if current.resolve(strict=True) != destination or snapshot(document) != before:
            raise ValueError('Application changed during transition')
        clear_marker()
        transaction = False
        print('BASELINE_CONVERTED: ' + document['source_sha'] + ' ' + metadata.reference(document) + ' ' + document['image_id'])
        print(json.dumps(before, sort_keys=True))
    except BaseException as original:
        if transaction:
            try:
                # 이 실행이 만든 pending만 제거한다. 다른 링크나 경로는 덮어쓰지 않는다.
                if pending.exists() or pending.is_symlink():
                    if not pending.is_symlink() or pending.resolve(strict=True) not in (previous, destination):
                        raise ValueError('Unrecognized pending baseline link')
                    pending.unlink()
                observed = current.resolve(strict=True)
                if observed not in (previous, destination):
                    raise ValueError('Unrecognized current baseline link')
                if observed != previous:
                    pending.symlink_to(previous)
                    os.replace(pending, current)
                prepare.command(['sync', '-f', str(root)])
                if current.resolve(strict=True) != previous or snapshot(document) != before:
                    raise ValueError('Baseline restoration not confirmed')
                clear_marker()
                transaction = False
            except BaseException as recovery:
                # 최초 예외를 유지하고 원복 실패 진단은 별도로 출력한다. 진행 기록은 자동으로 지우지 않는다.
                print('BASELINE_INCOMPLETE: original=' + safe_error(original) + '; recovery=' + safe_error(recovery)
                      + '; preserve deploy-in-progress and both releases; inspect before retry', file=sys.stderr)
        elif created:
            try:
                # 전환 전 실패에서만 자신이 생성한 불완전 디렉터리를 정리한다.
                if current.resolve(strict=True) != previous or pending.exists() or pending.is_symlink():
                    raise ValueError('Pre-transition state uncertain')
                shutil.rmtree(destination)
            except BaseException as cleanup:
                print('BASELINE_INCOMPLETE: original=' + safe_error(original) + '; cleanup=' + safe_error(cleanup)
                      + '; preserve release and inspect before choosing another release name', file=sys.stderr)
        raise
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
    except (ValueError, KeyError, OSError, subprocess.SubprocessError) as error:
        print("BASELINE_FAILED: " + safe_error(error), file=sys.stderr)
        raise SystemExit('Baseline conversion failed; inspect current and preserved releases before retry') from None
