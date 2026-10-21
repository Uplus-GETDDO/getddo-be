#!/usr/bin/env python3
"""CI 이미지를 배포용 S3에 보관하고 SSM의 최종 배포 결과를 확인한다."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tarfile
import time


def settings(environment):
    required = ("AWS_REGION", "AWS_ROLE_ARN", "DEPLOY_BUCKET", "SOURCE_SHA",
                "GITHUB_REPOSITORY", "GITHUB_RUN_ID", "GITHUB_RUN_ATTEMPT", "RUNNER_TEMP")
    values = {key: environment.get(key, "") for key in required}
    values["DEPLOY_MODE"] = environment.get("DEPLOY_MODE") or "single"
    if values["DEPLOY_MODE"] not in ("single", "rolling", "initial-c"):
        raise ValueError("DEPLOY_MODE must be single, rolling or initial-c")
    extra = ("EC2_INSTANCE_ID",) if values["DEPLOY_MODE"] == "single" else (
        "EC2_INSTANCE_ID_A", "EC2_INSTANCE_ID_C", "TARGET_GROUP_ARN")
    values.update({key: environment.get(key, "") for key in extra})
    for key in ("ROLLING_DEPLOY_ENABLED", "APP_SOURCE_DIR", "APPROVED_BASELINE_SHA", "APPROVED_BASELINE_IMAGE_ID"):
        values[key] = environment.get(key, "")
    missing = [key for key, value in values.items() if not value and key in required + extra]
    if missing:
        raise ValueError("배포 설정이 필요합니다: " + ", ".join(missing))
    role = re.fullmatch(r"arn:aws:iam::([0-9]{12}):role/[A-Za-z0-9+=,.@_/-]+", values["AWS_ROLE_ARN"])
    bucket = values["DEPLOY_BUCKET"]
    if not (role and re.fullmatch(r"[a-z]{2}-[a-z]+-[0-9]+", values["AWS_REGION"])
            and all(re.fullmatch(r"i-[a-f0-9]{17}", values[k]) for k in extra if k.startswith("EC2_"))
            and re.fullmatch(r"[a-f0-9]{40}", values["SOURCE_SHA"])
            and re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", values["GITHUB_REPOSITORY"])
            and values["GITHUB_RUN_ID"].isdigit() and values["GITHUB_RUN_ATTEMPT"].isdigit()
            and re.fullmatch(r"[a-z0-9][a-z0-9-]{1,61}[a-z0-9]", bucket)):
        raise ValueError("배포 설정 형식이 올바르지 않습니다.")
    # 기존 State 버킷 이름을 포함하는 패턴을 차단한다. 전용 배포 버킷만 사용한다.
    if "tfstate" in bucket or "terraform" in bucket:
        raise ValueError("Terraform State 버킷을 배포에 사용할 수 없습니다.")
    values["ACCOUNT_ID"] = role.group(1)
    if values["DEPLOY_MODE"] != "single":
        if values["EC2_INSTANCE_ID_A"] == values["EC2_INSTANCE_ID_C"]:
            raise ValueError("A and C must be distinct exact instance IDs")
        expected = r"arn:aws:elasticloadbalancing:" + re.escape(values["AWS_REGION"]) + ":" + values["ACCOUNT_ID"] + r":targetgroup/[A-Za-z0-9-]+/[a-f0-9]+"
        if not re.fullmatch(expected, values["TARGET_GROUP_ARN"]):
            raise ValueError("Target group account/region/ARN mismatch")
        if values["DEPLOY_MODE"] == "rolling" and values["ROLLING_DEPLOY_ENABLED"] != "true":
            raise ValueError("Infrastructure and two-JVM gate must explicitly enable rolling")
        if values["DEPLOY_MODE"] == "initial-c" and (
                values["SOURCE_SHA"] != values["APPROVED_BASELINE_SHA"] or
                not re.fullmatch(r"sha256:[a-f0-9]{64}", values["APPROVED_BASELINE_IMAGE_ID"]) or
                not values["APP_SOURCE_DIR"]):
            raise ValueError("Initial C requires approved actual A baseline SHA/image/source")
    return values


def command(arguments, timeout=70):
    return subprocess.run(arguments, check=True, capture_output=True, text=True, timeout=timeout).stdout


def aws(*arguments, timeout=70):
    return json.loads(command(["aws", *arguments, "--region", os.environ["AWS_REGION"],
                               "--output", "json", "--no-cli-pager"], timeout))


def verify_main(values):
    current = command(["gh", "api", "repos/" + values["GITHUB_REPOSITORY"] + "/git/ref/heads/main",
                       "--jq", ".object.sha"]).strip()
    if current != values["SOURCE_SHA"]:
        raise RuntimeError("main이 갱신되어 이전 커밋의 자동 배포를 중단합니다.")


def bundle(source, artifact, destination, tool_source=None):
    # .env·State·소스 전체 대신 배포에 필요한 공개 파일만 포함한다.
    files = {"compose.deploy.yaml": source / "compose.deploy.yaml",
             "deploy-container.sh": source / "infra/scripts/deploy-container.sh",
             "fetch-app-env.py": source / "infra/scripts/fetch-app-env.py"}
    if tool_source is not None:
        files["deploy-container.sh"] = tool_source / "infra/scripts/deploy-container.sh"
        files["inject-host-env.py"] = tool_source / "infra/scripts/inject-host-env.py"
    files.update({name: artifact / name for name in ("image.tar.gz", "image.tar.gz.sha256", "image-id.txt")})
    with tarfile.open(destination, "w") as archive:
        for name, path in files.items():
            if not path.is_file() or path.is_symlink():
                raise ValueError("배포 파일을 확인하세요: " + name)
            archive.add(path, arcname=name, recursive=False)
    digest = hashlib.sha256()
    with destination.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def parameters(values, key, checksum):
    release = "/opt/getddo/releases/" + values["GITHUB_RUN_ID"] + "-" + values["GITHUB_RUN_ATTEMPT"]
    quote = shlex.quote
    # SSM은 root로 실행한다. 명령에는 공개 리전만 전달하고 설정값은 서버 역할로 조회한다.
    commands = ["set -eu", "umask 077", "install -d -m 700 /opt/getddo/releases",
                "mkdir -m 700 -- " + quote(release), "cd -- " + quote(release)]
    download = ["timeout", "600", "aws", "s3api", "get-object", "--bucket", values["DEPLOY_BUCKET"],
                "--key", key, "--expected-bucket-owner", values["ACCOUNT_ID"],
                "--region", values["AWS_REGION"], "release.tar", "--no-cli-pager"]
    commands.extend([" ".join(map(quote, download)) + " >/dev/null",
                     "printf '%s\\n' " + quote(checksum + "  release.tar") + " | sha256sum --check -",
                     "tar --extract --file release.tar --no-same-owner --no-same-permissions",
                     "exec env GETDDO_DEPLOY_SERVER=" + quote(values.get("SERVER", "")) + " GETDDO_DEPLOY_METRICS_ENABLED=" + quote("false" if values["DEPLOY_MODE"] == "initial-c" else "true") + " bash deploy-container.sh " + quote(values["SOURCE_SHA"]) + " " + quote(values["AWS_REGION"])])
    return {"commands": ["\n".join(commands)], "executionTimeout": ["1800"]}


def wait_for_command(command_id, instance_id, deadline_seconds=2040):
    deadline = time.monotonic() + deadline_seconds
    while time.monotonic() < deadline:
        try:
            result = aws("ssm", "get-command-invocation", "--command-id", command_id,
                         "--instance-id", instance_id)
        except subprocess.CalledProcessError as error:
            if "InvocationDoesNotExist" not in (error.stderr or ""):
                raise
        else:
            status = result["Status"]
            if status == "Success" and result.get("ResponseCode") == 0:
                print("서버 교체와 상태 확인이 정상 종료됐습니다.")
                return
            if status not in ("Pending", "InProgress", "Delayed", "Cancelling"):
                # 앱 로그에 비밀값이 섞일 수 있어 원격 출력은 Actions로 복사하지 않는다.
                raise RuntimeError(f"SSM 배포 실패: {status}. Command ID: {command_id}")
        time.sleep(10)
    raise RuntimeError(f"SSM 대기 시간 초과: {command_id}. 원격 명령 종료 여부를 확인한 뒤 재시도하세요.")


def main(check_only=False):
    values = settings(os.environ)
    if values["DEPLOY_MODE"] != "initial-c":
        verify_main(values)
    if check_only:
        print("배포 설정 확인 완료")
        return
    source = Path(__file__).resolve().parents[2]
    temporary = Path(values["RUNNER_TEMP"])
    archive = temporary / "release.tar"
    app_source = Path(values["APP_SOURCE_DIR"]) if values["DEPLOY_MODE"] == "initial-c" else source
    if values["DEPLOY_MODE"] == "initial-c":
        if command(["git", "-C", str(app_source), "rev-parse", "HEAD"]).strip() != values["SOURCE_SHA"]:
            raise ValueError("Baseline source checkout SHA mismatch")
        command(["git", "-C", str(app_source), "diff", "--exit-code", "HEAD", "--",
                 "compose.deploy.yaml", "infra/scripts/fetch-app-env.py"])
        if (temporary / "container-image/image-id.txt").read_text().strip() != values["APPROVED_BASELINE_IMAGE_ID"]:
            raise ValueError("Approved baseline image mismatch")
    checksum = bundle(app_source, temporary / "container-image", archive,
                      source if values["DEPLOY_MODE"] != "single" else None)
    key = ("releases/" + values["SOURCE_SHA"] + "/" + values["GITHUB_RUN_ID"]
           + "-" + values["GITHUB_RUN_ATTEMPT"] + "/release.tar")
    # 고유 실행 경로만 쓰며 이미 존재하는 파일은 덮어쓰지 않는다.
    aws("s3api", "put-object", "--bucket", values["DEPLOY_BUCKET"], "--key", key,
        "--body", str(archive), "--server-side-encryption", "AES256",
        "--expected-bucket-owner", values["ACCOUNT_ID"], "--if-none-match", "*", timeout=600)
    if values["DEPLOY_MODE"] != "single":
        import importlib.util
        spec = importlib.util.spec_from_file_location("rolling", source / "infra/scripts/rolling-deploy.py")
        rolling = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(rolling)
        rolling.run(values, key, checksum, aws, parameters, lambda: verify_main(values))
        return
    # 단일 모드와 C 수동 초기화도 같은 운영 잠금을 사용한다. 미확인 SSM은 자동 해제하지 않는다.
    lock_key = "locks/container-deploy.json"
    aws("s3api", "put-object", "--bucket", values["DEPLOY_BUCKET"], "--key", lock_key,
        "--expected-bucket-owner", values["ACCOUNT_ID"], "--server-side-encryption", "AES256",
        "--if-none-match", "*", "--metadata",
        "run=" + values["GITHUB_RUN_ID"] + ",attempt=" + values["GITHUB_RUN_ATTEMPT"])
    try:
        # 산출물 업로드 뒤 운영 잠금을 확보한 시점에도 최신 main인지 확인한다.
        verify_main(values)
    except Exception:
        # 아직 원격 배포 명령을 보내지 않았으므로 안전하게 이 실행의 잠금만 해제한다.
        aws("s3api", "delete-object", "--bucket", values["DEPLOY_BUCKET"], "--key", lock_key,
            "--expected-bucket-owner", values["ACCOUNT_ID"])
        raise
    result = aws("ssm", "send-command", "--instance-ids", values["EC2_INSTANCE_ID"],
                 "--document-name", "AWS-RunShellScript", "--timeout-seconds", "120",
                 "--parameters", json.dumps(parameters(values, key, checksum)),
                 "--comment", "getddo " + values["SOURCE_SHA"])
    command_id = result["Command"]["CommandId"]
    print("SSM Command ID: " + command_id, flush=True)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as output:
            output.write(f"\n- 배포 커밋: `{values['SOURCE_SHA']}`\n- SSM Command ID: `{command_id}`\n")
    wait_for_command(command_id, values["EC2_INSTANCE_ID"])
    aws("s3api", "delete-object", "--bucket", values["DEPLOY_BUCKET"], "--key", lock_key,
        "--expected-bucket-owner", values["ACCOUNT_ID"])


if __name__ == "__main__":
    try:
        parser = argparse.ArgumentParser(description=__doc__)
        parser.add_argument("--check", action="store_true", help="설정과 main만 확인하고 배포하지 않음")
        main(parser.parse_args().check)
    except (KeyError, ValueError, RuntimeError, OSError, subprocess.SubprocessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
