#!/usr/bin/env python3
"""ALB 제외 전에 ECR 후보와 이전 복구 이미지만 준비한다. env/current/컨테이너는 변경하지 않는다."""
import argparse
import importlib.util
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile
import time

spec = importlib.util.spec_from_file_location('image_metadata', Path(__file__).with_name('image-metadata.py'))
metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata)


def command(arguments, timeout=30, deadline=None, **kwargs):
    """개별 준비 작업에 상한을 적용하며 Docker 인증 출력은 호출자에게 노출하지 않는다."""
    if deadline is not None:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise subprocess.TimeoutExpired(arguments, timeout)
        timeout = min(timeout, remaining)
    return subprocess.run(arguments, check=True, capture_output=True, text=True, timeout=timeout, **kwargs).stdout


def verify(document, deadline=None):
    """digest 참조의 로컬 ID·revision·단일 플랫폼을 확인한다. 이미지 ID를 digest로 간주하지 않는다."""
    ref = metadata.reference(document)
    rows = json.loads(command(['docker', 'image', 'inspect', ref], deadline=deadline))
    if len(rows) != 1:
        raise ValueError('Expected one local image')
    image = rows[0]
    if (image['Id'] != document['image_id'] or ref not in image.get('RepoDigests', []) or
            image.get('Config', {}).get('Labels', {}).get('org.opencontainers.image.revision') != document['source_sha'] or
            image.get('Os') + '/' + image.get('Architecture') != document['platform']):
        raise ValueError('Local ECR image identity mismatch')


def prepare(release, sha, local_only=False):
    """후보 및 current 복구 이미지를 검증한다. pull 실패에도 기존 release와 실행 앱은 보존한다."""
    deadline = time.monotonic() + 540
    def run(arguments, **kwargs):
        """호스트 전체 준비 예산을 개별 AWS/Docker 작업에도 적용한다."""
        return command(arguments, deadline=deadline, **kwargs)
    release = Path(release).resolve()
    root = release.parent.parent
    candidate = metadata.read(release / 'image-metadata.json', sha=sha, baseline=True)
    documents = [candidate]
    current = root / 'current'
    if current.is_symlink():
        previous = current.resolve(strict=True)
        if previous.parent != root / 'releases':
            raise ValueError('Current release outside release root')
        old = metadata.read(previous / 'image-metadata.json', baseline=True)
        if ((previous / 'image-id.txt').read_text().strip() != old['image_id'] or
                (previous / 'image-ref.txt').read_text().strip() != metadata.reference(old) or
                (previous / 'source-sha.txt').read_text().strip() != old['source_sha']):
            raise ValueError('Previous release identity mismatch')
        container = run(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=getddo',
                             '--filter', 'label=com.docker.compose.service=app']).strip()
        if not re.fullmatch(r'[a-f0-9]{12,64}', container):
            raise ValueError('Expected one running previous app')
        state = run(['docker', 'inspect', '--format',
                         '{{.State.Running}} {{.Image}} {{ index .Config.Labels "org.opencontainers.image.revision" }}', container]).strip()
        if state != 'true ' + old['image_id'] + ' ' + old['source_sha']:
            raise ValueError('Current release differs from running previous app')
        documents.append(old)
    elif current.exists():
        raise ValueError('Current must be a release symlink')
    elif run(['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=getddo',
                  '--filter', 'label=com.docker.compose.service=app']).strip():
        raise ValueError('Existing app has no rollback release')
    if not local_only:
        if os.geteuid() != 0:
            raise ValueError('Root-only temporary Docker authentication required')
        with tempfile.TemporaryDirectory(prefix='getddo-ecr-', dir='/run') as directory:
            environment = dict(os.environ, DOCKER_CONFIG=directory)
            password = run(['aws', 'ecr', 'get-login-password', '--region', metadata.REGION])
            run(['docker', 'login', '--username', 'AWS', '--password-stdin', metadata.REPOSITORY.split('/')[0]],
                    input=password, env=environment)
            del password
            for index, document in enumerate(documents):
                if index == 0:
                    run(['docker', 'pull', metadata.reference(document)], timeout=min(300, max(1, deadline - time.monotonic() - 90)), env=environment)
                    verify(document, deadline=deadline)
                    continue
                # 이전 이미지는 이미 로컬 검증이 되면 네트워크 없이 복구할 수 있다.
                try:
                    verify(document, deadline=deadline)
                except (ValueError, KeyError, subprocess.SubprocessError):
                    run(['docker', 'pull', metadata.reference(document)], timeout=min(300, max(1, deadline - time.monotonic() - 90)), env=environment)
                verify(document, deadline=deadline)
    else:
        for document in documents:
            verify(document, deadline=deadline)
    for filename, value in (('image-id.txt', candidate['image_id']), ('image-ref.txt', metadata.reference(candidate)),
                            ('source-sha.txt', candidate['source_sha'])):
        path = release / filename
        if path.is_symlink():
            raise ValueError('Release identity symlink rejected')
        path.write_text(value + '\n')
    print('IMAGE_PREPARED: ' + sha + ' ' + metadata.reference(candidate) + ' ' + candidate['image_id'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source_sha')
    parser.add_argument('--local-only', action='store_true')
    args = parser.parse_args()
    try:
        prepare(Path(__file__).resolve().parent, args.source_sha, args.local_only)
    except (ValueError, KeyError, OSError, subprocess.SubprocessError):
        raise SystemExit('ECR image preparation failed; existing application preserved') from None
