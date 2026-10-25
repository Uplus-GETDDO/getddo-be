"""ECR 출처 거부, 양쪽 사전 준비, 기준 기록 변환과 Stage 경계를 검증한다."""
import copy
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

    def test_success_records_exact_digest_and_config_id_and_cleans_auth(self):
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
                    return json.dumps({'images':[dict(imageId={'imageDigest':DIGEST,'imageTag':SHA+'-123-2'},imageManifest=json.dumps({'config':{'digest':ID}}))]})
                return 'private-token'
            with patch.object(publisher,'command',side_effect=command), \
                 patch.object(publisher.subprocess,'run',return_value=subprocess.CompletedProcess([],0)):
                publisher.publish(environment)
            self.assertEqual(document(),json.loads((Path(directory)/'container-image/image-metadata.json').read_text()))
            self.assertTrue(configs)
            self.assertTrue(all(not path.exists() for path in configs))


class BaselineTest(unittest.TestCase):
    def run_conversion(self, changed=False):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            old=root/'releases/old'
            old.mkdir(parents=True)
            (old/'compose.deploy.yaml').write_text('services: {}\n')
            (old/'fetch-app-env.py').write_text('# public\n')
            (old/'image-id.txt').write_text(ID)
            (root/'current').symlink_to(old)
            item=dict(document(),image_tag='baseline-'+SHA+'-copy-1')
            path=root/'input.json'
            path.write_text(json.dumps(item))
            evidence=dict(container_id='d'*64,restart_count=0,image_id=ID,revision=SHA,env_sha256='e'*64)
            snapshots=[evidence,evidence,dict(evidence,restart_count=1) if changed else evidence]
            with patch.object(convert.os,'geteuid',return_value=0), \
                 patch.object(convert.prepare,'verify') as verify, \
                 patch.object(convert.prepare,'command') as commands, \
                 patch.object(convert,'snapshot',side_effect=snapshots):
                if changed:
                    with self.assertRaises(ValueError): convert.convert(root,path,'baseline-copy-1')
                else:
                    convert.convert(root,path,'baseline-copy-1')
                verify.assert_called_once_with(item)
                self.assertTrue(all(call.args[0][0]=='sync' for call in commands.call_args_list))
            self.assertEqual(ID,(old/'image-id.txt').read_text())
            self.assertFalse((old/'image-ref.txt').exists())
            self.assertEqual(old if changed else root/'releases/baseline-copy-1',(root/'current').resolve())
            self.assertEqual(REF,(root/'releases/baseline-copy-1/image-ref.txt').read_text().strip())

    def test_no_restart_conversion_preserves_original_release(self): self.run_conversion()
    def test_changed_application_restores_original_current(self): self.run_conversion(changed=True)


if __name__=='__main__': unittest.main()
