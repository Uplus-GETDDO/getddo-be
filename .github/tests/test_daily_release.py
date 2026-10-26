"""자동 병합의 CI 조건·브랜치 변경·충돌·실패 경로를 대역으로 검증한다."""
import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

SOURCE = Path(__file__).resolve().parents[2] / 'infra/scripts/daily-release.py'
spec = importlib.util.spec_from_file_location('daily_release', SOURCE)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
DEV, MAIN = 'a' * 40, 'b' * 40
RUN = {'id': 7, 'head_sha': DEV, 'head_branch': 'dev', 'event': 'push',
       'status': 'completed', 'conclusion': 'success', 'html_url': 'https://github.com/test/run/7'}
JOBS = [{'name': name, 'status': 'completed', 'conclusion': 'success'}
        for name in ['빌드·테스트', '이미지 / 이미지 빌드 검증', '이미지 / Docker 이미지']]
PULL = {'number': 12, 'state': 'open', 'draft': False, 'mergeable': True,
        'head': {'ref': 'dev', 'sha': DEV, 'repo': {'full_name': 'owner/repo'}},
        'base': {'ref': 'main'}}


class FakeGitHub:
    repository = 'owner/repo'

    def __init__(self):
        """실제 GitHub 호출 없이 브랜치·CI·PR 상태를 독립적으로 준비한다."""
        self.refs = {'dev': DEV, 'main': MAIN}
        self.runs = [copy.deepcopy(RUN)]
        self.jobs = copy.deepcopy(JOBS)
        self.pull = copy.deepcopy(PULL)
        self.existing = []
        self.ahead = 1
        self.calls = []
        self.merged = True

    def ref(self, branch):
        """검증 중 브랜치 변경을 재현할 수 있도록 현재 SHA를 반환한다."""
        return self.refs[branch]

    def api(self, path, method='GET', data=None):
        """API 경로별 응답과 변경 요청을 기록해 외부 쓰기 없이 릴리스 흐름을 검증한다."""
        self.calls.append((path, method, data))
        if path.startswith('compare/'):
            return {'ahead_by': self.ahead}
        if path.startswith('actions/workflows/'):
            return {'workflow_runs': self.runs}
        if path.startswith('actions/runs/'):
            return {'jobs': self.jobs}
        if path.startswith('pulls?'):
            return self.existing
        if path == 'pulls' and method == 'POST':
            return self.pull
        if path == 'issues/12/labels' and method == 'POST':
            return [{'name': 'skip-review'}]
        if path == 'issues/12/labels/skip-review' and method == 'DELETE':
            return []
        if path.endswith('/merge'):
            return {'merged': self.merged, 'sha': 'c' * 40}
        if path == 'pulls/12':
            return self.pull
        raise AssertionError(path)

    def mutations(self):
        """PR 생성·라벨 적용·병합처럼 상태를 바꾸는 요청만 반환한다."""
        return [c for c in self.calls if c[1] != 'GET']


class DailyReleaseTests(unittest.TestCase):
    def test_success_merges_exact_sha_and_only_dev_to_main(self):
        """검증한 dev SHA만 main에 병합하고 그 전에 리뷰 예외 라벨을 적용하는지 확인한다."""
        github = FakeGitHub()
        self.assertIn('RELEASE_MERGED', release.release(github))
        create, label, merge = github.mutations()
        self.assertEqual(('issues/12/labels', 'POST', {'labels': ['skip-review']}), label)
        self.assertEqual(('main', 'dev'), (create[2]['base'], create[2]['head']))
        self.assertEqual({'sha': DEV, 'merge_method': 'merge'}, merge[2])

    def test_no_changes_does_not_mutate(self):
        """새 dev 변경이 없으면 PR 생성과 병합을 실행하지 않는지 확인한다."""
        github = FakeGitHub()
        github.ahead = 0
        self.assertIn('RELEASE_SKIPPED', release.release(github))
        self.assertFalse(github.mutations())

    def test_missing_failed_or_pending_ci_does_not_create_pr(self):
        """CI 부재·실패·진행 중이거나 다른 출처의 실행이면 PR을 만들지 않는지 확인한다."""
        for runs in [[], [{**RUN, 'status': 'in_progress'}], [{**RUN, 'conclusion': 'failure'}],
                     [{**RUN, 'head_sha': MAIN}], [{**RUN, 'event': 'pull_request'}]]:
            with self.subTest(runs=runs):
                github = FakeGitHub()
                github.runs = runs
                self.assertIn('RELEASE_SKIPPED', release.release(github))
                self.assertFalse(github.mutations())

    def test_missing_failed_or_skipped_required_job_does_not_create_pr(self):
        """각 필수 작업의 누락과 비정상 상태가 모두 릴리스를 막는지 확인한다."""
        cases = [JOBS[:index] + JOBS[index + 1:] for index in range(len(JOBS))]
        for index in range(len(JOBS)):
            for conclusion in ('failure', 'cancelled', 'skipped', 'neutral', 'timed_out'):
                jobs = copy.deepcopy(JOBS)
                jobs[index]['conclusion'] = conclusion
                cases.append(jobs)
            jobs = copy.deepcopy(JOBS)
            jobs[index]['status'] = 'in_progress'
            cases.append(jobs)
        for jobs in cases:
            with self.subTest(jobs=jobs):
                github = FakeGitHub()
                github.jobs = jobs
                self.assertIn('RELEASE_SKIPPED', release.release(github))
                self.assertFalse(github.mutations())

    def test_dev_build_with_skipped_main_publisher_is_eligible(self):
        """dev의 필수 검증 성공과 main 전용 작업 생략을 구분한다."""
        github = FakeGitHub()
        github.jobs.append({'name': 'main 이미지', 'status': 'completed', 'conclusion': 'skipped'})
        self.assertIn('RELEASE_DRY_RUN', release.release(github, dry_run=True))
        self.assertFalse(github.mutations())

    def test_successful_jobs_cannot_override_unsuccessful_build(self):
        """필수 작업이 성공해도 전체 Build 실패를 릴리스 대상으로 사용하지 않는다."""
        github = FakeGitHub()
        github.runs[0]['conclusion'] = 'failure'
        self.assertIsNone(release.successful_build(github, DEV))
        self.assertFalse(github.mutations())

    def test_dry_run_never_mutates(self):
        """조회 전용 실행에서는 검증에 성공해도 GitHub 상태를 변경하지 않는지 확인한다."""
        github = FakeGitHub()
        self.assertIn('RELEASE_DRY_RUN', release.release(github, dry_run=True))
        self.assertFalse(github.mutations())

    def test_existing_pr_is_reused(self):
        """열린 dev → main PR을 재사용하고 중복 생성하지 않는지 확인한다."""
        github = FakeGitHub()
        github.existing = [PULL]
        self.assertIn('RELEASE_MERGED', release.release(github))
        self.assertEqual(2, len(github.mutations()))

    def test_draft_closed_wrong_branch_sha_or_repository_is_not_merged(self):
        """초안·종료·잘못된 대상이나 소스의 PR을 병합하지 않는지 확인한다."""
        changes = [{'draft': True}, {'state': 'closed'},
                   {'head': {**PULL['head'], 'sha': MAIN}},
                   {'head': {**PULL['head'], 'ref': 'feature'}},
                   {'head': {**PULL['head'], 'repo': {'full_name': 'fork/repo'}}},
                   {'base': {'ref': 'dev'}}]
        for change in changes:
            with self.subTest(change=change):
                github = FakeGitHub()
                github.existing = [PULL]
                github.pull.update(change)
                self.assertIn('RELEASE_SKIPPED', release.release(github))
                self.assertFalse(github.mutations())

    def test_conflict_never_merges(self):
        """병합 충돌이 있는 PR은 변경 요청 없이 중단하는지 확인한다."""
        github = FakeGitHub()
        github.existing = [PULL]
        github.pull['mergeable'] = False
        self.assertIn('RELEASE_SKIPPED', release.release(github))
        self.assertFalse(github.mutations())

    def test_unknown_mergeability_times_out_without_merge(self):
        """병합 가능 여부가 끝내 확정되지 않으면 제한된 대기 후 중단하는지 확인한다."""
        github = FakeGitHub()
        github.existing = [PULL]
        github.pull['mergeable'] = None
        with patch.object(release.time, 'sleep') as sleep:
            self.assertIn('RELEASE_SKIPPED', release.release(github))
        self.assertEqual(6, sleep.call_count)
        self.assertFalse(github.mutations())

    def test_changed_dev_or_main_is_not_merged(self):
        """검증 중 어느 브랜치라도 바뀌면 조회한 이전 상태로 병합하지 않는지 확인한다."""
        for branch in ['dev', 'main']:
            with self.subTest(branch=branch):
                github = FakeGitHub()
                github.existing = [PULL]
                original = github.api
                def api(path, method='GET', data=None):
                    """PR 조회 시 브랜치를 갱신해 검증 도중 소스가 바뀌는 상황을 재현한다."""
                    if path == 'pulls/12':
                        github.refs[branch] = 'd' * 40
                    return original(path, method, data)
                github.api = api
                self.assertIn('RELEASE_SKIPPED', release.release(github))
                self.assertFalse(github.mutations())

    def test_ci_rerun_after_validation_blocks_merge(self):
        """병합 직전 CI 재확인에서 상태가 바뀌면 병합을 중단하는지 확인한다."""
        github = FakeGitHub()
        github.existing = [PULL]
        with patch.object(release, 'successful_build', side_effect=[RUN, None]):
            self.assertIn('RELEASE_SKIPPED', release.release(github))
        self.assertFalse(github.mutations())

    def test_label_failure_never_merges(self):
        """리뷰 예외 라벨을 적용하지 못하면 main 병합을 실행하지 않는다."""
        github = FakeGitHub()
        original = github.api
        def api(path, method='GET', data=None):
            """라벨 적용 요청만 실패시켜 이후 병합 호출이 차단되는지 검증한다."""
            if path == 'issues/12/labels':
                raise release.ReleaseError
            return original(path, method, data)
        github.api = api
        with self.assertRaises(release.ReleaseError):
            release.release(github)
        self.assertFalse(any(path.endswith('/merge') for path, _, _ in github.calls))

    def test_pr_changes_during_label_request_never_merge(self):
        """라벨 적용 중 대상·소스·상태가 바뀌면 검증했던 PR로 간주하지 않는다."""
        changes = [
            {'base': {'ref': 'dev'}}, {'state': 'closed'}, {'draft': True},
            {'head': {**PULL['head'], 'sha': MAIN}},
            {'head': {**PULL['head'], 'ref': 'feature'}},
            {'head': {**PULL['head'], 'repo': {'full_name': 'fork/repo'}}},
            {'mergeable': False}, {'mergeable': None},
        ]
        for change in changes:
            with self.subTest(change=change):
                github = FakeGitHub()
                original = github.api

                def api(path, method='GET', data=None, original=original, github=github, change=change):
                    """라벨 요청 직후 PR 정보만 바꾸어 재조회 누락을 재현한다."""
                    result = original(path, method, data)
                    if path == 'issues/12/labels':
                        github.pull.update(change)
                    return result

                github.api = api
                self.assertIn('RELEASE_SKIPPED', release.release(github))
                self.assertFalse(any(path.endswith('/merge') for path, _, _ in github.calls))
                outside_scope = ('base' in change or
                                 ('head' in change and
                                  (change['head']['ref'] != 'dev' or
                                   change['head']['repo']['full_name'] != github.repository)))
                removed = any(path == 'issues/12/labels/skip-review' and method == 'DELETE'
                              for path, method, _ in github.calls)
                self.assertEqual(outside_scope, removed)


    def test_branch_or_ci_changes_during_label_request_never_merge(self):
        """라벨 요청 중 dev·main 변경이나 CI 재실행이 생기면 병합을 중단한다."""
        for change in ('dev', 'main', 'ci'):
            with self.subTest(change=change):
                github = FakeGitHub()
                original = github.api

                def api(path, method='GET', data=None, original=original, github=github, change=change):
                    """라벨 적용 전에 통과했던 브랜치나 CI 근거를 무효화한다."""
                    result = original(path, method, data)
                    if path == 'issues/12/labels':
                        if change == 'ci':
                            github.runs[0]['status'] = 'in_progress'
                        else:
                            github.refs[change] = 'd' * 40
                    return result

                github.api = api
                self.assertIn('RELEASE_SKIPPED', release.release(github))
                self.assertFalse(any(path.endswith('/merge') for path, _, _ in github.calls))
                self.assertFalse(any(method == 'DELETE' for _, method, _ in github.calls))

    def test_merge_rejection_is_failure(self):
        """라벨 적용 후 병합 거부를 성공으로 보고하지 않고 라벨은 유지하는지 확인한다."""
        github = FakeGitHub()
        github.merged = False
        with self.assertRaises(release.ReleaseError):
            release.release(github)
        self.assertEqual(
            ('issues/12/labels', 'POST', {'labels': ['skip-review']}),
            github.mutations()[-2],
        )
        self.assertEqual({'sha': DEV, 'merge_method': 'merge'}, github.mutations()[-1][2])

    def test_api_failure_does_not_print_api_response_or_credentials(self):
        """API 호출 실패 시 응답 원문과 인증 정보가 오류 메시지에 섞이지 않는지 확인한다."""
        with patch.object(release.subprocess, 'run', return_value=Mock(returncode=1, stdout='secret', stderr='secret')):
            with self.assertRaises(release.ReleaseError) as error:
                release.GitHub('owner/repo').api('pulls')
        self.assertNotIn('secret', str(error.exception))

    def test_repository_is_validated(self):
        """저장소 경로에 명령 문자열이 섞인 입력을 API 호출 전에 거부하는지 확인한다."""
        with self.assertRaises(release.ReleaseError):
            release.GitHub('owner/repo; echo secret')


if __name__ == '__main__':
    unittest.main()
