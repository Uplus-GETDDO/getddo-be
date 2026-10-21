"""격리된 AWS 응답으로 배포 순서·실패 격리·미확인 명령 차단을 검증한다."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'infra/scripts' / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


rolling = load('rolling', 'rolling-deploy.py')
deploy = load('deploy', 'deploy-ssm.py')
env = load('inject_env', 'inject-host-env.py')
A, C = 'i-' + 'a' * 17, 'i-' + 'c' * 17
SHA = 'a' * 40
IMAGE = 'sha256:' + 'b' * 64


def values(mode='rolling'):
    result = dict(AWS_REGION='ap-northeast-2', AWS_ROLE_ARN='arn:aws:iam::123456789012:role/cd',
                  DEPLOY_BUCKET='getddo-deploy', SOURCE_SHA=SHA, GITHUB_REPOSITORY='org/repo',
                  GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1', RUNNER_TEMP='/tmp',
                  DEPLOY_MODE=mode, ROLLING_DEPLOY_ENABLED='true', EC2_INSTANCE_ID_A=A,
                  EC2_INSTANCE_ID_C=C, EC2_INSTANCE_ID=A,
                  TARGET_GROUP_ARN='arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:targetgroup/api/123abc',
                  APP_SOURCE_DIR='/tmp/baseline', APPROVED_BASELINE_SHA=SHA,
                  APPROVED_BASELINE_IMAGE_ID=IMAGE)
    return deploy.settings(result)


class FakeAWS:
    def __init__(self, fail=None, rollback=True, unknown=False, initial=False):
        self.events = []
        self.states = {A: 'healthy', C: 'unused' if initial else 'healthy'}
        self.fail, self.rollback, self.unknown = fail, rollback, unknown
        self.command = None
        self.command_count = 0
        self.lock = False
        self.drain_timeout = False
        self.deregistration_delay = 300
        self.other_fails = False
        self.stale = False
        self.incomplete = False

    def __call__(self, service, operation, *args, **kwargs):
        def value(flag):
            return args[args.index(flag) + 1]
        if service == 's3api':
            self.events.append((operation,))
            if operation == 'put-object':
                if self.lock:
                    raise RuntimeError('PreconditionFailed')
                self.lock = True
            if operation == 'delete-object':
                self.lock = False
            return {}
        if service == 'sts':
            return {'Account': '123456789012'}
        if service == 'ec2':
            return {'Reservations': [{'Instances': [dict(InstanceId=i, State={'Name': 'running'}, VpcId='vpc-1') for i in (A, C)]}]}
        if operation == 'describe-instance-information':
            return {'InstanceInformationList': [dict(InstanceId=i, PingStatus='Online') for i in (A, C)]}
        if operation == 'describe-target-groups':
            return {'TargetGroups': [dict(TargetType='instance', Port=8080, VpcId='vpc-1')]}
        if operation == 'describe-target-group-attributes':
            return {'Attributes': [dict(Key='deregistration_delay.timeout_seconds',
                                        Value=str(self.deregistration_delay))]}
        if service == 'elbv2':
            instance = value('--targets').split(',')[0][3:]
            self.events.append((operation, instance))
            if operation == 'describe-target-health':
                state = self.states[instance]
                return {'TargetHealthDescriptions': [dict(Target={'Id': instance, 'Port': 8080},
                        TargetHealth={'State': state, 'Reason': 'Target.NotRegistered' if state == 'unused' else ''})]}
            if operation == 'deregister-targets':
                self.states[instance] = 'draining' if self.drain_timeout else 'unused'
                if self.other_fails:
                    self.states[C if instance == A else A] = 'unhealthy'
            if operation == 'register-targets':
                self.states[instance] = 'healthy'
            return {}
        if operation == 'send-command':
            import json
            instance = value('--instance-ids')
            script = json.loads(value('--parameters'))['commands'][0]
            kind = 'deploy' if 'deploy-container.sh' in script else 'preflight'
            self.events.append((kind, instance, script))
            self.command = (kind, instance)
            self.command_count += 1
            return {'Command': {'CommandId': str(self.command_count)}}
        if operation == 'get-command-invocation':
            kind, instance = self.command
            if kind == 'deploy' and self.unknown:
                raise RuntimeError('Network outcome unknown')
            if kind == 'deploy' and instance == self.fail:
                token = ('ROLLBACK_OK: old run' if self.stale else
                         'ROLLBACK_CONFIRMED_HEALTHY: ' + SHA + ' ' + IMAGE) if self.rollback else 'ROLLBACK_FAILED'
                return dict(Status='Failed', ResponseCode=1, StandardOutputContent=token,
                            StandardErrorContent='DEPLOY_INCOMPLETE:' if self.incomplete else '')
            return dict(Status='Success', ResponseCode=0, StandardOutputContent='DEPLOY_OK: ' + SHA)
        raise AssertionError((service, operation, args))


class RollingTest(unittest.TestCase):
    def setUp(self):
        self.sleep = patch.object(rolling.time, 'sleep', return_value=None)
        self.sleep.start()
        self.addCleanup(self.sleep.stop)

    def run_roll(self, fake, mode='rolling', verify_current=None):
        verify = verify_current or (lambda: fake.events.append(('verify-main',)))
        rolling.run(values(mode), 'releases/approved', 'c' * 64, fake, deploy.parameters, verify)

    def deployments(self, fake):
        return [e[1] for e in fake.events if e[0] == 'deploy']

    def registrations(self, fake):
        return [e[1] for e in fake.events if e[0] == 'register-targets']

    def test_success_order_same_immutable_artifact(self):
        fake = FakeAWS()
        self.run_roll(fake)
        self.assertEqual([A, C], self.deployments(fake))
        self.assertEqual([A, C], self.registrations(fake))
        mutations = [(e[0], e[1]) for e in fake.events if e[0] in ('deregister-targets', 'register-targets', 'deploy')]
        self.assertEqual([('deregister-targets', A), ('deploy', A), ('register-targets', A),
                          ('deregister-targets', C), ('deploy', C), ('register-targets', C)], mutations)
        for e in fake.events:
            if e[0] == 'deploy':
                self.assertIn('releases/approved', e[2])
                self.assertIn('c' * 64, e[2])
                self.assertIn(SHA, e[2])
        self.assertFalse(fake.lock)

    def test_a_failure_restores_only_a_and_stops_c(self):
        fake = FakeAWS(fail=A)
        with self.assertRaises(RuntimeError):
            self.run_roll(fake)
        self.assertEqual([A], self.deployments(fake))
        self.assertEqual([A], self.registrations(fake))
        self.assertFalse(fake.lock)

    def test_c_failure_leaves_new_a_and_restores_c(self):
        fake = FakeAWS(fail=C)
        with self.assertRaises(RuntimeError):
            self.run_roll(fake)
        self.assertEqual([A, C], self.deployments(fake))
        self.assertEqual([A, C], self.registrations(fake))

    def test_main_advanced_during_preflight_does_not_drain_or_deploy(self):
        fake = FakeAWS()
        def changed_main():
            raise RuntimeError('main advanced')
        with self.assertRaisesRegex(RuntimeError, 'main advanced'):
            self.run_roll(fake, verify_current=changed_main)
        self.assertEqual([], self.deployments(fake))
        self.assertFalse(any(e[0] == 'deregister-targets' for e in fake.events))
        self.assertFalse(fake.lock)

    def test_main_checked_once_after_preflight_before_first_drain(self):
        fake = FakeAWS()
        self.run_roll(fake)
        boundary = fake.events.index(('verify-main',))
        preflights = [i for i, e in enumerate(fake.events) if e[0] == 'preflight']
        first_drain = next(i for i, e in enumerate(fake.events) if e[0] == 'deregister-targets')
        self.assertLess(max(preflights), boundary)
        self.assertLess(boundary, first_drain)
        self.assertEqual(1, fake.events.count(('verify-main',)))

    def test_a_becomes_unhealthy_between_hosts_preserves_recovery_lock(self):
        fake = FakeAWS()
        reads_after_registration = 0
        def failing_between_hosts(service, operation, *args, **kwargs):
            nonlocal reads_after_registration
            if (service == 'elbv2' and operation == 'describe-target-health'
                    and args[args.index('--targets') + 1].startswith('Id=' + A)
                    and ('register-targets', A) in fake.events):
                reads_after_registration += 1
                # 등록 대기 1회·안정 확인 12회 다음, C를 제외하기 전의 A 확인에서 장애가 난다.
                if reads_after_registration == 14:
                    fake.states[A] = 'unhealthy'
            return fake(service, operation, *args, **kwargs)
        with self.assertRaisesRegex(RuntimeError, 'not healthy'):
            rolling.run(values(), 'releases/approved', 'c' * 64,
                        failing_between_hosts, deploy.parameters, lambda: None)
        self.assertEqual([A], self.deployments(fake))
        self.assertEqual('healthy', fake.states[C])
        self.assertFalse(any(e[:2] == ('deregister-targets', C) for e in fake.events))
        self.assertTrue(fake.lock)

    def test_rollback_failure_retains_exclusion_and_lock(self):
        fake = FakeAWS(fail=A, rollback=False)
        with self.assertRaises(RuntimeError):
            self.run_roll(fake)
        self.assertEqual([], self.registrations(fake))
        self.assertEqual('unused', fake.states[A])
        self.assertTrue(fake.lock)

    def test_unknown_keeps_lock_and_retry_sends_no_new_command(self):
        fake = FakeAWS(unknown=True)
        with self.assertRaises(rolling.UnknownOutcome):
            self.run_roll(fake)
        count = fake.command_count
        self.assertEqual([], self.registrations(fake))
        self.assertTrue(fake.lock)
        with self.assertRaisesRegex(RuntimeError, 'PreconditionFailed'):
            self.run_roll(fake)
        self.assertEqual(count, fake.command_count)

    def test_stale_rollback_marker_does_not_register(self):
        fake = FakeAWS(fail=A)
        fake.stale = True
        with self.assertRaises(RuntimeError):
            self.run_roll(fake)
        self.assertEqual([], self.registrations(fake))
        self.assertTrue(fake.lock)

    def test_incomplete_cleanup_blocks_rollback_registration(self):
        fake = FakeAWS(fail=A)
        fake.incomplete = True
        with self.assertRaises(RuntimeError):
            self.run_roll(fake)
        self.assertEqual([], self.registrations(fake))

    def test_draining_timeout_stops_before_host_mutation(self):
        fake = FakeAWS()
        fake.drain_timeout = True
        clock = [0]
        def advance(seconds):
            clock[0] += seconds
        with patch.object(rolling.time, 'monotonic', side_effect=lambda: clock[0]), \
             patch.object(rolling.time, 'sleep', side_effect=advance):
            with self.assertRaisesRegex(RuntimeError, 'transition timed out'):
                self.run_roll(fake)
        self.assertEqual(360, clock[0])
        self.assertEqual([], self.deployments(fake))
        self.assertEqual([], self.registrations(fake))

    def test_drain_observes_unused_after_configured_delay(self):
        for delay in (300, 600):
            with self.subTest(delay=delay):
                fake = FakeAWS()
                fake.drain_timeout = True
                fake.deregistration_delay = delay
                clock = [0]
                drained_at = {}
                def advance(seconds):
                    clock[0] += seconds
                def delayed(service, operation, *args, **kwargs):
                    if operation == 'describe-target-health':
                        instance = args[args.index('--targets') + 1].split(',')[0][3:]
                        if instance in drained_at and clock[0] >= drained_at[instance] + delay + 10:
                            fake.states[instance] = 'unused'
                            del drained_at[instance]
                    result = fake(service, operation, *args, **kwargs)
                    if operation == 'deregister-targets':
                        instance = args[args.index('--targets') + 1].split(',')[0][3:]
                        drained_at[instance] = clock[0]
                    return result
                with patch.object(rolling.time, 'monotonic', side_effect=lambda: clock[0]), \
                     patch.object(rolling.time, 'sleep', side_effect=advance):
                    self.run_roll(delayed, verify_current=lambda: None)
                self.assertEqual([A, C], self.deployments(fake))
                self.assertFalse(fake.lock)

    def test_other_unhealthy_during_drain_stops_update(self):
        fake = FakeAWS()
        fake.other_fails = True
        with self.assertRaisesRegex(RuntimeError, 'not healthy'):
            self.run_roll(fake)
        self.assertEqual([], self.deployments(fake))

    def test_preflight_requires_both_healthy(self):
        fake = FakeAWS()
        fake.states[C] = 'unhealthy'
        with self.assertRaises(RuntimeError):
            self.run_roll(fake)
        self.assertEqual([], self.deployments(fake))
        self.assertEqual(0, fake.command_count)

    def test_initial_c_never_mutates_a_or_alb(self):
        fake = FakeAWS(initial=True)
        self.run_roll(fake, 'initial-c')
        self.assertEqual([C], self.deployments(fake))
        self.assertEqual([], self.registrations(fake))
        self.assertFalse(any(e[0] == 'deregister-targets' for e in fake.events))
        self.assertNotIn(('verify-main',), fake.events)
        for e in fake.events:
            if e[0] == 'preflight' and e[1] == A:
                self.assertNotIn('exec 8>', e[2])
                self.assertNotIn('exec 9>', e[2])
        script = next(e[2] for e in fake.events if e[0] == 'deploy')
        self.assertIn('GETDDO_DEPLOY_METRICS_ENABLED=false', script)

    def test_initial_a_revision_template_reaches_docker_without_backslashes(self):
        import os
        import subprocess
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / 'image-id.txt').write_text(IMAGE)
            docker = path / 'docker'
            docker.write_text("#!/usr/bin/env python3\nimport sys\na=sys.argv[1:]\n"
                              "if a[0]=='ps': print('container')\n"
                              "elif a[0]=='inspect': print('true " + IMAGE + "')\n"
                              "else:\n assert a[a.index('--format')+1] == '{{ index .Config.Labels \"org.opencontainers.image.revision\" }}'\n print('" + SHA + "')\n")
            docker.chmod(0o700)
            curl = path / 'curl'
            curl.write_text('#!/bin/sh\nexit 0\n')
            curl.chmod(0o700)
            script = rolling.baseline_script(values('initial-c')).replace('/opt/getddo/current/image-id.txt', str(path/'image-id.txt'))
            result = subprocess.run(['bash', '-c', script], env=dict(os.environ, PATH=str(path)+':'+os.environ['PATH']), capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_initial_c_failure_does_not_register_or_touch_a(self):
        fake = FakeAWS(initial=True, fail=C, rollback=False)
        with self.assertRaises(RuntimeError):
            self.run_roll(fake, 'initial-c')
        self.assertEqual([C], self.deployments(fake))
        self.assertEqual([], self.registrations(fake))

    def test_initial_c_rejects_already_registered_c(self):
        fake = FakeAWS()
        with self.assertRaisesRegex(RuntimeError, 'unregistered'):
            self.run_roll(fake, 'initial-c')
        self.assertEqual(0, fake.command_count)

    def test_ssm_send_response_loss_never_starts_next_command(self):
        fake = FakeAWS()
        def lost(service, operation, *args, **kwargs):
            if operation == 'send-command':
                fake.events.append(('send-lost',))
                raise OSError('response lost after send')
            return fake(service, operation, *args, **kwargs)
        with self.assertRaises(rolling.UnknownOutcome):
            rolling.run(values(), 'releases/approved', 'c' * 64, lost, deploy.parameters, lambda: None)
        self.assertTrue(fake.lock)
        self.assertEqual(1, len([e for e in fake.events if e[0] == 'send-lost']))
        self.assertEqual([], self.registrations(fake))

    def test_ssm_cancelled_and_wait_timeout_are_unknown(self):
        for status in ('Cancelled', 'TimedOut', 'Cancelling'):
            def response(service, operation, *args, **kwargs):
                if operation == 'send-command':
                    return {'Command': {'CommandId': 'exact-command'}}
                return {'Status': status, 'ResponseCode': -1}
            with self.subTest(status=status), self.assertRaises(rolling.UnknownOutcome):
                rolling.invocation(response, A, {'commands': ['true']})
        def pending(service, operation, *args, **kwargs):
            return {'Command': {'CommandId': 'exact-command'}} if operation == 'send-command' else {'Status': 'Pending'}
        with patch.object(rolling.time, 'monotonic', side_effect=[0, 2041]):
            with self.assertRaises(rolling.UnknownOutcome):
                rolling.invocation(pending, A, {'commands': ['true']})



class SettingsAndEnvTest(unittest.TestCase):
    def test_aws_accepts_empty_success_output_but_rejects_malformed_json(self):
        with patch.dict(deploy.os.environ, AWS_REGION='ap-northeast-2'):
            for output in ('', ' \n', '{"ETag": "test"}'):
                with self.subTest(output=output), patch.object(deploy, 'command', return_value=output):
                    self.assertEqual(json.loads(output) if output.strip() else {},
                                     deploy.aws('elbv2', 'register-targets'))
            with patch.object(deploy, 'command', return_value='invalid'):
                with self.assertRaises(json.JSONDecodeError):
                    deploy.aws('elbv2', 'register-targets')

    def test_main_advance_remains_cause_when_lock_cleanup_fails(self):
        calls = []
        def aws_call(service, operation, *args, **kwargs):
            calls.append(operation)
            if operation == 'delete-object':
                raise RuntimeError('cleanup unavailable')
            return {}
        with tempfile.TemporaryDirectory() as temporary:
            configuration = dict(values('single'), RUNNER_TEMP=temporary)
            with patch.dict(deploy.os.environ, configuration, clear=True), \
                 patch.object(deploy, 'verify_main', side_effect=[None, RuntimeError('main advanced')]), \
                 patch.object(deploy, 'bundle', return_value='a'*64), \
                 patch.object(deploy, 'aws', side_effect=aws_call), \
                 patch.object(deploy.sys, 'stderr') as stderr:
                with self.assertRaisesRegex(RuntimeError, 'main advanced'):
                    deploy.main()
                self.assertIn('lock cleanup failed', ''.join(call.args[0] for call in stderr.write.call_args_list))
        self.assertNotIn('send-command', calls)

    def test_host_injection_preserves_last_value_without_newline(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'env'
            original = 'SPRING_DATASOURCE_PASSWORD=a$quoted"'
            path.write_text(original)
            path.chmod(0o600)
            env.inject(path, 'c', 'false')
            self.assertTrue(path.read_text().startswith(original + '\nGETDDO_METRICS_SERVER=c\n'))

    def test_duplicate_instance_ids_and_gate_rejected(self):
        for changes in ({'EC2_INSTANCE_ID_C': A}, {'ROLLING_DEPLOY_ENABLED': ''},
                        {'TARGET_GROUP_ARN': values()['TARGET_GROUP_ARN'].replace('123456789012', '999999999999')}):
            with self.assertRaises(ValueError):
                deploy.settings(dict(values(), **changes))

    def test_single_default_does_not_require_two_host_config(self):
        data = values('single')
        data.pop('DEPLOY_MODE')
        self.assertEqual('single', deploy.settings(data)['DEPLOY_MODE'])

    def test_single_mode_uses_shared_lock_and_preserves_it_on_unconfirmed_result(self):
        for fail in (False, True):
            calls = []
            def aws_call(service, operation, *args, **kwargs):
                calls.append((service, operation, args))
                return {'Command': {'CommandId': 'single-command'}} if operation == 'send-command' else {}
            with tempfile.TemporaryDirectory() as temporary:
                configuration = values('single')
                configuration['RUNNER_TEMP'] = temporary
                with patch.dict(deploy.os.environ, configuration, clear=True), \
                     patch.object(deploy, 'verify_main'), patch.object(deploy, 'bundle', return_value='a'*64), \
                     patch.object(deploy, 'aws', side_effect=aws_call), \
                     patch.object(deploy, 'wait_for_command', side_effect=RuntimeError('unknown') if fail else None):
                    if fail:
                        with self.assertRaises(RuntimeError):
                            deploy.main()
                    else:
                        deploy.main()
            locks = [i for i, call in enumerate(calls) if 'locks/container-deploy.json' in call[2] and call[1] == 'put-object']
            sent = [i for i, call in enumerate(calls) if call[1] == 'send-command']
            self.assertLess(locks[0], sent[0])
            self.assertEqual(0 if fail else 1, len([call for call in calls if call[1] == 'delete-object']))

    def test_single_main_advance_after_upload_unlocks_without_sending(self):
        calls = []
        def aws_call(service, operation, *args, **kwargs):
            calls.append(operation)
            return {}
        with tempfile.TemporaryDirectory() as temporary:
            configuration = values('single')
            configuration['RUNNER_TEMP'] = temporary
            with patch.dict(deploy.os.environ, configuration, clear=True), \
                 patch.object(deploy, 'verify_main', side_effect=[None, RuntimeError('main advanced')]), \
                 patch.object(deploy, 'bundle', return_value='a'*64), \
                 patch.object(deploy, 'aws', side_effect=aws_call):
                with self.assertRaisesRegex(RuntimeError, 'main advanced'):
                    deploy.main()
        self.assertNotIn('send-command', calls)
        self.assertEqual('delete-object', calls[-1])

    def test_host_injection_preserves_required_and_raw_values(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'env'
            original = 'SPRING_DATASOURCE_PASSWORD=a$quoted"\nAWS_REGION=ap-northeast-2\n'
            path.write_text(original)
            path.chmod(0o600)
            env.inject(path, 'c', 'false')
            self.assertTrue(path.read_text().startswith(original))
            self.assertIn('GETDDO_METRICS_SERVER=c\n', path.read_text())
            self.assertIn('GETDDO_METRICS_ENABLED=false\n', path.read_text())
            with self.assertRaises(ValueError):
                env.inject(path, 'a', 'true')

    def test_injection_rejects_invalid_server_enabled_and_links(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'env'
            path.write_text('AWS_REGION=ap-northeast-2\n')
            path.chmod(0o600)
            for server, enabled in (('b', 'true'), ('a\nOTHER=x', 'true'), ('a', 'yes')):
                with self.assertRaises(ValueError):
                    env.inject(path, server, enabled)
            link = Path(directory) / 'link'
            link.symlink_to(path)
            with self.assertRaises(ValueError):
                env.inject(link, 'a', 'true')


if __name__ == '__main__':
    unittest.main()
