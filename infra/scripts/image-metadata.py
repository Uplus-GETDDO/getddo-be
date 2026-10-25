#!/usr/bin/env python3
"""고정 ECR 메타데이터를 코드로 실행하지 않고 검증한다. 실패 시 배포 상태는 변경하지 않는다."""
import argparse
import json
from pathlib import Path
import re
import subprocess

REPOSITORY = '942466928325.dkr.ecr.ap-northeast-2.amazonaws.com/getddo-be'
REGION = 'ap-northeast-2'
DIGEST = r'sha256:[a-f0-9]{64}'


def validate(document, sha=None, run_id=None, attempt=None, baseline=False):
    """정확한 필드·저장소·플랫폼·출처를 요구하며 manifest digest와 config ID를 구별한다."""
    fields = {'schema_version', 'source_sha', 'build_run_id', 'build_run_attempt',
              'repository_uri', 'image_tag', 'image_digest', 'image_id', 'platform'}
    if not isinstance(document, dict) or set(document) != fields or type(document['schema_version']) is not int or document['schema_version'] != 1:
        raise ValueError('Image metadata schema mismatch')
    for field, pattern in {'source_sha': r'[a-f0-9]{40}', 'build_run_id': r'[1-9][0-9]*',
                           'build_run_attempt': r'[1-9][0-9]*', 'image_digest': DIGEST, 'image_id': DIGEST}.items():
        if not isinstance(document[field], str) or not re.fullmatch(pattern, document[field]):
            raise ValueError('Invalid image metadata field: ' + field)
    if document['repository_uri'] != REPOSITORY or document['platform'] != 'linux/amd64':
        raise ValueError('Unapproved repository or platform')
    tag = '-'.join(document[key] for key in ('source_sha', 'build_run_id', 'build_run_attempt'))
    baseline_tag = r'baseline-' + document['source_sha'] + r'-[A-Za-z0-9][A-Za-z0-9_.-]{0,60}'
    if document['image_tag'] != tag and not (baseline and isinstance(document['image_tag'], str) and re.fullmatch(baseline_tag, document['image_tag'])):
        raise ValueError('Image tag provenance mismatch')
    for expected, field in ((sha, 'source_sha'), (run_id, 'build_run_id'), (attempt, 'build_run_attempt')):
        if expected is not None and document[field] != expected:
            raise ValueError('Image metadata provenance mismatch: ' + field)
    return document


def read(path, **expected):
    """링크나 과대 파일을 거부하고 JSON 객체를 반환한다."""
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 8192:
        raise ValueError('Invalid metadata file')
    return validate(json.loads(path.read_text()), **expected)


def reference(document):
    """실행에는 변경 가능한 태그 대신 허용 저장소의 manifest digest를 사용한다."""
    return document['repository_uri'] + '@' + document['image_digest']


def verify_ecr(document, aws):
    """정확한 태그의 ECR manifest와 config digest를 대조하며 잘못된 이미지는 배포하지 않는다."""
    result = aws('ecr', 'batch-get-image', '--registry-id', '942466928325', '--repository-name', 'getddo-be',
                 '--image-ids', 'imageTag=' + document['image_tag'])
    rows = result.get('images', [])
    if result.get('failures') or len(rows) != 1:
        raise ValueError('ECR image missing')
    row = rows[0]
    manifest = json.loads(row['imageManifest'])
    if (row['imageId']['imageDigest'] != document['image_digest'] or
            row['imageId'].get('imageTag') != document['image_tag'] or
            manifest.get('config', {}).get('digest') != document['image_id'] or 'manifests' in manifest):
        raise ValueError('ECR manifest/config identity mismatch')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('path')
    parser.add_argument('--sha', required=True)
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--attempt', required=True)
    args = parser.parse_args()
    document = read(args.path, sha=args.sha, run_id=args.run_id, attempt=args.attempt)
    def aws(*arguments):
        result = subprocess.run(['aws', *arguments, '--region', REGION, '--output', 'json', '--no-cli-pager'],
                                check=True, capture_output=True, text=True, timeout=30)
        return json.loads(result.stdout)
    verify_ecr(document, aws)
    print(reference(document))
