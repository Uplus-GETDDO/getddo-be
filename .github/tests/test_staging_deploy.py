"""Stage 배포의 대상 경계·고정 산출물·실패 잠금과 dev 기본값 회귀 검증."""
import copy
import hashlib
import importlib.util
import io
import contextlib
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'infra/scripts' / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


stage = load('staging', 'deploy-staging.py')
fetch = load('fetch', 'fetch-app-env.py')
host_env = load('host_env', 'inject-host-env.py')
SHA = 'a' * 40
REGION = 'ap-northeast-2'
ACCOUNT = '123456789012'
A, C = 'i-' + 'a' * 17, 'i-' + 'c' * 17
TG = f'arn:aws:elasticloadbalancing:{REGION}:{ACCOUNT}:targetgroup/getddo-staging/123abc'
ALB = f'arn:aws:elasticloadbalancing:{REGION}:{ACCOUNT}:loadbalancer/app/getddo-staging/123abc'


def configuration():
    return {'account_id': ACCOUNT, 'region': REGION, 'vpc_id': 'vpc-' + 'b' * 17,
            'run_id': 'test-123', 'instances': {'a': A, 'c': C},
            'expected_names': {'a': 'getddo-staging-api-a', 'c': 'getddo-staging-api-c'},
            'target_group_arn': TG, 'deploy_bucket': f'getddo-deploy-{ACCOUNT}-{REGION}'}


class FakeAWS:
    def __init__(self):
        config = configuration()
        self.calls = []
        self.registered = set()
        self.results = {
            'get-caller-identity': {'Account': ACCOUNT},
            'describe-target-groups': {'TargetGroups': [{'TargetGroupArn': TG, 'VpcId': config['vpc_id'],
                'TargetType': 'instance', 'Port': 8080, 'Protocol': 'HTTP', 'LoadBalancerArns': [ALB]}]},
            'describe-load-balancers': {'LoadBalancers': [{'LoadBalancerArn': ALB, 'Scheme': 'internal',
                                                          'VpcId': config['vpc_id']}]},
            'describe-tags': {'TagDescriptions': [{'ResourceArn': arn, 'Tags': [{'Key': 'Environment', 'Value': 'staging'}, {'Key': 'RunId', 'Value': config['run_id']}]}
                                                  for arn in (TG, ALB)]},
            'get-bucket-location': {'LocationConstraint': REGION},
            'describe-instances': {'Reservations': [{'Instances': [
                {'InstanceId': instance, 'State': {'Name': 'running'}, 'VpcId': config['vpc_id'],
                 'Tags': [{'Key': 'Environment', 'Value': 'staging'}, {'Key': 'Name', 'Value': config['expected_names'][server]}, {'Key': 'RunId', 'Value': config['run_id']}]}
                for server, instance in config['instances'].items()]}]},
            'describe-instance-information': {'InstanceInformationList': [{'InstanceId': instance, 'PingStatus': 'Online'} for instance in (A, C)]},
            'describe-target-health': {'TargetHealthDescriptions': []},
        }

    def __call__(self, service, operation, *args, **kwargs):
        self.calls.append((service, operation, args))
        if operation == 'get-bucket-location':
            if args[args.index('--bucket') + 1] != configuration()['deploy_bucket']:
                raise AssertionError('Unexpected deployment bucket')
        if operation == 'deregister-targets':
            self.registered.discard(args[args.index('--targets') + 1].split(',')[0][3:])
        if operation == 'register-targets':
            self.registered.add(args[args.index('--targets') + 1].split(',')[0][3:])
        if operation == 'describe-target-health' and '--targets' not in args and self.registered:
            return {'TargetHealthDescriptions': [{'Target': {'Id': target, 'Port': 8080}, 'TargetHealth': {'State': 'healthy'}} for target in sorted(self.registered)]}
        if operation == 'describe-target-health' and '--targets' in args:
            target = args[args.index('--targets') + 1].split(',')[0][3:]
            return {'TargetHealthDescriptions': [{'Target': {'Id': target, 'Port': 8080}, 'TargetHealth': {'State': 'healthy' if target in self.registered else 'unused'}}]}
        return copy.deepcopy(self.results.get(operation, {}))


class StageBoundaryTest(unittest.TestCase):
    def test_manifest_rejects_other_bucket_state_bucket_invalid_ids_names_and_arns(self):
        self.assertEqual(configuration(), stage.manifest(configuration()))
        bad = [{'deploy_bucket': 'getddo-prod'}, {'deploy_bucket': 'getddo-tfstate-staging'},
               {'account_id': '999999999999'}, {'region': 'us-east-1'}, {'run_id': '../prod'},
               {'instances': {'a': A, 'c': A}}, {'instances': {'a': '*', 'c': C}},
               {'expected_names': {'a': 'getddo-prod-api-a', 'c': 'getddo-staging-api-c'}},
               {'target_group_arn': TG.replace(ACCOUNT, '999999999999')}]
        for changes in bad:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                stage.manifest(dict(configuration(), **changes))

    def test_exact_stage_targets_pass_without_writes(self):
        aws = FakeAWS()
        stage.validate_targets(configuration(), aws)
        self.assertFalse(any(op == 'head-bucket' for _, op, _ in aws.calls))
        location = next(args for _, op, args in aws.calls if op == 'get-bucket-location')
        self.assertEqual(location[location.index('--bucket')+1], configuration()['deploy_bucket'])
        self.assertEqual(location[location.index('--expected-bucket-owner')+1], ACCOUNT)
        self.assertFalse(any(op in ('put-object', 'send-command', 'register-targets') for _, op, _ in aws.calls))

    def test_prod_or_wrong_account_vpc_name_alb_targets_and_offline_hosts_rejected(self):
        for scenario in ('account', 'vpc', 'environment', 'name', 'extra-host', 'offline', 'prod-tg', 'public-alb', 'bucket-region', 'prod-target'):
            with self.subTest(scenario=scenario):
                aws = FakeAWS()
                instance = aws.results['describe-instances']['Reservations'][0]['Instances'][0]
                if scenario == 'account': aws.results['get-caller-identity']['Account'] = '999999999999'
                if scenario == 'vpc': instance['VpcId'] = 'vpc-' + 'd' * 17
                if scenario == 'environment': instance['Tags'][0]['Value'] = 'prod'
                if scenario == 'name': instance['Tags'][1]['Value'] = 'getddo-api-a'
                if scenario == 'extra-host': aws.results['describe-instances']['Reservations'][0]['Instances'].append(copy.deepcopy(instance))
                if scenario == 'offline': aws.results['describe-instance-information']['InstanceInformationList'][0]['PingStatus'] = 'ConnectionLost'
                if scenario == 'prod-tg': aws.results['describe-tags']['TagDescriptions'][0]['Tags'][0]['Value'] = 'prod'
                if scenario == 'public-alb': aws.results['describe-load-balancers']['LoadBalancers'][0]['Scheme'] = 'internet-facing'
                if scenario == 'bucket-region': aws.results['get-bucket-location']['LocationConstraint'] = 'us-east-1'
                if scenario == 'prod-target': aws.results['describe-target-health']['TargetHealthDescriptions'] = [{'Target': {'Id': 'i-' + 'd' * 17, 'Port': 8080}}]
                with self.assertRaises(ValueError): stage.validate_targets(configuration(), aws)
                self.assertFalse(any(op in ('put-object', 'send-command', 'register-targets') for _, op, _ in aws.calls))

    def test_payload_only_stage_environment_and_immutable_stage_key(self):
        key = 'staging/test-123/releases/' + SHA + '/release.tar'
        payload = stage.parameters(configuration(), 'c', SHA, key, 'b' * 64)
        script = payload['commands'][0]
        self.assertIn('GETDDO_DEPLOY_ENVIRONMENT=staging', script)
        self.assertIn('GETDDO_DEPLOY_SERVER=c', script)
        self.assertIn(key, script)
        self.assertNotIn('/getddo/dev/', script)
        self.assertNotIn('source ', script)
        self.assertNotIn('eval ', script)

    def test_run_ownership_rejects_reusing_current_hosts_with_another_run(self):
        data = configuration(); data['run_id'] = 'another-run'
        aws = FakeAWS()
        with self.assertRaises(ValueError): stage.validate_targets(data, aws)
        self.assertFalse(any(op in ('put-object', 'send-command', 'deregister-targets') for _, op, _ in aws.calls))



class ArtifactTest(unittest.TestCase):
    def artifact(self, directory, revision=SHA):
        artifact = Path(directory)
        config = json.dumps({'config': {'Labels': {'org.opencontainers.image.revision': revision}}}).encode()
        image_id = 'sha256:' + hashlib.sha256(config).hexdigest()
        with tarfile.open(artifact / 'image.tar.gz', 'w:gz') as archive:
            for name, content in [('config.json', config), ('manifest.json', json.dumps([
                    {'Config': 'config.json', 'RepoTags': ['getddo-be:' + SHA], 'Layers': []}]).encode())]:
                item = tarfile.TarInfo(name)
                item.size = len(content)
                archive.addfile(item, io.BytesIO(content))
        checksum = hashlib.sha256((artifact / 'image.tar.gz').read_bytes()).hexdigest()
        (artifact / 'image-id.txt').write_text(image_id + '\n')
        (artifact / 'image.tar.gz.sha256').write_text(checksum + '  image.tar.gz\n')
        return artifact, image_id, checksum

    def test_sha_digest_revision_and_dirty_checkout_are_independently_checked(self):
        for failure in (None, 'checkout', 'dirty', 'image-id', 'checksum', 'bytes', 'revision', 'config-digest'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as directory:
                artifact, image_id, checksum = self.artifact(directory, 'c' * 40 if failure == 'revision' else SHA)
                if failure == 'image-id': (artifact / 'image-id.txt').write_text('sha256:' + 'f' * 64)
                if failure == 'checksum': (artifact / 'image.tar.gz.sha256').write_text('b' * 64 + '  image.tar.gz\n')
                if failure == 'bytes': (artifact / 'image.tar.gz').write_bytes(b'changed')
                if failure == 'config-digest':
                    image_id = 'sha256:' + 'f' * 64
                    (artifact / 'image-id.txt').write_text(image_id)
                with patch.object(stage.deploy, 'command', side_effect=[('b' * 40 if failure == 'checkout' else SHA) + '\n', 'M Dockerfile' if failure == 'dirty' else '']):
                    if failure:
                        with self.assertRaises(ValueError): stage.validate_artifact(ROOT, artifact, SHA, image_id, checksum)
                    else:
                        stage.validate_artifact(ROOT, artifact, SHA, image_id, checksum)


class StageRunTest(unittest.TestCase):
    def setUp(self):
        self.sleep = self.enterContext(patch.object(stage.time, 'sleep'))

    def test_check_is_read_only_and_does_not_query_main(self):
        aws = FakeAWS()
        with patch.object(stage, 'validate_artifact'), patch.object(stage.deploy, 'verify_main') as verify:
            stage.run(configuration(), ROOT, ROOT, SHA, 'sha256:' + 'b' * 64, 'c' * 64, aws, True)
            verify.assert_not_called()
        self.assertFalse(any(op in ('put-object', 'send-command') for _, op, _ in aws.calls))

    def test_success_and_failure_preserve_production_lock_and_stop_next_host(self):
        for failure in (None, 'failed-a', 'unknown-a', 'failed-c', 'upload'):
            with self.subTest(failure=failure):
                aws = FakeAWS()
                responses = [{'Status': 'Success', 'ResponseCode': 0}] * 2
                if failure == 'failed-a': responses[0] = {'Status': 'Failed', 'ResponseCode': 1}
                if failure == 'unknown-a': responses = [stage.rolling.UnknownOutcome('unknown')]
                if failure == 'failed-c': responses[1] = {'Status': 'Failed', 'ResponseCode': 1}
                def invoke(service, operation, *args, **kwargs):
                    result = aws(service, operation, *args, **kwargs)
                    if failure == 'upload' and operation == 'put-object' and '--body' in args:
                        raise RuntimeError('upload failed')
                    return result
                with patch.object(stage, 'validate_artifact'), patch.object(stage.deploy, 'bundle', return_value='b' * 64), \
                     patch.object(stage.rolling, 'invocation', side_effect=responses) as remote, \
                     patch.object(stage.deploy, 'verify_main') as main_guard:
                    if failure:
                        with self.assertRaises(RuntimeError): stage.run(configuration(), ROOT, ROOT, SHA, 'sha256:' + 'b' * 64, 'c' * 64, invoke)
                    else:
                        stage.run(configuration(), ROOT, ROOT, SHA, 'sha256:' + 'b' * 64, 'c' * 64, invoke)
                    self.assertEqual(0 if failure == 'upload' else 1 if failure in ('failed-a', 'unknown-a') else 2, remote.call_count)
                    main_guard.assert_not_called()
                mutations = [(op, args) for _, op, args in aws.calls if op in ('put-object', 'delete-object')]
                self.assertTrue(all(args[args.index('--key') + 1].startswith('staging/test-123/') or args[args.index('--key') + 1] == 'staging/locks/app.json' for _, args in mutations))
                self.assertFalse(any('locks/container-deploy.json' in args for _, args in mutations))
                self.assertEqual(1 if failure in (None, 'upload') else 0, sum(op == 'delete-object' for op, _ in mutations))

    def test_repeat_deploy_uses_unique_remote_release_and_archive_keys(self):
        keys, commands = [], []
        for _ in range(2):
            aws = FakeAWS()
            with patch.object(stage, 'validate_artifact'), patch.object(stage.deploy, 'bundle', return_value='b' * 64), \
                 patch.object(stage.rolling, 'invocation', return_value={'Status': 'Success', 'ResponseCode': 0}) as remote:
                stage.run(configuration(), ROOT, ROOT, SHA, 'sha256:' + 'b' * 64, 'c' * 64, aws)
                commands.append(remote.call_args_list[0].args[2]['commands'][0])
            keys.append(next(args[args.index('--key') + 1] for _, op, args in aws.calls if op == 'put-object' and '--body' in args))
        self.assertNotEqual(keys[0], keys[1])
        self.assertNotEqual(commands[0], commands[1])

    def test_final_two_target_health_and_unlock_failure_never_print_success(self):
        for failure in ('final-health', 'unlock'):
            with self.subTest(failure=failure):
                aws = FakeAWS()
                def invoke(service, operation, *args, **kwargs):
                    result = aws(service, operation, *args, **kwargs)
                    if failure == 'final-health' and operation == 'describe-target-health' and '--targets' not in args and len(aws.registered) == 2:
                        result['TargetHealthDescriptions'][0]['TargetHealth']['State'] = 'unhealthy'
                    if failure == 'unlock' and operation == 'delete-object':
                        raise RuntimeError('unlock failed')
                    return result
                output = io.StringIO()
                with patch.object(stage, 'validate_artifact'), patch.object(stage.deploy, 'bundle', return_value='b' * 64), \
                     patch.object(stage.rolling, 'invocation', return_value={'Status': 'Success', 'ResponseCode': 0}), \
                     contextlib.redirect_stdout(output):
                    with self.assertRaises(RuntimeError):
                        stage.run(configuration(), ROOT, ROOT, SHA, 'sha256:' + 'b' * 64, 'c' * 64, invoke)
                self.assertNotIn('STAGING_DEPLOY_OK', output.getvalue())
                if failure == 'final-health':
                    self.assertFalse(any(op == 'delete-object' for _, op, _ in aws.calls))

    def test_only_healthy_host_remains_while_unregistered_peer_is_prepared_first(self):
        aws=FakeAWS();aws.registered={A}
        with patch.object(stage, 'validate_artifact'), patch.object(stage.deploy, 'bundle', return_value='b'*64), \
             patch.object(stage.rolling, 'invocation', return_value={'Status':'Success','ResponseCode':0}) as remote:
            stage.run(configuration(),ROOT,ROOT,SHA,'sha256:'+'b'*64,'c'*64,aws)
        self.assertEqual([C,A],[call.args[1] for call in remote.call_args_list])
        removed=[args[args.index('--targets')+1] for _,op,args in aws.calls if op=='deregister-targets']
        self.assertEqual(['Id='+C+',Port=8080','Id='+A+',Port=8080'],removed)

    def test_drain_timeout_holds_lock_and_never_replaces_any_host(self):
        aws = FakeAWS()
        def stuck(service, operation, *args, **kwargs):
            result = aws(service, operation, *args, **kwargs)
            if operation == 'describe-target-health' and '--targets' in args:
                result['TargetHealthDescriptions'][0]['TargetHealth']['State'] = 'draining'
            return result
        with patch.object(stage, 'validate_artifact'), patch.object(stage.deploy, 'bundle', return_value='b'*64), \
             patch.object(stage.rolling, 'invocation') as remote, patch.object(stage.time, 'monotonic', side_effect=[0, 301]):
            with self.assertRaisesRegex(RuntimeError, 'drain timeout'):
                stage.run(configuration(), ROOT, ROOT, SHA, 'sha256:'+'b'*64, 'c'*64, stuck)
            remote.assert_not_called()
        self.assertFalse(any(op == 'delete-object' for _, op, _ in aws.calls))

    def test_recovered_peer_is_observed_for_two_minutes_before_healthy_host_drains(self):
        aws = FakeAWS()
        aws.registered = {A}
        def invoke(service, operation, *args, **kwargs):
            if operation == 'deregister-targets' and args[-1] == 'Id=' + A + ',Port=8080':
                self.assertEqual([10] * 12, [call.args[0] for call in self.sleep.call_args_list])
            return aws(service, operation, *args, **kwargs)
        with patch.object(stage, 'validate_artifact'), patch.object(stage.deploy, 'bundle', return_value='b' * 64), \
             patch.object(stage.rolling, 'invocation', return_value={'Status': 'Success', 'ResponseCode': 0}):
            stage.run(configuration(), ROOT, ROOT, SHA, 'sha256:' + 'b' * 64, 'c' * 64, invoke)
        self.assertEqual([10] * 24, [call.args[0] for call in self.sleep.call_args_list])

    def test_transient_recovered_peer_never_drains_existing_healthy_host(self):
        for healthy_observations in (1, 12):
            with self.subTest(healthy_observations=healthy_observations):
                aws = FakeAWS()
                aws.registered = {A}
                observations = 0
                def invoke(service, operation, *args, **kwargs):
                    nonlocal observations
                    result = aws(service, operation, *args, **kwargs)
                    if operation == 'describe-target-health' and '--targets' in args and args[-1] == 'Id=' + C + ',Port=8080' and C in aws.registered:
                        observations += 1
                        if observations > healthy_observations:
                            result['TargetHealthDescriptions'][0]['TargetHealth']['State'] = 'unhealthy'
                    return result
                with patch.object(stage, 'validate_artifact'), patch.object(stage.deploy, 'bundle', return_value='b' * 64), \
                     patch.object(stage.rolling, 'invocation', return_value={'Status': 'Success', 'ResponseCode': 0}) as remote:
                    with self.assertRaisesRegex(RuntimeError, 'not healthy'):
                        stage.run(configuration(), ROOT, ROOT, SHA, 'sha256:' + 'b' * 64, 'c' * 64, invoke)
                self.assertEqual([C], [call.args[1] for call in remote.call_args_list])
                self.assertIn(A, aws.registered)
                self.assertFalse(any(op == 'deregister-targets' and args[-1] == 'Id=' + A + ',Port=8080' for _, op, args in aws.calls))
                self.assertFalse(any(op == 'delete-object' for _, op, _ in aws.calls))

    def test_failed_peer_before_or_during_drain_never_stops_existing_host(self):
        for phase in ('before-drain', 'both-unhealthy', 'draining', 'drained'):
            with self.subTest(phase=phase):
                aws = FakeAWS()
                aws.registered = {A}
                peer_failed = False
                def invoke(service, operation, *args, **kwargs):
                    nonlocal peer_failed
                    result = aws(service, operation, *args, **kwargs)
                    if operation == 'describe-target-health':
                        if phase in ('before-drain', 'both-unhealthy') and '--targets' not in args and aws.registered == {A, C}:
                            peer_failed = True
                            if phase == 'both-unhealthy':
                                for row in result['TargetHealthDescriptions']:
                                    row['TargetHealth']['State'] = 'unhealthy'
                        if '--targets' in args and args[-1] == 'Id=' + A + ',Port=8080' and A not in aws.registered:
                            if phase == 'draining':
                                result['TargetHealthDescriptions'][0]['TargetHealth']['State'] = 'draining' if not peer_failed else 'unused'
                            peer_failed = phase != 'before-drain'
                        if peer_failed and '--targets' in args:
                            for row in result['TargetHealthDescriptions']:
                                if row['Target']['Id'] == C:
                                    row['TargetHealth']['State'] = 'unhealthy'
                    return result
                with patch.object(stage, 'validate_artifact'), patch.object(stage.deploy, 'bundle', return_value='b' * 64), \
                     patch.object(stage.rolling, 'invocation', return_value={'Status': 'Success', 'ResponseCode': 0}) as remote:
                    with self.assertRaisesRegex(RuntimeError, 'not healthy'):
                        stage.run(configuration(), ROOT, ROOT, SHA, 'sha256:' + 'b' * 64, 'c' * 64, invoke)
                self.assertEqual([C], [call.args[1] for call in remote.call_args_list])
                if phase in ('before-drain', 'both-unhealthy'):
                    self.assertIn(A, aws.registered)
                self.assertFalse(any(op == 'register-targets' and args[-1] == 'Id=' + A + ',Port=8080' for _, op, args in aws.calls))
                self.assertFalse(any(op == 'delete-object' for _, op, _ in aws.calls))

    def test_health_timeout_retains_lock_and_stops_next_host(self):
        aws = FakeAWS()
        def invoke(service, operation, *args, **kwargs):
            result = aws(service, operation, *args, **kwargs)
            if operation == 'describe-target-health' and '--targets' in args and A in aws.registered:
                result['TargetHealthDescriptions'][0]['TargetHealth']['State'] = 'initial'
            return result
        with patch.object(stage, 'validate_artifact'), patch.object(stage.deploy, 'bundle', return_value='b' * 64), \
             patch.object(stage.rolling, 'invocation', return_value={'Status': 'Success', 'ResponseCode': 0}) as remote, \
             patch.object(stage.time, 'monotonic', side_effect=[0, 0, 301]):
            with self.assertRaisesRegex(RuntimeError, 'health timeout'):
                stage.run(configuration(), ROOT, ROOT, SHA, 'sha256:' + 'b' * 64, 'c' * 64, invoke)
        self.assertEqual([A], [call.args[1] for call in remote.call_args_list])
        self.assertFalse(any(op == 'delete-object' for _, op, _ in aws.calls))

    def test_target_validation_failure_before_lock_or_remote_command(self):
        aws = FakeAWS()
        aws.results['get-caller-identity']['Account'] = '999999999999'
        with patch.object(stage, 'validate_artifact'), patch.object(stage.rolling, 'invocation') as remote:
            with self.assertRaises(ValueError): stage.run(configuration(), ROOT, ROOT, SHA, 'sha256:' + 'b' * 64, 'c' * 64, aws)
            remote.assert_not_called()
        self.assertFalse(any(op == 'put-object' for _, op, _ in aws.calls))


class EnvironmentTest(unittest.TestCase):
    def test_fetch_default_dev_and_explicit_staging_never_mix_paths(self):
        for environment in (None, 'staging'):
            with patch.object(fetch.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, '{"Parameters": []}')) as command:
                fetch.fetch(REGION, *([environment] if environment else []))
            names = [arg for call in command.call_args_list for arg in call.args[0] if arg.startswith('/getddo/')]
            self.assertEqual(14, len(names))
            self.assertTrue(all(name.startswith('/getddo/' + (environment or 'dev') + '/app/') for name in names))
        with self.assertRaises(fetch.ConfigurationError): fetch.fetch(REGION, '../dev')

    def test_validate_staging_rejects_dev_parameters_and_missing_secure_values(self):
        values = dict(zip(fetch.KEYS, ['jdbc:mysql://db.test/getddo?sslMode=VERIFY_IDENTITY', 'user', 'raw$"secret',
            'validate', 'true', 'https://test.example', REGION, 'images-test', 'https://test.example/images/',
            'redis.test.cache.amazonaws.com', '6379', 'a' * 32, 'true', 'redis']))
        def document(environment):
            return {'Parameters': [{'Name': '/getddo/' + environment + '/app/' + key,
                'Value': value, 'Type': 'SecureString' if key.endswith('PASSWORD') else 'String'} for key, value in values.items()]}
        self.assertEqual(values, fetch.validate(document('staging'), REGION, 'staging'))
        self.assertEqual(values, fetch.validate(document('dev'), REGION))
        with self.assertRaises(fetch.ConfigurationError): fetch.validate(document('dev'), REGION, 'staging')
        missing = document('staging'); missing['Parameters'].pop()
        with self.assertRaises(fetch.ConfigurationError): fetch.validate(missing, REGION, 'staging')
        insecure = document('staging'); insecure['Parameters'][2]['Type'] = 'String'
        with self.assertRaises(fetch.ConfigurationError): fetch.validate(insecure, REGION, 'staging')

    def test_metrics_labels_default_dev_and_staging_preserve_raw_secrets(self):
        for environment in (None, 'staging'):
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / 'app.env'
                original = 'SPRING_DATASOURCE_PASSWORD=$(touch /tmp/never-execute)$"\n'
                path.write_text(original); path.chmod(0o600)
                host_env.inject(path, 'a', 'true', *([environment] if environment else []))
                self.assertTrue(path.read_text().startswith(original))
                self.assertIn('GETDDO_METRICS_ENVIRONMENT=' + (environment or 'dev') + '\n', path.read_text())
                self.assertIn('GETDDO_METRICS_SERVER=a\n', path.read_text())


if __name__ == '__main__':
    unittest.main()
