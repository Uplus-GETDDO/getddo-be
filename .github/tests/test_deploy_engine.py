"""실제 Bash 엔진을 임시 경로·Docker 대역으로 실행해 복구 확인 신호를 검증한다."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SHA = 'a' * 40
NEW = 'sha256:' + 'b' * 64
OLD = 'sha256:' + 'd' * 64
REPOSITORY = '942466928325.dkr.ecr.ap-northeast-2.amazonaws.com/getddo-be'
NEW_REF = REPOSITORY + '@sha256:' + 'c' * 64
OLD_REF = REPOSITORY + '@sha256:' + 'e' * 64


class DeploymentEngineTest(unittest.TestCase):
    def execute(self, previous=True, fail_new=False, fail_stop=False, fail_cleanup=False, metadata="10.0.1.20", fail_metadata=False, fail_metrics=False, legacy=False, previous_monitoring=False, metrics_response="samples", fail_metadata_ip=False, deploy_environment="dev", fail_restore=False):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            host = base / 'opt/getddo'
            release = host / 'releases/new'
            release.mkdir(parents=True)
            config = base / 'etc/getddo'
            config.mkdir(parents=True)
            engine = (ROOT / 'infra/scripts/deploy-container.sh').read_text().replace('/etc/getddo', str(config))
            (release / 'deploy-container.sh').write_text(engine)
            for filename in ('inject-host-env.py', 'prepare-image.py', 'image-metadata.py', 'prepare-staging-image.sh'):
                shutil.copy(ROOT / 'infra/scripts' / filename, release)
            def identity(directory, image_id, digest):
                document = dict(schema_version=1, source_sha=SHA, build_run_id='12', build_run_attempt='1',
                                repository_uri=REPOSITORY, image_tag=SHA + '-12-1', image_digest=digest,
                                image_id=image_id, platform='linux/amd64')
                (directory / 'image-metadata.json').write_text(json.dumps(document))
                (directory / 'image-ref.txt').write_text(REPOSITORY + '@' + digest)
                (directory / 'source-sha.txt').write_text(SHA)
            identity(release, NEW, 'sha256:' + 'c' * 64)
            (release / 'fetch-app-env.py').write_text('import sys, pathlib, os\nargs = sys.argv[1:]\nexpected = os.environ.get("GETDDO_DEPLOY_ENVIRONMENT", "dev")\npassed = args[args.index("--environment")+1] if "--environment" in args else "dev"\nif passed != expected:\n    raise SystemExit(3)\np = pathlib.Path(args[args.index("--output")+1])\np.write_text("AWS_REGION=ap-northeast-2\\n")\np.chmod(0o600)\n')
            (release / 'compose.deploy.yaml').write_text('services: {}\n' if legacy else (ROOT / 'compose.deploy.yaml').read_text())
            (release / 'image-id.txt').write_text(NEW)
            (release / 'image.tar.gz').write_bytes(b'validated-test-artifact')
            checksum = hashlib.sha256(b'validated-test-artifact').hexdigest()
            (release / 'image.tar.gz.sha256').write_text(checksum + '  image.tar.gz\n')
            if previous:
                old = host / 'releases/old'
                old.mkdir()
                (old / 'image-id.txt').write_text(OLD)
                identity(old, OLD, 'sha256:' + 'e' * 64)
                (old / 'compose.deploy.yaml').write_text((ROOT / 'compose.deploy.yaml').read_text() if previous_monitoring else 'services: {}\n')
                (host / 'current').symlink_to(old)
                (config / 'app.env').write_text('PREVIOUS=raw$"value\nGETDDO_METRICS_SERVER=a\n')
                (config / 'app.env').chmod(0o600)
            mock_bin = base / 'bin'
            mock_bin.mkdir()
            docker = mock_bin / 'docker'
            docker.write_text('''#!/usr/bin/env python3
import os, pathlib, sys, json
args=sys.argv[1:]
if args[0]=='compose':
    assert os.environ.get('GETDDO_API_LOG_GROUP') == '/getddo/' + os.environ['GETDDO_DEPLOY_ENVIRONMENT'] + '/api'
    if not os.environ.get('API_PRIVATE_IP') and 'API_PRIVATE_IP' in pathlib.Path(args[args.index('--file')+1]).read_text(): sys.exit(2)
    if 'config' in args: sys.exit(0)
    if 'ps' in args:
        if '--all' not in args: print('container')
        sys.exit(0)
    if 'up' in args:
        image=os.environ['APP_IMAGE']
        if os.environ['FAIL_NEW']=='1' and image==os.environ['NEW_IMAGE']: sys.exit(1)
        pathlib.Path(os.environ['STATE_FILE']).write_text(image)
        sys.exit(0)
    if 'stop' in args: sys.exit(int(os.environ['FAIL_STOP']))
if args[0]=='ps':
    if pathlib.Path(os.environ['HAS_PREVIOUS']).exists(): print('f'*64)
    sys.exit(0)
if args[0]=='inspect':
    if 'revision' in args[args.index('--format')+1]:
        print('true '+os.environ['OLD_ID']+' '+os.environ['SOURCE_SHA'])
        sys.exit(0)
    image=pathlib.Path(os.environ['STATE_FILE']).read_text()
    image_id=os.environ['OLD_ID'] if image in (os.environ['OLD_REF'], os.environ['OLD_ID']) else os.environ['NEW_ID']
    print('true '+image+' '+image_id)
    sys.exit(0)
if args[:2]==['image','inspect']:
    if '--format' not in args:
        ref=args[-1]
        image_id=os.environ['OLD_ID'] if ref==os.environ['OLD_REF'] else os.environ['NEW_ID']
        print(json.dumps([dict(Id=image_id, RepoDigests=[ref], Os='linux', Architecture='amd64', Config=dict(Labels={'org.opencontainers.image.revision':os.environ['SOURCE_SHA']}))]))
    if '--format' in args:
        print(os.environ['SOURCE_SHA'] if 'revision' in args[args.index('--format')+1] else os.environ['NEW_ID'])
    sys.exit(0)
if args[:2]==['image','load']: sys.exit(0)
raise SystemExit(2)
''')
            docker.chmod(0o700)
            curl = mock_bin / 'curl'
            curl.write_text('''#!/usr/bin/env python3
import os, sys
url=sys.argv[-1]
if '169.254.169.254' in url:
    assert '--noproxy' in sys.argv and '--fail' in sys.argv and '--max-time' in sys.argv
    if os.environ['FAIL_METADATA']=='1': sys.exit(1)
    if url.endswith('/token'):
        assert '--request' in sys.argv and 'PUT' in sys.argv
        print('test-metadata-token')
    else:
        assert 'test-metadata-token' not in ' '.join(sys.argv)
        assert sys.argv[sys.argv.index('--header')+1] == '@-'
        assert sys.stdin.read() == 'X-aws-ec2-metadata-token: test-metadata-token\\n'
        if os.environ['FAIL_METADATA_IP']=='1': sys.exit(22)
        print(os.environ['METADATA_IP'])
elif url.endswith('/actuator/prometheus'):
    assert url.startswith('http://' + os.environ['METADATA_IP'] + ':9091/')
    if os.environ['FAIL_METRICS']=='1': sys.exit(22)
    names = ['http_server_requests_seconds_bucket', 'jvm_memory_used_bytes', 'tomcat_threads_busy_threads', 'hikaricp_connections_max']
    mode = os.environ['METRICS_RESPONSE']
    if mode == 'names':
        print(' '.join(names))
    elif mode == 'comments':
        print('\\n'.join('# HELP ' + name + ' test' for name in names))
    else:
        if mode == 'missing': names.pop()
        for name in names:
            print(name + '{test="sample"} 1.0')
else:
    print('200')
''')
            curl.chmod(0o700)
            (mock_bin / 'sleep').write_text('#!/bin/sh\nexit 0\n')
            (mock_bin / 'sleep').chmod(0o700)
            install = mock_bin / 'install'
            install.write_text('#!/usr/bin/env python3\nimport os, sys\nif os.environ["FAIL_RESTORE"] == "1" and any("restore.env" in a for a in sys.argv): raise SystemExit(1)\nos.execv("/usr/bin/install", ["install"]+sys.argv[1:])\n')
            install.chmod(0o700)
            sync = mock_bin / 'sync'
            sync.write_text('#!/bin/sh\nexit 0\n')
            sync.chmod(0o700)
            # clear_marker syncs the config directory only after rollback restored the app.
            if fail_cleanup:
                sync.write_text('#!/usr/bin/env python3\nimport os,sys\nsys.exit(1 if sys.argv[-1] == os.environ["CONFIG_DIR"] else 0)\n')
            environment = dict(os.environ, PATH=str(mock_bin) + ':' + os.environ['PATH'],
                               METRICS_RESPONSE=metrics_response, API_PRIVATE_IP='0.0.0.0', METADATA_IP=metadata, FAIL_METADATA=str(int(fail_metadata)), FAIL_METADATA_IP=str(int(fail_metadata_ip)),
                               FAIL_RESTORE=str(int(fail_restore)), FAIL_METRICS=str(int(fail_metrics)), FAIL_NEW=str(int(fail_new)), FAIL_STOP=str(int(fail_stop)),
                               STATE_FILE=str(base/'state'), HAS_PREVIOUS=str(host/'current'), CONFIG_DIR=str(config),
                               NEW_IMAGE=NEW if deploy_environment == 'staging' else NEW_REF, NEW_ID=NEW, OLD_ID=OLD, OLD_REF=OLD_REF, SOURCE_SHA=SHA, GETDDO_DEPLOY_SERVER='c',
                               GETDDO_DEPLOY_METRICS_ENABLED='true', GETDDO_DEPLOY_ENVIRONMENT=deploy_environment)
            result = subprocess.run(['bash', str(release/'deploy-container.sh'), SHA, 'ap-northeast-2'],
                                    env=environment, capture_output=True, text=True, timeout=20)
            result.deployment_started = (base / 'state').exists()
            return (result, (config/'app.env').read_text() if (config/'app.env').exists() else None,
                    (host/'deploy-in-progress').exists(), (host/'current').resolve())

    def test_success_injects_host_settings_and_clears_transaction(self):
        result, env, marker, current = self.execute()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('GETDDO_METRICS_SERVER=c\n', env)
        self.assertIn('GETDDO_METRICS_ENABLED=true\n', env)
        self.assertFalse(marker)
        self.assertNotIn('API_PRIVATE_IP', env)
        self.assertNotIn('SPRING_PROFILES_INCLUDE', env)
        self.assertEqual('new', current.name)

    def test_stage_success_injects_stage_labels_and_failed_update_restores_prior_env(self):
        result, env, marker, current = self.execute(deploy_environment='staging')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('GETDDO_METRICS_ENVIRONMENT=staging\n', env)
        self.assertIn('GETDDO_METRICS_SERVER=c\n', env)
        self.assertFalse(marker)
        result, env, marker, current = self.execute(deploy_environment='staging', fail_new=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('PREVIOUS=raw$"value\nGETDDO_METRICS_SERVER=a\n', env)
        self.assertIn('ROLLBACK_CONFIRMED_HEALTHY:', result.stdout)
        self.assertEqual('old', current.name)
        self.assertFalse(marker)

    def test_invalid_environment_rejected_before_touching_app(self):
        result, env, marker, current = self.execute(deploy_environment='prod')
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(result.deployment_started)
        self.assertEqual('old', current.name)

    def test_failed_update_restores_full_env_and_emits_confirmed_health_only_after_cleanup(self):
        result, env, marker, current = self.execute(fail_new=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('PREVIOUS=raw$"value\nGETDDO_METRICS_SERVER=a\n', env)
        self.assertIn('ROLLBACK_CONFIRMED_HEALTHY: ' + SHA + ' ' + OLD, result.stdout)
        self.assertFalse(marker)
        self.assertEqual('old', current.name)

    def test_env_restore_failure_preserves_marker_and_never_confirms_rollback(self):
        result, env, marker, current = self.execute(fail_new=True, fail_restore=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('ROLLBACK_FAILED:', result.stderr)
        self.assertNotIn('ROLLBACK_CONFIRMED_HEALTHY:', result.stdout)
        self.assertTrue(marker)
        self.assertEqual('old', current.name)

    def test_cleanup_failure_never_emits_confirmation_and_preserves_marker(self):
        result, env, marker, current = self.execute(fail_new=True, fail_cleanup=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('ROLLBACK_OK:', result.stdout)
        self.assertNotIn('ROLLBACK_CONFIRMED_HEALTHY:', result.stdout)
        self.assertTrue(marker)
        self.assertIn('DEPLOY_INCOMPLETE:', result.stderr)

    def test_first_deploy_failure_stops_app_and_restores_absent_env(self):
        result, env, marker, current = self.execute(previous=False, fail_new=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIsNone(env)
        self.assertFalse(marker)
        self.assertNotIn('ROLLBACK_CONFIRMED_HEALTHY:', result.stdout)

    def test_first_deploy_stop_failure_preserves_recovery_record(self):
        result, env, marker, current = self.execute(previous=False, fail_new=True, fail_stop=True)
        self.assertNotEqual(0, result.returncode)
        self.assertTrue(marker)
        self.assertNotIn('ROLLBACK_CONFIRMED_HEALTHY:', result.stdout)
        self.assertIn('DEPLOY_INCOMPLETE:', result.stderr)

    def test_metadata_failure_keeps_previous_env_and_app_before_transaction(self):
        for options in ({"fail_metadata": True}, {"fail_metadata_ip": True}, {"metadata": "0.0.0.0"}, {"metadata": "127.0.0.1"},
                        {"metadata": "169.254.169.254"}, {"metadata": "8.8.8.8"}, {"metadata": "10.0.0.1:9091"},
                        {"metadata": "::1"}, {"metadata": "10.0.0.1\n10.0.0.2"}):
            with self.subTest(options=options):
                result, env, marker, current = self.execute(**options)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual('PREVIOUS=raw$"value\nGETDDO_METRICS_SERVER=a\n', env)
                self.assertEqual('old', current.name)
                self.assertFalse(result.deployment_started)
                self.assertFalse(marker)
                self.assertNotIn('ROLLBACK_OK', result.stdout)

    def test_metrics_failure_rolls_back_to_old_compose_without_metrics_endpoint(self):
        result, env, marker, current = self.execute(fail_metrics=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('ROLLBACK_CONFIRMED_HEALTHY:', result.stdout)
        self.assertEqual('old', current.name)
        self.assertFalse(marker)
        self.assertEqual('PREVIOUS=raw$"value\nGETDDO_METRICS_SERVER=a\n', env)

    def test_metric_names_comments_and_missing_samples_never_commit_new_release(self):
        for response in ('names', 'comments', 'missing'):
            with self.subTest(response=response):
                result, env, marker, current = self.execute(metrics_response=response)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('ROLLBACK_CONFIRMED_HEALTHY:', result.stdout)
                self.assertEqual('old', current.name)
                self.assertFalse(marker)
                self.assertEqual('PREVIOUS=raw$"value\nGETDDO_METRICS_SERVER=a\n', env)

    def test_legacy_release_still_prepares_ip_for_monitoring_previous_compose(self):
        result, env, marker, current = self.execute(legacy=True, previous_monitoring=True, fail_metadata=True)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(result.deployment_started)
        self.assertEqual('old', current.name)
        self.assertFalse(marker)
        self.assertEqual('PREVIOUS=raw$"value\nGETDDO_METRICS_SERVER=a\n', env)

    def test_legacy_baseline_does_not_require_metadata_or_prometheus(self):
        result, env, marker, current = self.execute(legacy=True, fail_metadata=True, fail_metrics=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('new', current.name)
        self.assertFalse(marker)


if __name__ == '__main__':
    unittest.main()
