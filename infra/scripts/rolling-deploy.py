#!/usr/bin/env python3
"""ALB의 정상 상대 서버를 보존하며 기존 서버 단위 배포 엔진을 순서대로 실행한다."""
import json
import shlex
import time


class UnknownOutcome(RuntimeError):
    """명령 종료를 증명하지 못해 운영 잠금을 보존해야 하는 상태."""


def invocation(aws, instance, payload):
    # send-command 응답 자체가 유실돼도 명령이 실행됐을 수 있다. 자동 재시도하지 않는다.
    try:
        result = aws("ssm", "send-command", "--instance-ids", instance,
                     "--document-name", "AWS-RunShellScript", "--timeout-seconds", "120",
                     "--parameters", json.dumps(payload))
        command_id = result["Command"]["CommandId"]
        print("SSM Command ID: " + command_id + " instance: " + instance, flush=True)
        deadline = time.monotonic() + 2040
        while time.monotonic() < deadline:
            try:
                result = aws("ssm", "get-command-invocation", "--command-id", command_id,
                             "--instance-id", instance)
            except Exception as error:
                if "InvocationDoesNotExist" not in (getattr(error, "stderr", "") or ""):
                    raise
            else:
                status = result["Status"]
                if status in ("Success", "Failed") and result.get("ResponseCode", -1) >= 0:
                    return result
                if status not in ("Pending", "InProgress", "Delayed"):
                    raise UnknownOutcome("SSM outcome unresolved: " + command_id)
            time.sleep(10)
    except Exception as error:
        raise UnknownOutcome("SSM outcome unknown; retain lock, inspect command before recovery") from error
    raise UnknownOutcome("SSM wait expired: " + command_id)


def checked(aws, instance, script):
    result = invocation(aws, instance, {"commands": [script], "executionTimeout": ["120"]})
    if result["Status"] != "Success" or result["ResponseCode"] != 0:
        raise RuntimeError("Host preflight failed: " + instance)


def baseline_script(values):
    """초기화 직전 실제 A 이미지·revision·HTTP를 변경 없이 대조한다."""
    quote = shlex.quote
    image = values["APPROVED_BASELINE_IMAGE_ID"]
    revision_format = '{{ index .Config.Labels "org.opencontainers.image.revision" }}'
    return "\n".join([
        "set -eu",
        'test "$(cat /opt/getddo/current/image-id.txt)" = ' + quote(image),
        'test "$(docker inspect --format \'{{.State.Running}} {{.Image}}\' $(docker ps -q --filter label=com.docker.compose.project=getddo --filter label=com.docker.compose.service=app))" = ' + quote("true " + image),
        'test "$(docker image inspect --format ' + quote(revision_format) + ' ' + quote(image) + ')" = ' + quote(values["SOURCE_SHA"]),
        "curl --fail --silent --output /dev/null --max-time 5 http://127.0.0.1:8080/v3/api-docs",
    ])


def run(values, key, checksum, aws, parameters, verify_current):
    a, c = values["EC2_INSTANCE_ID_A"], values["EC2_INSTANCE_ID_C"]
    group = values["TARGET_GROUP_ARN"]
    drain_timeout = 300

    def target_health(instance):
        result = aws("elbv2", "describe-target-health", "--target-group-arn", group,
                     "--targets", "Id=" + instance + ",Port=8080")
        rows = result["TargetHealthDescriptions"]
        if len(rows) != 1 or rows[0]["Target"]["Id"] != instance or rows[0]["Target"]["Port"] != 8080:
            raise RuntimeError("Unexpected target health response")
        return rows[0]["TargetHealth"]

    def state(instance):
        return target_health(instance)["State"]

    def healthy(instance):
        if state(instance) != "healthy":
            raise RuntimeError("Other/required target is not healthy: " + instance)

    def wait(instance, expected, other):
        deadline = time.monotonic() + (drain_timeout if expected == "unused" else 300)
        while time.monotonic() < deadline:
            healthy(other)
            if state(instance) == expected:
                return
            time.sleep(10)
        raise RuntimeError("Target transition timed out: " + instance + " -> " + expected)

    def register(instance, other):
        healthy(other)
        aws("elbv2", "register-targets", "--target-group-arn", group,
            "--targets", "Id=" + instance + ",Port=8080")
        try:
            wait(instance, "healthy", other)
            # 2분 동안 연속 정상 확인. 고정 sleep만으로 정상화 판정하지 않는다.
            for _ in range(12):
                time.sleep(10)
                healthy(other)
                healthy(instance)
        except Exception:
            # 정상 상대 서버가 있을 때만 실패 대상을 다시 제외한다. 새 원격 명령은 보내지 않는다.
            healthy(other)
            aws("elbv2", "deregister-targets", "--target-group-arn", group,
                "--targets", "Id=" + instance + ",Port=8080")
            raise

    # 강제 종료/응답 유실에도 남는 버킷 단일 객체 잠금. 해제는 종료를 증명한 경우만 수행한다.
    lock_key = "locks/container-deploy.json"
    aws("s3api", "put-object", "--bucket", values["DEPLOY_BUCKET"], "--key", lock_key,
        "--expected-bucket-owner", values["ACCOUNT_ID"], "--server-side-encryption", "AES256",
        "--if-none-match", "*", "--metadata",
        "run=" + values["GITHUB_RUN_ID"] + ",attempt=" + values["GITHUB_RUN_ATTEMPT"])
    safe_to_unlock = True
    try:
        identity = aws("sts", "get-caller-identity")
        if identity["Account"] != values["ACCOUNT_ID"]:
            raise RuntimeError("AWS account mismatch")
        tg = aws("elbv2", "describe-target-groups", "--target-group-arns", group)["TargetGroups"]
        if len(tg) != 1 or tg[0]["TargetType"] != "instance" or tg[0]["Port"] != 8080:
            raise RuntimeError("Expected instance target group on port 8080")
        if values["DEPLOY_MODE"] != "initial-c":
            attributes = aws("elbv2", "describe-target-group-attributes", "--target-group-arn", group)["Attributes"]
            delay = int(next(item["Value"] for item in attributes
                             if item["Key"] == "deregistration_delay.timeout_seconds"))
            if not 0 <= delay <= 3600:
                raise ValueError("Unexpected deregistration delay")
            drain_timeout = max(300, delay + 60)
        hosts = aws("ec2", "describe-instances", "--instance-ids", a, c)["Reservations"]
        instances = [i for reservation in hosts for i in reservation["Instances"]]
        if ({i["InstanceId"] for i in instances} != {a, c} or len(instances) != 2 or
                any(i["State"]["Name"] != "running" or i["VpcId"] != tg[0]["VpcId"] for i in instances)):
            raise RuntimeError("Exact running instances must belong to target group VPC")
        online = aws("ssm", "describe-instance-information", "--filters",
                     "Key=InstanceIds,Values=" + a + "," + c)["InstanceInformationList"]
        if {i["InstanceId"] for i in online if i["PingStatus"] == "Online"} != {a, c}:
            raise RuntimeError("Both hosts must be SSM Online")
        healthy(a)
        initial = values["DEPLOY_MODE"] == "initial-c"
        if initial:
            if target_health(c).get("Reason") != "Target.NotRegistered":
                raise RuntimeError("Initial C must be unregistered")
        else:
            healthy(c)
        preflight = """set -eu
exec 8>/opt/getddo/orchestration.lock
flock -n 8
test ! -e /opt/getddo/deploy-in-progress
test ! -L /opt/getddo/deploy-in-progress
for context in /etc/getddo/.deploy-env.*/context; do test ! -e "$context"; done
exec 9>/opt/getddo/deploy.lock
flock -n 9
"""
        for instance in (a, c):
            # 최초 C에서는 A 파일/잠금도 변경하지 않는다.
            check_script = preflight
            if initial and instance == a:
                check_script = "set -eu\ntest ! -e /opt/getddo/deploy-in-progress\nfor context in /etc/getddo/.deploy-env.*/context; do test ! -e \"$context\"; done"
            checked(aws, instance, check_script)
        if initial:
            # A는 읽기 전용 확인만 한다. C의 첫 앱은 실제 A의 승인 이미지와 동일해야 한다.
            checked(aws, a, baseline_script(values))
            checked(aws, c, "set -eu\ntest ! -e /opt/getddo/current\ntest ! -L /opt/getddo/current")
        hosts_to_update = (("c", c, a),) if initial else (("a", a, c), ("c", c, a))
        for index, (server, target, other) in enumerate(hosts_to_update):
            # 첫 서버를 변경한 뒤에는 상대 health 확인 실패도 부분 배포 복구 대상으로 남긴다.
            if index > 0:
                safe_to_unlock = False
            healthy(other)
            if not initial:
                healthy(target)
                healthy(other)
                if index == 0:
                    # 업로드·원격 사전 검사 사이 main이 바뀌면 아직 정상인 두 서버를 건드리지 않는다.
                    # A를 바꾼 이후에는 같은 산출물로 C까지 마쳐 불필요한 버전 혼재를 피한다.
                    verify_current()
                safe_to_unlock = False
                aws("elbv2", "deregister-targets", "--target-group-arn", group,
                    "--targets", "Id=" + target + ",Port=8080")
                wait(target, "unused", other)
            healthy(other)
            payload = parameters(dict(values, SERVER=server), key, checksum)
            # 잠금은 다운로드부터 엔진 종료까지 유지한다. flock는 exec 뒤에도 상속된다.
            payload["commands"][0] = ("set -eu\nexec 8>/opt/getddo/orchestration.lock\nflock -n 8\n" +
                                       payload["commands"][0])
            safe_to_unlock = False
            result = invocation(aws, target, payload)
            if result["Status"] != "Success" or result["ResponseCode"] != 0:
                output = result.get("StandardOutputContent", "")
                confirmation = "ROLLBACK_CONFIRMED_HEALTHY: " + values["SOURCE_SHA"] + " sha256:"
                diagnostics = output + result.get("StandardErrorContent", "")
                if not initial and confirmation in output and "DEPLOY_INCOMPLETE:" not in diagnostics:
                    register(target, other)
                    safe_to_unlock = True
                else:
                    safe_to_unlock = False
                raise RuntimeError("Deployment failed; next host stopped: " + target)
            if not initial:
                register(target, other)
            healthy(other)
            safe_to_unlock = True
        if not initial:
            safe_to_unlock = False
            healthy(a)
            healthy(c)
            safe_to_unlock = True
    except UnknownOutcome:
        safe_to_unlock = False
        raise
    finally:
        if safe_to_unlock:
            aws("s3api", "delete-object", "--bucket", values["DEPLOY_BUCKET"], "--key", lock_key,
                "--expected-bucket-owner", values["ACCOUNT_ID"])
