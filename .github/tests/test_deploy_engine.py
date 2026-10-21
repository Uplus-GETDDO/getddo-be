"""실제 Bash 엔진을 임시 경로·Docker 대역으로 실행해 복구 확인 신호를 검증한다."""
import hashlib
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


class DeploymentEngineTest(unittest.TestCase):
    def execute(self, previous=True, fail_new=False, fail_stop=False, fail_cleanup=False):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            host = base / 'opt/getddo'
            release = host / 'releases/new'
            release.mkdir(parents=True)
            config = base / 'etc/getddo'
            config.mkdir(parents=True)
            engine = (ROOT / 'infra/scripts/deploy-container.sh').read_text().replace('/etc/getddo', str(config))
            (release / 'deploy-container.sh').write_text(engine)
            shutil.copy(ROOT / 'infra/scripts/inject-host-env.py', release)
            (release / 'fetch-app-env.py').write_text('import sys, pathlib, os\np = pathlib.Path(sys.argv[sys.argv.index("--output")+1])\np.write_text("AWS_REGION=ap-northeast-2\\n")\np.chmod(0o600)\n')
            (release / 'compose.deploy.yaml').write_text('services: {}\n')
            (release / 'image-id.txt').write_text(NEW)
            (release / 'image.tar.gz').write_bytes(b'validated-test-artifact')
            checksum = hashlib.sha256(b'validated-test-artifact').hexdigest()
            (release / 'image.tar.gz.sha256').write_text(checksum + '  image.tar.gz\n')
            if previous:
                old = host / 'releases/old'
                old.mkdir()
                (old / 'image-id.txt').write_text(OLD)
                (old / 'compose.deploy.yaml').write_text('services: {}\n')
                (host / 'current').symlink_to(old)
                (config / 'app.env').write_text('PREVIOUS=raw$"value\nGETDDO_METRICS_SERVER=a\n')
                (config / 'app.env').chmod(0o600)
            mock_bin = base / 'bin'
            mock_bin.mkdir()
            docker = mock_bin / 'docker'
            docker.write_text('''#!/usr/bin/env python3
import os, pathlib, sys
args=sys.argv[1:]
if args[0]=='compose':
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
if args[0]=='inspect':
    print('true '+pathlib.Path(os.environ['STATE_FILE']).read_text())
    sys.exit(0)
if args[:2]==['image','inspect']:
    if '--format' in args:
        print(os.environ['SOURCE_SHA'] if 'revision' in args[args.index('--format')+1] else os.environ['NEW_IMAGE'])
    sys.exit(0)
if args[:2]==['image','load']: sys.exit(0)
raise SystemExit(2)
''')
            docker.chmod(0o700)
            curl = mock_bin / 'curl'
            curl.write_text('#!/bin/sh\nprintf 200\n')
            curl.chmod(0o700)
            sync = mock_bin / 'sync'
            sync.write_text('#!/bin/sh\n' + ('exit 1\n' if fail_cleanup else 'exit 0\n'))
            sync.chmod(0o700)
            # cleanup-only failure must occur after transaction syncs, so fail on third sync call.
            if fail_cleanup:
                sync.write_text('#!/usr/bin/env python3\nimport os,pathlib,sys\np=pathlib.Path(os.environ["SYNC_COUNTER"])\nn=int(p.read_text())+1 if p.exists() else 1\np.write_text(str(n))\nsys.exit(1 if n>=3 else 0)\n')
            environment = dict(os.environ, PATH=str(mock_bin) + ':' + os.environ['PATH'],
                               FAIL_NEW=str(int(fail_new)), FAIL_STOP=str(int(fail_stop)),
                               STATE_FILE=str(base/'state'), SYNC_COUNTER=str(base/'sync-count'),
                               NEW_IMAGE=NEW, SOURCE_SHA=SHA, GETDDO_DEPLOY_SERVER='c',
                               GETDDO_DEPLOY_METRICS_ENABLED='true')
            result = subprocess.run(['bash', str(release/'deploy-container.sh'), SHA, 'ap-northeast-2'],
                                    env=environment, capture_output=True, text=True, timeout=20)
            return (result, (config/'app.env').read_text() if (config/'app.env').exists() else None,
                    (host/'deploy-in-progress').exists(), (host/'current').resolve())

    def test_success_injects_host_settings_and_clears_transaction(self):
        result, env, marker, current = self.execute()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('GETDDO_METRICS_SERVER=c\n', env)
        self.assertIn('GETDDO_METRICS_ENABLED=true\n', env)
        self.assertFalse(marker)
        self.assertEqual('new', current.name)

    def test_failed_update_restores_full_env_and_emits_confirmed_health_only_after_cleanup(self):
        result, env, marker, current = self.execute(fail_new=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('PREVIOUS=raw$"value\nGETDDO_METRICS_SERVER=a\n', env)
        self.assertIn('ROLLBACK_CONFIRMED_HEALTHY: ' + SHA + ' ' + OLD, result.stdout)
        self.assertFalse(marker)
        self.assertEqual('old', current.name)

    def test_cleanup_failure_never_emits_confirmation_and_preserves_marker(self):
        result, env, marker, current = self.execute(fail_new=True, fail_cleanup=True)
        self.assertNotEqual(0, result.returncode)
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


if __name__ == '__main__':
    unittest.main()
