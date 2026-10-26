#!/usr/bin/env python3
"""최신 dev의 성공한 Build를 확인하고 dev → main PR을 병합한다."""
import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path


class ReleaseError(RuntimeError):
    """인증 정보나 API 원문을 포함하지 않는 릴리스 오류."""


class GitHub:
    def __init__(self, repository):
        """명령 인자에 사용할 저장소 이름을 검증하고 호출 대상을 고정한다."""
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise ReleaseError("GITHUB_REPOSITORY를 확인하세요.")
        self.repository = repository

    def api(self, path, method="GET", data=None):
        """요청 본문은 표준 입력으로 전달하고 실패 시 API 원문을 노출하지 않는다."""
        args = ["gh", "api", f"repos/{self.repository}/{path}", "--method", method]
        if data is not None:
            args.extend(["--input", "-"])
        result = subprocess.run(args, input=json.dumps(data) if data is not None else None,
                                capture_output=True, text=True, timeout=60)
        if result.returncode:
            raise ReleaseError("GitHub API 요청 실패. Actions 로그의 단계와 App 권한을 확인하세요.")
        return json.loads(result.stdout)

    def ref(self, branch):
        """지정한 브랜치의 현재 SHA를 조회해 검증 전후 상태를 비교한다."""
        return self.api(f"git/ref/heads/{branch}")["object"]["sha"]


def successful_build(github, sha):
    """같은 dev 커밋의 가장 최근 push Build와 필수 검증 작업을 검사한다."""
    runs = github.api(f"actions/workflows/build.yml/runs?branch=dev&event=push&head_sha={sha}&per_page=1")["workflow_runs"]
    if not runs:
        return None
    run = runs[0]
    if (run["head_sha"] != sha or run["head_branch"] != "dev" or run["event"] != "push"
            or run["status"] != "completed" or run["conclusion"] != "success"):
        return None
    # 재실행에서는 최신 attempt의 작업만 받는다. 생략된 필수 작업을 CI 성공으로 간주하지 않는다.
    jobs = github.api(f"actions/runs/{run['id']}/jobs?filter=latest&per_page=100")["jobs"]
    expected = {"빌드·테스트", "이미지 / 이미지 빌드 검증", "이미지 / Docker 이미지"}
    if not expected.issubset({job["name"] for job in jobs}):
        return None
    # dev에서 생략되는 main 전용 게시 작업은 필수 검증에 포함하지 않는다.
    # 전체 Build 성공 조건은 위에서 별도로 확인하므로 다른 작업 실패도 통과하지 않는다.
    if any(job["status"] != "completed" or job["conclusion"] != "success"
           for job in jobs if job["name"] in expected):
        return None
    return run


def release(github, dry_run=False):
    """실패·변경 없음·충돌·검증 중 브랜치 변경 시 병합하지 않는다."""
    dev, main = github.ref("dev"), github.ref("main")
    comparison = github.api(f"compare/{main}...{dev}")
    if comparison["ahead_by"] == 0:
        return "RELEASE_SKIPPED: main에 반영할 dev 커밋이 없습니다."
    build = successful_build(github, dev)
    if build is None:
        return "RELEASE_SKIPPED: 최신 dev의 Build·테스트·이미지가 모두 성공하지 않았습니다."
    if dry_run:
        return f"RELEASE_DRY_RUN: dev {dev}의 CI 성공. PR 생성·병합은 실행하지 않았습니다."
    pulls = github.api(f"pulls?state=open&base=main&head={github.repository.split('/')[0]}:dev")
    if len(pulls) > 1:
        raise ReleaseError("dev → main PR이 여러 개입니다. 수동 확인이 필요합니다.")
    if pulls:
        number = pulls[0]["number"]
    else:
        pull = github.api("pulls", "POST", {
            "base": "main", "head": "dev",
            "title": "[chore] GD-115 매일 dev 변경 main 반영",
            "body": ("## 배포 대상\n\n최신 dev의 성공한 Build를 확인한 정기 릴리스입니다.\n\n"
                     f"- dev: `{dev}`\n- CI: {build['html_url']}\n"
                     "- 관련: GD-115\n\nmain 병합 후 기존 Build → Container CD가 실행됩니다.\n"
                     "최신 dev CI가 실패·진행 중이거나 브랜치가 변경되면 자동 병합하지 않습니다."),
        })
        number = pull["number"]
    # GitHub가 생성 직후에는 mergeable을 null로 반환할 수 있다. 최대 1분만 기다린다.
    for attempt in range(7):
        pull = github.api(f"pulls/{number}")
        if (pull["state"] != "open" or pull["draft"] or pull["head"]["ref"] != "dev"
                or pull["base"]["ref"] != "main" or pull["head"]["sha"] != dev
                or pull["head"]["repo"]["full_name"] != github.repository):
            return "RELEASE_SKIPPED: PR 대상·커밋·상태가 달라졌습니다."
        if pull["mergeable"] is not None:
            break
        if attempt < 6:
            time.sleep(10)
    if pull["mergeable"] is not True:
        return "RELEASE_SKIPPED: PR 충돌 또는 병합 가능 여부를 확인하지 못했습니다."
    # 조회 이후 새 dev 커밋이나 main 배포가 생기면 오늘 실행은 건너뛴다.
    if github.ref("dev") != dev or github.ref("main") != main:
        return "RELEASE_SKIPPED: 검증 중 dev 또는 main이 변경됐습니다."
    if successful_build(github, dev) is None:
        return "RELEASE_SKIPPED: dev CI 상태가 변경됐습니다."
    # main 릴리스 PR은 저장소의 리뷰 예외 라벨을 병합 전에 적용한다.
    github.api(f"issues/{number}/labels", "POST", {"labels": ["skip-review"]})
    # 라벨 요청도 외부 호출이므로 그동안 변경된 대상과 검증 근거를 다시 확인한다.
    pull = github.api(f"pulls/{number}")
    if (pull["base"]["ref"] != "main" or pull["head"]["ref"] != "dev"
            or pull["head"]["repo"]["full_name"] != github.repository):
        # 리뷰 예외가 일반 기능 PR로 이어지지 않도록 범위를 벗어난 라벨만 회수한다.
        github.api(f"issues/{number}/labels/skip-review", "DELETE")
        return "RELEASE_SKIPPED: PR이 main 릴리스 범위를 벗어나 리뷰 예외 라벨을 제거했습니다."
    if (pull["state"] != "open" or pull["draft"] or pull["head"]["ref"] != "dev"
            or pull["base"]["ref"] != "main" or pull["head"]["sha"] != dev
            or pull["head"]["repo"]["full_name"] != github.repository
            or pull["mergeable"] is not True):
        return "RELEASE_SKIPPED: 라벨 적용 중 PR 대상·커밋·상태가 달라졌습니다."
    if github.ref("dev") != dev or github.ref("main") != main:
        return "RELEASE_SKIPPED: 라벨 적용 중 dev 또는 main이 변경됐습니다."
    if successful_build(github, dev) is None:
        return "RELEASE_SKIPPED: 라벨 적용 중 dev CI 상태가 변경됐습니다."
    merged = github.api(f"pulls/{number}/merge", "PUT", {"sha": dev, "merge_method": "merge"})
    if not merged.get("merged"):
        raise ReleaseError("PR을 병합하지 못했습니다. main 보호 규칙과 App 예외를 확인하세요.")
    return f"RELEASE_MERGED: PR #{number}, main {merged['sha']}. 후속 Build·Container CD를 확인하세요."


def main():
    """조회 전용 옵션을 처리하고 실행 결과만 출력하며 실패 원문은 숨긴다."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="CI 상태만 확인하며 PR을 만들거나 병합하지 않음")
    args = parser.parse_args()
    try:
        message = release(GitHub(os.environ.get("GITHUB_REPOSITORY", "")), args.dry_run)
    except (ReleaseError, KeyError, ValueError, TypeError, subprocess.SubprocessError):
        print("RELEASE_FAILED: GitHub 응답·App 권한·브랜치 보호 설정을 확인하세요.", file=sys.stderr)
        return 1
    print(message)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as summary:
            summary.write(message + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
