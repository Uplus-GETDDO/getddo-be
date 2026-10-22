#!/usr/bin/env python3
"""고정 SHA의 Docker-save 산출물을 검증된 Stage a/c에만 배포한다."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile
import time
import uuid


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


deploy = load("stage_deploy_engine", "deploy-ssm.py")
rolling = load("stage_ssm", "rolling-deploy.py")


def manifest(document):
    required = {"account_id", "region", "vpc_id", "run_id", "instances", "expected_names",
                "target_group_arn", "deploy_bucket"}
    if not isinstance(document, dict) or set(document) != required:
        raise ValueError("Stage manifest fields mismatch")
    patterns = {"account_id": r"[0-9]{12}", "region": r"ap-northeast-2",
                "vpc_id": r"vpc-[a-f0-9]{8,17}", "run_id": r"[a-z0-9][a-z0-9-]{0,62}",
                "deploy_bucket": r"[a-z0-9][a-z0-9-]{1,61}[a-z0-9]"}
    for key, pattern in patterns.items():
        if not isinstance(document[key], str) or not re.fullmatch(pattern, document[key]):
            raise ValueError("Invalid Stage manifest field: " + key)
    bucket = document["deploy_bucket"]
    if (bucket != "getddo-deploy-" + document["account_id"] + "-" + document["region"] or
            "tfstate" in bucket or "terraform" in bucket):
        raise ValueError("Exact project deployment bucket is required")
    for key in ("instances", "expected_names"):
        if not isinstance(document[key], dict) or set(document[key]) != {"a", "c"}:
            raise ValueError("Exact a/c mapping required: " + key)
    for server in ("a", "c"):
        instance, name = document["instances"][server], document["expected_names"][server]
        if not isinstance(instance, str) or not re.fullmatch(r"i-[a-f0-9]{17}", instance):
            raise ValueError("Invalid exact Stage instance ID")
        if (not isinstance(name, str) or "staging" not in name or
                not re.fullmatch(r"getddo-staging-api-" + server, name)):
            raise ValueError("Expected staging API a/c names are required")
    if document["instances"]["a"] == document["instances"]["c"]:
        raise ValueError("Stage instances must be distinct")
    arn = (r"arn:aws:elasticloadbalancing:" + document["region"] + ":" + document["account_id"] +
           r":targetgroup/[A-Za-z0-9-]+/[a-f0-9]+")
    if not isinstance(document["target_group_arn"], str) or not re.fullmatch(arn, document["target_group_arn"]):
        raise ValueError("Stage target group account/region mismatch")
    return document


def tags(items):
    return {item["Key"]: item["Value"] for item in items}


def validate_targets(config, aws):
    """변경 없이 대상 경계를 검증한다. 실패 시 S3·SSM·TG를 변경하지 않는다."""
    if aws("sts", "get-caller-identity")["Account"] != config["account_id"]:
        raise ValueError("Stage AWS account mismatch")
    group = config["target_group_arn"]
    groups = aws("elbv2", "describe-target-groups", "--target-group-arns", group)["TargetGroups"]
    if (len(groups) != 1 or groups[0]["TargetGroupArn"] != group or
            groups[0]["VpcId"] != config["vpc_id"] or groups[0]["TargetType"] != "instance" or
            groups[0]["Port"] != 8080 or groups[0]["Protocol"] != "HTTP"):
        raise ValueError("Stage target group contract mismatch")
    balancers = groups[0].get("LoadBalancerArns", [])
    if len(balancers) != 1:
        raise ValueError("Stage target group must have one internal ALB")
    prefix = "arn:aws:elasticloadbalancing:" + config["region"] + ":" + config["account_id"] + ":loadbalancer/app/"
    if not balancers[0].startswith(prefix):
        raise ValueError("Stage ALB account/region mismatch")
    lbs = aws("elbv2", "describe-load-balancers", "--load-balancer-arns", *balancers)["LoadBalancers"]
    if (len(lbs) != 1 or lbs[0]["LoadBalancerArn"] != balancers[0] or
            lbs[0]["Scheme"] != "internal" or lbs[0]["VpcId"] != config["vpc_id"]):
        raise ValueError("Stage requires an internal ALB in the expected VPC")
    descriptions = aws("elbv2", "describe-tags", "--resource-arns", group, *balancers)["TagDescriptions"]
    if ({row["ResourceArn"] for row in descriptions} != {group, *balancers} or
            any(tags(row["Tags"]).get("Environment") != "staging" or
                tags(row["Tags"]).get("RunId") != config["run_id"] for row in descriptions)):
        raise ValueError("Stage TG/ALB tags required")
    location = aws("s3api", "get-bucket-location", "--bucket", config["deploy_bucket"],
                   "--expected-bucket-owner", config["account_id"])
    if location.get("LocationConstraint") != config["region"]:
        raise ValueError("Stage artifact bucket region mismatch")
    ids = config["instances"]
    reservations = aws("ec2", "describe-instances", "--instance-ids", ids["a"], ids["c"])["Reservations"]
    instances = [i for reservation in reservations for i in reservation["Instances"]]
    if len(instances) != 2 or {i["InstanceId"] for i in instances} != set(ids.values()):
        raise ValueError("Exact Stage instance response mismatch")
    for server, instance_id in ids.items():
        instance = next(i for i in instances if i["InstanceId"] == instance_id)
        labels = tags(instance.get("Tags", []))
        if (instance["State"]["Name"] != "running" or instance["VpcId"] != config["vpc_id"] or
                labels.get("Environment") != "staging" or labels.get("Name") != config["expected_names"][server] or
                labels.get("RunId") != config["run_id"]):
            raise ValueError("Stage instance state/VPC/environment/name mismatch")
    rows = aws("ssm", "describe-instance-information", "--filters",
               "Key=InstanceIds,Values=" + ids["a"] + "," + ids["c"])["InstanceInformationList"]
    if {row["InstanceId"] for row in rows if row["PingStatus"] == "Online"} != set(ids.values()):
        raise ValueError("Both Stage instances must be SSM Online")
    targets = aws("elbv2", "describe-target-health", "--target-group-arn", group)["TargetHealthDescriptions"]
    if any(row["Target"]["Id"] not in ids.values() or row["Target"]["Port"] != 8080 for row in targets):
        raise ValueError("Non-Stage target found in Stage target group")


def validate_artifact(source, artifact, sha, image_id, checksum):
    if (not re.fullmatch(r"[a-f0-9]{40}", sha) or
            not re.fullmatch(r"sha256:[a-f0-9]{64}", image_id) or
            not re.fullmatch(r"[a-f0-9]{64}", checksum)):
        raise ValueError("Full SHA, immutable image ID and archive SHA256 required")
    if deploy.command(["git", "-C", str(source), "rev-parse", "HEAD"]).strip() != sha:
        raise ValueError("Stage source checkout SHA mismatch")
    if deploy.command(["git", "-C", str(source), "status", "--porcelain", "--untracked-files=no"]).strip():
        raise ValueError("Stage source checkout must have no tracked changes")
    for name in ("image.tar.gz", "image.tar.gz.sha256", "image-id.txt"):
        if not (artifact / name).is_file() or (artifact / name).is_symlink():
            raise ValueError("Missing/unsafe Stage image artifact")
    if (artifact / "image-id.txt").read_text().strip() != image_id:
        raise ValueError("Pinned Stage image ID mismatch")
    expected = checksum + "  image.tar.gz"
    if (artifact / "image.tar.gz.sha256").read_text().strip() != expected:
        raise ValueError("Pinned Stage archive checksum mismatch")
    digest = hashlib.sha256()
    with (artifact / "image.tar.gz").open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != checksum:
        raise ValueError("Stage image archive integrity failure")
    # Docker save의 config digest와 revision을 전송 전에도 대조한다. 파일을 추출하지 않는다.
    with tarfile.open(artifact / "image.tar.gz", "r:gz") as archive:
        images = json.load(archive.extractfile("manifest.json"))
        if len(images) != 1 or "getddo-be:" + sha not in images[0]["RepoTags"]:
            raise ValueError("Stage archive must contain exactly the pinned SHA image")
        config = archive.extractfile(images[0]["Config"]).read()
        if "sha256:" + hashlib.sha256(config).hexdigest() != image_id:
            raise ValueError("Stage archive config digest mismatch")
        if json.loads(config).get("config", {}).get("Labels", {}).get("org.opencontainers.image.revision") != sha:
            raise ValueError("Stage image revision mismatch")


def parameters(config, server, sha, key, checksum, attempt="1"):
    values = {"GITHUB_RUN_ID": config["run_id"], "GITHUB_RUN_ATTEMPT": attempt,
              "DEPLOY_BUCKET": config["deploy_bucket"], "ACCOUNT_ID": config["account_id"],
              "AWS_REGION": config["region"], "SOURCE_SHA": sha, "SERVER": server, "DEPLOY_MODE": "rolling"}
    payload = deploy.parameters(values, key, checksum)
    # Only this Stage entry point selects staging. Production parameters remain untouched.
    payload["commands"][0] = payload["commands"][0].replace(
        "exec env GETDDO_DEPLOY_SERVER=", "exec env GETDDO_DEPLOY_ENVIRONMENT=staging GETDDO_DEPLOY_SERVER=", 1)
    return payload


def run(config, source, artifact, sha, image_id, checksum, aws, check_only=False):
    validate_artifact(source, artifact, sha, image_id, checksum)
    validate_targets(config, aws)
    if check_only:
        print("STAGING_CHECK_OK: source, image and exact Stage targets verified; no writes")
        return

    def require_healthy(instance):
        rows = aws("elbv2", "describe-target-health", "--target-group-arn", config["target_group_arn"],
                   "--targets", "Id=" + instance + ",Port=8080")["TargetHealthDescriptions"]
        if (len(rows) != 1 or rows[0]["Target"]["Id"] != instance or
                rows[0]["Target"]["Port"] != 8080 or rows[0]["TargetHealth"]["State"] != "healthy"):
            raise RuntimeError("Required Stage target is not healthy; retain Stage lock")

    prefix = "staging/" + config["run_id"] + "/"
    attempt = str(uuid.uuid4().int)
    key = prefix + "releases/" + sha + "/" + attempt + "/release.tar"
    lock = "staging/locks/app.json"
    common = ("--bucket", config["deploy_bucket"], "--expected-bucket-owner", config["account_id"])
    # Lock must be retained when a remote outcome or any partial a/c deployment is unresolved.
    aws("s3api", "put-object", *common, "--key", lock, "--if-none-match", "*",
        "--server-side-encryption", "AES256", "--metadata", "source-sha=" + sha + ",image-id=" + image_id + ",run-id=" + config["run_id"])
    safe_to_unlock = True
    try:
        with tempfile.TemporaryDirectory(prefix="getddo-stage-") as temporary:
            archive = Path(temporary) / "release.tar"
            release_checksum = deploy.bundle(source, artifact, archive, source)
            aws("s3api", "put-object", *common, "--key", key, "--body", str(archive),
                "--if-none-match", "*", "--server-side-encryption", "AES256", timeout=600)
        initial = aws("elbv2", "describe-target-health", "--target-group-arn", config["target_group_arn"])["TargetHealthDescriptions"]
        healthy = {row["Target"]["Id"] for row in initial if row["TargetHealth"]["State"] == "healthy"}
        # 먼저 비정상/미등록 호스트를 준비하여 유일한 정상 호스트를 유지한다.
        servers = sorted(("a", "c"), key=lambda server: config["instances"][server] in healthy)
        recovered = set()
        for server in servers:
            safe_to_unlock = False
            validate_targets(config, aws)
            rows = aws("elbv2", "describe-target-health", "--target-group-arn", config["target_group_arn"])["TargetHealthDescriptions"]
            healthy = {row["Target"]["Id"] for row in rows if row["TargetHealth"]["State"] == "healthy"}
            selected = config["instances"][server]
            # 이미 복구한 상대가 이탈한 경우 최초 생성 예외로 다시 진입하지 않는다.
            peers = (healthy | recovered) - {selected}
            if selected in healthy and not peers:
                raise RuntimeError("Cannot drain the only healthy Stage target; retain Stage lock")
            safe_to_unlock = False
            for peer in peers:
                require_healthy(peer)
            aws("elbv2", "deregister-targets", "--target-group-arn", config["target_group_arn"],
                "--targets", "Id=" + config["instances"][server] + ",Port=8080")
            deadline = time.monotonic() + 300
            while True:
                for peer in peers:
                    require_healthy(peer)
                rows = aws("elbv2", "describe-target-health", "--target-group-arn", config["target_group_arn"],
                           "--targets", "Id=" + config["instances"][server] + ",Port=8080")["TargetHealthDescriptions"]
                if not rows or (len(rows) == 1 and rows[0]["Target"]["Id"] == config["instances"][server] and
                                rows[0]["Target"]["Port"] == 8080 and rows[0]["TargetHealth"]["State"] == "unused"):
                    break
                if time.monotonic() >= deadline:
                    raise RuntimeError("Stage ALB drain timeout; retain Stage lock")
                time.sleep(5)
            for peer in peers:
                require_healthy(peer)
            result = rolling.invocation(aws, config["instances"][server], parameters(config, server, sha, key, release_checksum, attempt))
            if result["Status"] != "Success" or result["ResponseCode"] != 0:
                raise RuntimeError("Stage host deployment failed; next host stopped; retain Stage lock")
            aws("elbv2", "register-targets", "--target-group-arn", config["target_group_arn"],
                "--targets", "Id=" + config["instances"][server] + ",Port=8080")
            deadline = time.monotonic() + 300
            while True:
                rows = aws("elbv2", "describe-target-health", "--target-group-arn", config["target_group_arn"],
                           "--targets", "Id=" + config["instances"][server] + ",Port=8080")["TargetHealthDescriptions"]
                if (len(rows) == 1 and rows[0]["Target"]["Id"] == config["instances"][server] and
                        rows[0]["Target"]["Port"] == 8080 and rows[0]["TargetHealth"]["State"] == "healthy"):
                    break
                if time.monotonic() >= deadline:
                    raise RuntimeError("Stage ALB health timeout; retain Stage lock")
                time.sleep(10)
            # 운영 rolling 배포와 동일하게 2분 동안 10초 간격으로 연속 정상을 확인한다.
            for _ in range(12):
                time.sleep(10)
                for peer in peers:
                    require_healthy(peer)
                require_healthy(selected)
            recovered.add(selected)
        # 첫 서버가 두 번째 교체 중 이탈했을 수 있으므로 전체 두 대를 마지막에 다시 확인한다.
        rows = aws("elbv2", "describe-target-health", "--target-group-arn", config["target_group_arn"])["TargetHealthDescriptions"]
        if (len(rows) != 2 or {row["Target"]["Id"] for row in rows} != set(config["instances"].values()) or
                any(row["Target"]["Port"] != 8080 or row["TargetHealth"]["State"] != "healthy" for row in rows)):
            raise RuntimeError("Final Stage a/c health mismatch; retain Stage lock")
        safe_to_unlock = True
    finally:
        if safe_to_unlock:
            aws("s3api", "delete-object", *common, "--key", lock)
    print("STAGING_DEPLOY_OK: " + sha + " " + image_id)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--image-id", required=True)
    parser.add_argument("--archive-sha256", required=True)
    parser.add_argument("--artifact-dir", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    try:
        config = manifest(json.loads(args.manifest.read_text()))
        def aws(*arguments, timeout=70):
            output = deploy.command(["aws", *arguments, "--region", config["region"],
                                     "--output", "json", "--no-cli-pager"], timeout)
            return json.loads(output) if output.strip() else {}
        run(config, Path(__file__).resolve().parents[2], args.artifact_dir, args.source_sha,
            args.image_id, args.archive_sha256, aws, args.check)
    except (ValueError, KeyError, TypeError, RuntimeError, OSError, tarfile.TarError, subprocess.SubprocessError):
        # AWS/SSM stderr can contain secrets: never relay raw exceptions or remote output.
        print("STAGING_DEPLOY_FAILED: inspect Stage command status/lock and local inputs; no automatic retry", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
