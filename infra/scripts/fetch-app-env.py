#!/usr/bin/env python3
"""EC2 역할로 필수 앱 설정을 조회·검증하고 비공개 임시 env 파일을 만든다."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from urllib.parse import parse_qs, urlsplit


# 설정 계약을 명시한다. 새 변수를 사용하려면 이 목록과 등록 문서를 함께 갱신한다.
# 경로 전체나 과거 버전을 읽지 않고 현재 배포에 필요한 14개만 요청한다.
PREFIX = "/getddo/dev/app/"
KEYS = (
    "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD",
    "SPRING_JPA_HIBERNATE_DDL_AUTO", "SPRING_FLYWAY_ENABLED", "CORS_ALLOWED_ORIGINS",
    "AWS_REGION", "GETDDO_IMAGES_BUCKET", "GETDDO_IMAGES_BASE_URL",
    "SPRING_DATA_REDIS_HOST", "SPRING_DATA_REDIS_PORT", "SPRING_DATA_REDIS_PASSWORD",
    "SPRING_DATA_REDIS_SSL_ENABLED", "RATE_LIMIT_STORE",
)


class ConfigurationError(ValueError):
    """값을 포함하지 않는 운영용 오류 메시지."""


def parameter_prefix(environment):
    if environment not in ("dev", "staging"):
        raise ConfigurationError("지원하지 않는 배포 환경입니다.")
    return PREFIX if environment == "dev" else "/getddo/staging/app/"


def fetch(region, environment="dev"):
    """필수 설정을 최대 10개씩 복호화 조회하고 응답을 검증용 문서로 합친다."""
    prefix = parameter_prefix(environment)
    # CLI 출력은 메모리로만 받는다. 원격 명령·Actions·SSM 로그에 비밀값을 쓰지 않는다.
    # 별도 profile이나 Access Key를 전달하지 않아 서버의 기본 인증 체인을 사용한다.
    # GetParameters는 한 번에 최대 10개다. 14개를 10개·4개로 조회해 모두 성공한 뒤 검증한다.
    combined = {"Parameters": [], "InvalidParameters": []}
    for offset in range(0, len(KEYS), 10):
        result = subprocess.run(
            ["aws", "ssm", "get-parameters", "--names",
             *[prefix + key for key in KEYS[offset:offset + 10]],
             "--with-decryption", "--region", region, "--output", "json", "--no-cli-pager"],
            capture_output=True, text=True, timeout=60,
        )
        if result.returncode:
            # AWS 오류에 값이 섞일 가능성이 있어 stderr/stdout 원문을 재출력하지 않는다.
            raise ConfigurationError("Parameter Store 조회 실패. 서버 역할·리전·복호화 권한을 확인하세요.")
        try:
            document = json.loads(result.stdout)
        except (ValueError, TypeError):
            raise ConfigurationError("Parameter Store 응답 형식이 올바르지 않습니다.") from None
        if (not isinstance(document, dict) or not isinstance(document.get("Parameters"), list)
                or not isinstance(document.get("InvalidParameters", []), list)):
            raise ConfigurationError("Parameter Store 응답 목록이 올바르지 않습니다.")
        combined["Parameters"].extend(document["Parameters"])
        combined["InvalidParameters"].extend(document.get("InvalidParameters", []))
    return combined


def validate(document, region, environment="dev"):
    """필수 이름·타입과 배포 설정 형식을 검사해 env에 기록할 값을 반환한다."""
    prefix = parameter_prefix(environment)
    # 누락·중복·잘못된 타입은 기존 앱을 교체하기 전에 실패시킨다.
    if not isinstance(document, dict) or document.get("InvalidParameters"):
        raise ConfigurationError("필수 파라미터를 모두 조회하지 못했습니다.")
    parameters = document.get("Parameters")
    if not isinstance(parameters, list):
        raise ConfigurationError("Parameter Store 응답 목록이 없습니다.")
    values = {}
    for parameter in parameters:
        if not isinstance(parameter, dict):
            raise ConfigurationError("파라미터 항목 형식이 올바르지 않습니다.")
        name = parameter.get("Name")
        if name not in {prefix + key for key in KEYS} or name in values:
            raise ConfigurationError("요청하지 않은 파라미터 또는 중복 항목이 있습니다.")
        # DB와 Redis 비밀번호 모두 암호화된 파라미터만 받는다.
        expected = "SecureString" if name in (
            prefix + "SPRING_DATASOURCE_PASSWORD", prefix + "SPRING_DATA_REDIS_PASSWORD"
        ) else "String"
        if parameter.get("Type") != expected:
            raise ConfigurationError("파라미터 타입을 확인하세요: " + name)
        value = parameter.get("Value")
        # raw env는 한 줄에 한 변수다. 줄바꿈·NUL로 새 변수를 삽입하지 못하게 한다.
        if not isinstance(value, str) or not value.strip() or any(c in value for c in "\r\n\0"):
            raise ConfigurationError("빈 값 또는 여러 줄 값은 사용할 수 없습니다: " + name)
        values[name] = value
    if set(values) != {prefix + key for key in KEYS}:
        raise ConfigurationError(f"필수 파라미터 {len(KEYS)}개가 모두 필요합니다.")
    values = {key: values[prefix + key] for key in KEYS}

    if values["AWS_REGION"] != region:
        raise ConfigurationError("앱 리전과 설정 조회 리전이 다릅니다.")
    jdbc = values["SPRING_DATASOURCE_URL"]
    url = urlsplit(jdbc[5:])
    if (not jdbc.startswith("jdbc:mysql://") or not url.hostname or not url.path.strip("/")
            or parse_qs(url.query).get("sslMode") != ["VERIFY_IDENTITY"]):
        raise ConfigurationError("DB URL의 전체 경로와 sslMode=VERIFY_IDENTITY를 확인하세요.")
    if values["SPRING_JPA_HIBERNATE_DDL_AUTO"] != "validate":
        raise ConfigurationError("현재 배포의 JPA 설정은 validate여야 합니다.")
    if values["SPRING_FLYWAY_ENABLED"] not in ("true", "false"):
        raise ConfigurationError("Flyway 설정은 true 또는 false여야 합니다.")
    for origin in values["CORS_ALLOWED_ORIGINS"].split(","):
        parsed = urlsplit(origin.strip())
        if parsed.scheme != "https" or not parsed.hostname or parsed.path or parsed.query or parsed.fragment or parsed.username:
            raise ConfigurationError("CORS에는 경로 없는 HTTPS origin을 지정하세요.")
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,61}[a-z0-9]", values["GETDDO_IMAGES_BUCKET"]):
        raise ConfigurationError("이미지 버킷 이름 형식을 확인하세요.")
    image_url = urlsplit(values["GETDDO_IMAGES_BASE_URL"])
    if (image_url.scheme != "https" or not image_url.hostname or image_url.path != "/images/"
            or image_url.query or image_url.fragment or image_url.username):
        raise ConfigurationError("이미지 기본 주소는 HTTPS이고 /images/로 끝나야 합니다.")
    # 현재 Terraform의 ElastiCache DNS·6379·필수 TLS 계약을 앱 교체 전에 검사한다.
    if not re.fullmatch(r"[a-z0-9]+(?:[.-][a-z0-9]+)*\.cache\.amazonaws\.com", values["SPRING_DATA_REDIS_HOST"]):
        raise ConfigurationError("Redis 호스트는 프로토콜·포트 없는 ElastiCache DNS여야 합니다.")
    if values["SPRING_DATA_REDIS_PORT"] != "6379":
        raise ConfigurationError("현재 Redis 연결 포트는 6379여야 합니다.")
    if values["SPRING_DATA_REDIS_SSL_ENABLED"] != "true":
        raise ConfigurationError("현재 Redis 연결은 TLS 활성화(true)가 필수입니다.")
    if not re.fullmatch(r"[A-Za-z0-9]{32,64}", values["SPRING_DATA_REDIS_PASSWORD"]):
        raise ConfigurationError("Redis 비밀번호는 Terraform 입력과 같은 32~64자 영문·숫자여야 합니다.")
    if values["RATE_LIMIT_STORE"] not in ("memory", "redis"):
        raise ConfigurationError("요청 빈도 제한 저장소는 memory 또는 redis여야 합니다.")
    return values


def write_env(output, values):
    """완성된 600 파일만 새 경로에 게시하고 실패 시 작업용 파일만 정리한다."""
    # 서버에서는 root가 실행하므로 root 소유 600이며 파일 내용은 source로 실행하지 않는다.
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=Path(output).parent,
                                     prefix=".app-env.") as stream:
        for key in KEYS:
            stream.write(key + "=" + values[key] + "\n")
        stream.flush()
        os.fsync(stream.fileno())
        # 같은 파일시스템에서 기존 파일·링크를 덮어쓰지 않고 완성된 내용만 게시한다.
        os.link(stream.name, output)


def main():
    """조회·검증 후 새 설정 파일을 생성하고 비밀값 없는 결과와 종료 코드를 반환한다."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--region", required=True, choices=["ap-northeast-2"])
    parser.add_argument("--output", required=True, type=Path, help="아직 존재하지 않는 임시 env 파일")
    parser.add_argument("--environment", choices=("dev", "staging"), default="dev")
    args = parser.parse_args()
    try:
        values = validate(fetch(args.region, args.environment), args.region, args.environment)
        write_env(args.output, values)
    except ConfigurationError as error:
        # 오류 메시지는 코드가 정의한 항목명·설명만 포함하며 실제 값을 넣지 않는다.
        print(str(error), file=sys.stderr)
        return 1
    except (ValueError, TypeError, OSError, subprocess.SubprocessError):
        print("설정 조회·임시 파일 생성 실패. 기존 설정은 유지합니다.", file=sys.stderr)
        return 1
    print(f"PARAMETERS_OK: 필수 설정 {len(KEYS)}개를 검증했습니다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
