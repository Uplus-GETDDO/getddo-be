"""실제 Compose config로 private bind와 앱 env 경계를 검사한다."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class MonitoringComposeTest(unittest.TestCase):
    def test_private_management_binding_and_missing_ip(self):
        """두 배포 환경의 포트·로그·DB 연결 상한과 필수 주소 누락을 검증한다."""
        compose = os.environ.get('GETDDO_TEST_COMPOSE')
        command = [compose] if compose else ['docker', 'compose']
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / 'app.env'
            env_file.write_text('AWS_REGION=ap-northeast-2\nSPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=10\n')
            env = dict(os.environ, APP_IMAGE='getddo:test', APP_ENV_FILE=str(env_file), API_PRIVATE_IP='10.0.1.20')
            args = command + ['--env-file', '/dev/null', '--file', str(ROOT / 'compose.deploy.yaml'),
                              'config', '--format', 'json']
            result = subprocess.run(args, env=env, capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            app = json.loads(result.stdout)['services']['app']
            ports = app['ports']
            self.assertTrue(any(p['target'] == 8080 and str(p['published']) == '8080' for p in ports))
            management = [p for p in ports if p['target'] == 9091]
            self.assertEqual(1, len(management))
            self.assertEqual('10.0.1.20', management[0]['host_ip'])
            self.assertEqual('9091', str(management[0]['published']))
            self.assertEqual('monitoring', app['environment']['SPRING_PROFILES_INCLUDE'])
            self.assertEqual('30', app['environment']['SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE'])
            self.assertNotIn('API_PRIVATE_IP', app['environment'])
            self.assertEqual('/getddo/dev/api', app['logging']['options']['awslogs-group'])
            stage_env = dict(env, GETDDO_API_LOG_GROUP='/getddo/staging/api')
            stage = subprocess.run(args, env=stage_env, capture_output=True, text=True, timeout=30)
            self.assertEqual(0, stage.returncode, stage.stderr)
            stage_app = json.loads(stage.stdout)['services']['app']
            self.assertEqual('/getddo/staging/api', stage_app['logging']['options']['awslogs-group'])
            self.assertEqual('30', stage_app['environment']['SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE'])
            self.assertEqual(app['ports'], stage_app['ports'])
            self.assertEqual(app['volumes'], stage_app['volumes'])
            env.pop('API_PRIVATE_IP')
            missing = subprocess.run(args, env=env, capture_output=True, text=True, timeout=30)
            self.assertNotEqual(0, missing.returncode)
            self.assertIn('API_PRIVATE_IP', missing.stderr)


if __name__ == '__main__':
    unittest.main()
