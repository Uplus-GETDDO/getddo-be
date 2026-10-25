#!/usr/bin/env python3
"""main CI에서 단일 플랫폼 이미지를 한 번 빌드하고 불변 ECR 태그와 작은 metadata를 게시한다."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile

spec = importlib.util.spec_from_file_location('image_metadata', Path(__file__).with_name('image-metadata.py'))
metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata)


def command(arguments, timeout=1800, **kwargs):
    """인증 출력은 캡처하고 실패 시 원문을 로그로 유출하지 않는다."""
    return subprocess.run(arguments, check=True, capture_output=True, text=True, timeout=timeout, **kwargs).stdout


def publish(environment):
    """main ref와 허용 계정·리전·run을 요구한다. 실패한 push는 산출물로 인정하지 않으며 기존 불변 태그를 덮어쓰지 않는다."""
    sha, run, attempt = (environment[key] for key in ('SOURCE_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT'))
    tag = '-'.join((sha, run, attempt))
    if (environment.get('GITHUB_EVENT_NAME') != 'push' or environment.get('GITHUB_REF') != 'refs/heads/main' or
            environment.get('ECR_REPOSITORY_URI') != metadata.REPOSITORY or environment.get('ECR_AWS_REGION') != metadata.REGION):
        raise ValueError('Unapproved publisher context')
    # 소스/태그 형식을 AWS 인증 전에 확인한다.
    document = dict(schema_version=1, source_sha=sha, build_run_id=run, build_run_attempt=attempt,
                    repository_uri=metadata.REPOSITORY, image_tag=tag, image_digest='sha256:' + '0' * 64,
                    image_id='sha256:' + '0' * 64, platform='linux/amd64')
    metadata.validate(document)
    def aws(*arguments):
        return json.loads(command(['aws', *arguments, '--region', metadata.REGION, '--output', 'json', '--no-cli-pager'], timeout=30))
    if aws('sts', 'get-caller-identity')['Account'] != '942466928325':
        raise ValueError('Publisher AWS account mismatch')
    ref = metadata.REPOSITORY + ':' + tag
    command(['docker', 'build', '--platform', 'linux/amd64', '--tag', ref,
             '--label', 'org.opencontainers.image.revision=' + sha, '.'])
    image = json.loads(command(['docker', 'image', 'inspect', ref], timeout=30))[0]
    if (image['Os'] + '/' + image['Architecture'] != 'linux/amd64' or
            image.get('Config', {}).get('Labels', {}).get('org.opencontainers.image.revision') != sha):
        raise ValueError('Built image revision/platform mismatch')
    document['image_id'] = image['Id']
    with tempfile.TemporaryDirectory(prefix='ecr-publisher-', dir=environment['RUNNER_TEMP']) as directory:
        secure_env = dict(environment, DOCKER_CONFIG=directory)
        password = command(['aws', 'ecr', 'get-login-password', '--region', metadata.REGION], timeout=30)
        command(['docker', 'login', '--username', 'AWS', '--password-stdin', metadata.REPOSITORY.split('/')[0]],
                timeout=30, input=password, env=secure_env)
        del password
        # 동일 run 재호출도 push 실패를 숨기지 않는다. 재실행 차수는 새 불변 태그를 사용한다.
        pushed = subprocess.run(['docker', 'push', ref], capture_output=True, text=True, timeout=600, env=secure_env)
        if pushed.returncode:
            raise ValueError('ECR push failed; immutable tags must not be overwritten')
        result = aws('ecr', 'batch-get-image', '--registry-id', '942466928325', '--repository-name', 'getddo-be', '--image-ids', 'imageTag=' + tag)
        rows = result.get('images', [])
        if result.get('failures') or len(rows) != 1:
            raise ValueError('ECR push was not verified')
        document['image_digest'] = rows[0]['imageId']['imageDigest']
        metadata.validate(document)
        metadata.verify_ecr(document, aws)
    path = Path(environment['RUNNER_TEMP']) / 'container-image'
    path.mkdir(mode=0o700, exist_ok=True)
    (path / 'image-metadata.json').write_text(json.dumps(document, indent=2) + '\n')
    print('ECR_PUBLISHED: ' + sha + ' ' + metadata.reference(document) + ' ' + document['image_id'])


if __name__ == '__main__':
    try:
        publish(os.environ)
    except (ValueError, KeyError, OSError, subprocess.SubprocessError):
        raise SystemExit('Image publishing failed; no deployment metadata accepted') from None
