"""ECR 출처 거부, 양쪽 사전 준비, 기준 기록 변환과 Stage 경계를 검증한다."""
import copy
from contextlib import contextmanager, redirect_stderr, redirect_stdout
import io
import importlib.util
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


metadata = load('ecr_metadata_test', 'image-metadata.py')
prepare = load('ecr_prepare_test', 'prepare-image.py')
convert = load('ecr_convert_test', 'convert-ecr-baseline.py')
deploy = load('ecr_bundle_test', 'deploy-ssm.py')
publisher = load('ecr_publish_test', 'publish-image.py')
SHA = 'a' * 40
ID = 'sha256:' + 'b' * 64
DIGEST = 'sha256:' + 'c' * 64
REF = metadata.REPOSITORY + '@' + DIGEST


def document():
    return dict(schema_version=1, source_sha=SHA, build_run_id='123', build_run_attempt='2',
                repository_uri=metadata.REPOSITORY, image_tag=SHA+'-123-2', image_digest=DIGEST,
                image_id=ID, platform='linux/amd64')


class MetadataTest(unittest.TestCase):
    def test_provenance_repository_and_platform_rejected_independently(self):
        for field, wrong in (('source_sha','f'*40), ('build_run_id','124'), ('build_run_attempt','1'),
                             ('repository_uri',metadata.REPOSITORY+'-other'), ('platform','linux/arm64'),
                             ('image_digest',ID+'\n'), ('image_id',DIGEST+'; echo bad'), ('image_tag',SHA+'-123-1'),
                             ('schema_version',True)):
            with self.subTest(field=field):
                item = dict(document(), **{field:wrong})
                with self.assertRaises(ValueError):
                    metadata.validate(item, sha=SHA, run_id='123', attempt='2')
        self.assertEqual(document(), metadata.validate(document(), sha=SHA, run_id='123', attempt='2'))
        with self.assertRaises(ValueError): metadata.validate(dict(document(), extra='unexpected'))

    def test_manifest_digest_is_not_config_id(self):
        row = {'imageId':{'imageDigest':DIGEST,'imageTag':document()['image_tag']},
               'imageManifest':json.dumps({'config':{'digest':ID}})}
        metadata.verify_ecr(document(), lambda *a:{'images':[row]})
        for change in ('digest','config','index','missing'):
            with self.subTest(change=change):
                wrong = copy.deepcopy(row)
                if change=='digest': wrong['imageId']['imageDigest']=ID
                if change=='config': wrong['imageManifest']=json.dumps({'config':{'digest':DIGEST}})
                if change=='index': wrong['imageManifest']=json.dumps({'config':{'digest':ID},'manifests':[]})
                with self.assertRaises(ValueError):
                    metadata.verify_ecr(document(), lambda *a:{'images':[] if change=='missing' else [wrong]})

    def test_baseline_exception_is_explicit_and_does_not_change_ci_tag_contract(self):
        item = dict(document(), image_tag='baseline-'+SHA+'-migration-1')
        with self.assertRaises(ValueError): metadata.validate(item)
        self.assertEqual(item, metadata.validate(item, baseline=True))


class PreparationTest(unittest.TestCase):
    def test_local_image_requires_ref_id_revision_and_platform(self):
        row = dict(Id=ID, RepoDigests=[REF], Os='linux', Architecture='amd64',
                   Config={'Labels':{'org.opencontainers.image.revision':SHA}})
        with patch.object(prepare,'command',return_value=json.dumps([row])):
            prepare.verify(document())
        for field in ('Id','RepoDigests','Architecture','Config'):
            wrong = copy.deepcopy(row)
            wrong[field] = {'Id':DIGEST,'RepoDigests':[], 'Architecture':'arm64', 'Config':{'Labels':{}}}[field]
            with self.subTest(field=field), patch.object(prepare,'command',return_value=json.dumps([wrong])):
                with self.assertRaises(ValueError): prepare.verify(document())

    def test_pull_failure_preserves_current_env_and_containers_and_cleans_auth(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            candidate=root/'releases/new'
            candidate.mkdir(parents=True)
            old=root/'releases/old'
            old.mkdir()
            (root/'current').symlink_to(old)
            for path in (candidate,old):
                (path/'image-metadata.json').write_text(json.dumps(document()))
                (path/'image-id.txt').write_text(ID)
                (path/'image-ref.txt').write_text(REF)
                (path/'source-sha.txt').write_text(SHA)
            original={p.relative_to(root):p.read_bytes() for p in root.rglob('*') if p.is_file()}
            commands=[]
            configs=[]
            def command(arguments, **kwargs):
                commands.append(arguments)
                if arguments[:2]==['docker','login']:
                    config=Path(kwargs['env']['DOCKER_CONFIG'])
                    self.assertEqual(0o700,config.stat().st_mode & 0o777)
                    configs.append(config)
                if arguments[:2]==['docker','pull']:
                    raise subprocess.TimeoutExpired(arguments,300)
                if arguments[:2]==['docker','ps']: return 'f'*64
                if arguments[:2]==['docker','inspect']: return 'true '+ID+' '+SHA
                return 'private-token'
            temporary_directory = tempfile.TemporaryDirectory
            with patch.object(prepare.tempfile,'TemporaryDirectory',side_effect=lambda **kwargs: temporary_directory(prefix='auth-',dir=directory)), \
                 patch.object(prepare.os,'geteuid',return_value=0), \
                 patch.object(prepare,'verify',side_effect=ValueError('not local')), \
                 patch.object(prepare,'command',side_effect=command):
                with self.assertRaises(subprocess.TimeoutExpired): prepare.prepare(candidate,SHA)
            self.assertEqual(old,(root/'current').resolve())
            self.assertEqual(original,{p.relative_to(root):p.read_bytes() for p in root.rglob('*') if p.is_file()})
            self.assertTrue(configs)
            self.assertTrue(all(not path.exists() for path in configs))
            self.assertFalse(any('compose' in call or 'load' in call for call in commands))

    def prepare_case(self, local_only=False, missing=None, invalid=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            candidate = root/'releases/new'
            previous = root/'releases/old'
            candidate.mkdir(parents=True)
            previous.mkdir()
            old = dict(document(), image_id='sha256:'+'d'*64, image_digest='sha256:'+'e'*64)
            (candidate/'image-metadata.json').write_text(json.dumps(document()))
            for filename, value in (('image-metadata.json',json.dumps(old)), ('image-id.txt',old['image_id']),
                                    ('image-ref.txt',metadata.reference(old)), ('source-sha.txt',SHA)):
                (previous/filename).write_text(value)
            (root/'current').symlink_to(previous)
            if invalid == 'outside':
                (root/'current').unlink()
                (root/'current').symlink_to(root)
            calls, configs, old_checks = [], [], []
            def command(arguments, **kwargs):
                calls.append(arguments)
                if arguments[:2] == ['docker','ps']:
                    return 'invalid container' if invalid == 'container' else 'f'*64
                if arguments[:2] == ['docker','inspect']:
                    return 'true '+('sha256:'+'f'*64 if invalid == 'identity' else old['image_id'])+' '+SHA
                if arguments[:2] == ['docker','login']:
                    configs.append(Path(kwargs['env']['DOCKER_CONFIG']))
                return 'private-token'
            def verify(item, **kwargs):
                if item['image_id'] == old['image_id']:
                    old_checks.append(True)
                    if len(old_checks) == 1 and missing:
                        raise missing('old image not local')
            temporary_directory = tempfile.TemporaryDirectory
            output = io.StringIO()
            with patch.object(prepare.tempfile,'TemporaryDirectory',side_effect=lambda **kwargs: temporary_directory(dir=directory)), \
                 patch.object(prepare.os,'geteuid',return_value=0), patch.object(prepare,'command',side_effect=command), \
                 patch.object(prepare,'verify',side_effect=verify), redirect_stdout(output):
                if invalid:
                    with self.assertRaises(ValueError):
                        prepare.prepare(candidate,SHA,local_only=local_only)
                else:
                    prepare.prepare(candidate,SHA,local_only=local_only)
            if invalid:
                self.assertFalse((candidate/'image-ref.txt').exists())
            else:
                self.assertEqual(ID,(candidate/'image-id.txt').read_text().strip())
                self.assertEqual(REF,(candidate/'image-ref.txt').read_text().strip())
                self.assertEqual(SHA,(candidate/'source-sha.txt').read_text().strip())
                self.assertEqual('IMAGE_PREPARED: '+SHA+' '+REF+' '+ID,output.getvalue().strip())
            pulls = [call[-1] for call in calls if call[:2] == ['docker','pull']]
            if local_only or invalid:
                self.assertEqual([],pulls)
                self.assertEqual([],configs)
            else:
                self.assertEqual([REF]+([metadata.reference(old)] if missing else []),pulls)
                self.assertTrue(configs)
                self.assertTrue(all(not path.exists() for path in configs))
            if missing:
                self.assertEqual(2,len(old_checks))

    def test_prepare_success_records_exact_metadata_and_cleans_temporary_auth(self):
        self.prepare_case()

    def test_previous_missing_value_or_key_error_pulls_digest_and_revalidates(self):
        for error in (ValueError,KeyError):
            with self.subTest(error=error):
                self.prepare_case(missing=error)

    def test_local_only_never_authenticates_or_pulls(self):
        self.prepare_case(local_only=True)

    def test_current_outside_releases_or_wrong_running_identity_fails_before_pull(self):
        for invalid in ('outside','container','identity'):
            with self.subTest(invalid=invalid):
                self.prepare_case(invalid=invalid)


class BundleAndWorkflowTest(unittest.TestCase):
    def test_prod_small_bundle_has_no_tar_and_stage_explicitly_keeps_tar(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)
            for filename in ('image-metadata.json','image-id.txt','image.tar.gz','image.tar.gz.sha256'):
                (path/filename).write_text('{}')
            deploy.ecr_bundle(ROOT,path,path/'prod.tar')
            with tarfile.open(path/'prod.tar') as archive:
                names=set(archive.getnames())
            self.assertIn('prepare-image.py',names)
            self.assertIn('image-metadata.json',names)
            self.assertNotIn('image.tar.gz',names)
            self.assertNotIn('prepare-staging-image.sh',names)
            deploy.bundle(ROOT,path,path/'stage.tar',ROOT)
            with tarfile.open(path/'stage.tar') as archive:
                self.assertIn('prepare-staging-image.sh',archive.getnames())
                self.assertIn('image.tar.gz',archive.getnames())
            (path/'image-metadata.json').unlink()
            (path/'image-metadata.json').symlink_to(path/'image-id.txt')
            with self.assertRaises(ValueError): deploy.ecr_bundle(ROOT,path,path/'rejected.tar')

    def test_oidc_boundary_and_exact_artifact_attempt(self):
        image=(ROOT/'.github/workflows/image.yml').read_text()
        publisher=(ROOT/'.github/workflows/image-publish.yml').read_text()
        build=(ROOT/'.github/workflows/build.yml').read_text()
        cd=(ROOT/'.github/workflows/cd.yml').read_text()
        self.assertNotIn('id-token:',image)
        self.assertNotIn('environment:',publisher)
        self.assertIn("github.ref == 'refs/heads/main'",publisher)
        self.assertIn('id-token: write',build.split('  publish-image:',1)[1])
        self.assertNotIn('id-token:',build.split('  publish-image:',1)[0])
        self.assertIn('test "$VALIDATE_RESULT" = success',image)
        self.assertIn('image-metadata-${{ github.event.workflow_run.head_sha }}-${{ github.event.workflow_run.id }}-${{ github.event.workflow_run.run_attempt }}',cd)
        self.assertNotIn('docker image load',cd)
        self.assertIn('environment: production',cd)


class PublisherTest(unittest.TestCase):
    def test_pr_dev_and_wrong_registry_never_build_or_authenticate(self):
        environment=dict(SOURCE_SHA=SHA,GITHUB_RUN_ID='123',GITHUB_RUN_ATTEMPT='2',
                         GITHUB_EVENT_NAME='push',GITHUB_REF='refs/heads/main',
                         ECR_REPOSITORY_URI=metadata.REPOSITORY,ECR_AWS_REGION='ap-northeast-2')
        for change in ({'GITHUB_EVENT_NAME':'pull_request'}, {'GITHUB_REF':'refs/heads/dev'},
                       {'ECR_REPOSITORY_URI':metadata.REPOSITORY+'-other'}):
            with self.subTest(change=change), patch.object(publisher,'command') as commands:
                with self.assertRaises(ValueError): publisher.publish(dict(environment,**change))
                commands.assert_not_called()

    def publish_case(self, failure=None):
        with tempfile.TemporaryDirectory() as directory:
            environment=dict(SOURCE_SHA=SHA,GITHUB_RUN_ID='123',GITHUB_RUN_ATTEMPT='2',
                             GITHUB_EVENT_NAME='push',GITHUB_REF='refs/heads/main',RUNNER_TEMP=directory,
                             ECR_REPOSITORY_URI=metadata.REPOSITORY,ECR_AWS_REGION='ap-northeast-2')
            configs=[]
            def command(arguments,**kwargs):
                if arguments[:2]==['aws','sts']: return json.dumps({'Account':'942466928325'})
                if arguments[:3]==['docker','image','inspect']:
                    return json.dumps([dict(Id=ID,Os='linux',Architecture='amd64',Config={'Labels':{'org.opencontainers.image.revision':SHA}})])
                if arguments[:2]==['docker','login']:
                    configs.append(Path(kwargs['env']['DOCKER_CONFIG']))
                    self.assertEqual(0o700,configs[-1].stat().st_mode & 0o777)
                if arguments[:3]==['aws','ecr','batch-get-image']:
                    row = dict(imageId={'imageDigest':DIGEST,'imageTag':SHA+'-123-2'},imageManifest=json.dumps({'config':{'digest':ID}}))
                    return json.dumps({'images': [] if failure == 'missing' else [row,row] if failure == 'multiple' else [row]})
                return 'private-token'
            with patch.object(publisher,'command',side_effect=command), \
                 patch.object(publisher.subprocess,'run',return_value=subprocess.CompletedProcess([],1 if failure == 'push' else 0)):
                if failure:
                    with self.assertRaises(ValueError):
                        publisher.publish(environment)
                else:
                    publisher.publish(environment)
            metadata_file = Path(directory)/'container-image/image-metadata.json'
            if failure:
                self.assertFalse(metadata_file.exists())
            else:
                self.assertEqual(document(),json.loads(metadata_file.read_text()))
            self.assertTrue(configs)
            self.assertTrue(all(not path.exists() for path in configs))

    def test_success_records_exact_digest_and_config_id_and_cleans_auth(self):
        self.publish_case()

    def test_push_failure_or_ecr_cardinality_never_writes_metadata_and_cleans_auth(self):
        for failure in ('push','missing','multiple'):
            with self.subTest(failure=failure):
                self.publish_case(failure)


class BaselineTest(unittest.TestCase):
    @contextmanager
    def fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            old = root / 'releases/old'
            old.mkdir(parents=True)
            (old / 'compose.deploy.yaml').write_text('services: {}\n')
            (old / 'fetch-app-env.py').write_text('# public\n')
            (old / 'image-id.txt').write_text(ID)
            (root / 'current').symlink_to(old)
            item = dict(document(), image_tag='baseline-'+SHA+'-copy-1')
            path = root / 'input.json'
            path.write_text(json.dumps(item))
            evidence = dict(container_id='d'*64, restart_count=0, image_id=ID, revision=SHA, env_sha256='e'*64)
            with patch.object(convert.os, 'geteuid', return_value=0), \
                 patch.object(convert.prepare, 'verify'), patch.object(convert.prepare, 'command'):
                yield root, old, path, evidence

    def test_no_restart_conversion_preserves_original_release_and_clears_marker(self):
        with self.fixture() as (root, old, path, evidence), patch.object(convert, 'snapshot', return_value=evidence):
            replace = convert.os.replace
            def checked_replace(pending, current):
                self.assertTrue((root/'deploy-in-progress/context').is_file())
                return replace(pending, current)
            with patch.object(convert.os, 'replace', side_effect=checked_replace):
                convert.convert(root, path, 'baseline-copy-1')
            self.assertEqual(root/'releases/baseline-copy-1', (root/'current').resolve())
            self.assertFalse((root/'deploy-in-progress').exists())
            self.assertEqual(ID, (old/'image-id.txt').read_text())
            self.assertFalse((old/'image-ref.txt').exists())
            self.assertEqual(REF, (root/'releases/baseline-copy-1/image-ref.txt').read_text().strip())

    def test_changed_application_restores_original_current_and_confirms_before_marker_cleanup(self):
        with self.fixture() as (root, old, path, evidence):
            changed = dict(evidence, restart_count=1)
            with patch.object(convert, 'snapshot', side_effect=[evidence,evidence,changed,evidence]):
                with self.assertRaisesRegex(ValueError, 'Application changed'):
                    convert.convert(root, path, 'baseline-copy-1')
            self.assertEqual(old, (root/'current').resolve())
            self.assertFalse((root/'deploy-in-progress').exists())
            self.assertTrue((root/'releases/baseline-copy-1').is_dir())

    def test_rollback_failure_keeps_marker_first_error_and_blocks_next_conversion(self):
        with self.fixture() as (root, old, path, evidence):
            changed = dict(evidence, restart_count=1)
            replace = convert.os.replace
            attempts = []
            def fail_rollback(pending, current):
                attempts.append(True)
                if len(attempts) == 2:
                    raise OSError('restore rename unavailable')
                return replace(pending, current)
            errors = io.StringIO()
            with patch.object(convert, 'snapshot', side_effect=[evidence,evidence,changed]), \
                 patch.object(convert.os, 'replace', side_effect=fail_rollback), redirect_stderr(errors):
                with self.assertRaisesRegex(ValueError, 'Application changed'):
                    convert.convert(root, path, 'baseline-copy-1')
            self.assertTrue((root/'deploy-in-progress').is_dir())
            self.assertIn('original=ValueError: Application changed', errors.getvalue())
            self.assertIn('recovery=OSError', errors.getvalue())
            self.assertTrue(old.is_dir())
            with self.assertRaisesRegex(ValueError, 'Unresolved deployment'):
                convert.convert(root, path, 'baseline-another')

    def test_unconfirmed_app_state_keeps_marker_after_link_restore(self):
        with self.fixture() as (root, old, path, evidence):
            changed = dict(evidence, restart_count=1)
            with patch.object(convert, 'snapshot', side_effect=[evidence,evidence,changed,changed]):
                with self.assertRaisesRegex(ValueError, 'Application changed'):
                    convert.convert(root, path, 'baseline-copy-1')
            self.assertEqual(old, (root/'current').resolve())
            self.assertTrue((root/'deploy-in-progress').is_dir())

    def test_copy_failure_removes_only_own_pretransition_directory_and_allows_retry(self):
        with self.fixture() as (root, old, path, evidence), patch.object(convert, 'snapshot', return_value=evidence):
            with patch.object(convert.shutil, 'copyfile', side_effect=OSError('copy unavailable')):
                with self.assertRaises(OSError):
                    convert.convert(root, path, 'baseline-copy-1')
            self.assertEqual(old, (root/'current').resolve())
            self.assertFalse((root/'releases/baseline-copy-1').exists())
            self.assertFalse((root/'deploy-in-progress').exists())
            convert.convert(root, path, 'baseline-copy-1')

    def test_safe_subprocess_diagnostic_does_not_print_captured_output(self):
        error = subprocess.CalledProcessError(2, ['docker','inspect'], output='private-value', stderr='private-value')
        self.assertEqual('CalledProcessError exit=2', convert.safe_error(error))


if __name__=='__main__': unittest.main()
