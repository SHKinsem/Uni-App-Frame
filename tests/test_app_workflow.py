import base64
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import time
import unittest

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.exceptions import InvalidSignature
from uni_frame.package import pack

ROOT = Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / f'{name}.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.stage = load('stage-app')
        self.stage.ROOT = self.root / 'framework'
        raw = self.stage.ROOT / 'android/host/src/main/res/raw'
        raw.mkdir(parents=True)
        self.key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        self.key_path = self.root / 'publisher.pem'
        self.key_path.write_bytes(self.key.private_bytes(serialization.Encoding.PEM,
                                  serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        (raw / 'publisher.der').write_bytes(self.key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo))
        (raw / 'runtime_profiles.json').write_text(json.dumps({'x86_64': {'fingerprint': 'a' * 64}}))
        (self.stage.ROOT / 'tools').mkdir()
        shutil.copy(ROOT / 'tools/build-catalog.py', self.stage.ROOT / 'tools/build-catalog.py')
        self.dist = self.root / 'dist'
        self.dist.mkdir()
        (self.dist / 'host-update.json').write_text('untouched host metadata')

    def release(self, project, sequence, *, expired=False):
        directory = self.root / f'{project}-{sequence}'
        directory.mkdir()
        payload = directory / 'payload'
        payload.mkdir()
        (payload / 'project.apk').write_bytes(b'fixture apk')
        bundle = directory / f'{project}-{sequence}.uaf'
        metadata = dict(formatVersion=1, projectId=project, buildId=f'test-{sequence}',
            sourceCommit='b' * 40, releaseSequence=sequence,
            expiresAt=int(time.time()) + (-10 if expired else 86400),
            channel='dev', hostApi=1, minAndroidApi=24, abis=['x86_64'],
            runtimeFingerprint='a' * 64, requiredCapabilities=['storage.project.v1'])
        pack(payload, metadata, self.key, bundle)
        receipt = dict(projectId=project, name=project, description='test', sourceCommit='b' * 40,
                       sequence=sequence, releases=[dict(abi='x86_64', package=bundle.name,
                       sha256=hashlib.sha256(bundle.read_bytes()).hexdigest())])
        (directory / 'release.json').write_text(json.dumps(receipt))
        return directory

    def publish(self, release):
        self.stage.stage(release, self.dist, self.key_path)

    def test_merge_preserves_other_apps_and_host_files(self):
        self.publish(self.release('first', 1))
        self.publish(self.release('second', 1))
        self.publish(self.release('first', 2))
        result = self.stage.read_catalog(self.dist / 'catalog.json', self.key.public_key())
        self.assertEqual({a['projectId']: a['releases'][0]['sequence'] for a in result['apps']},
                         {'first': 2, 'second': 1})
        self.assertEqual((self.dist / 'host-update.json').read_text(), 'untouched host metadata')

    def test_replay_leaves_catalog_unchanged(self):
        release = self.release('first', 1)
        self.publish(release)
        before = (self.dist / 'catalog.json').read_bytes()
        with self.assertRaisesRegex(ValueError, 'sequence'):
            self.publish(release)
        self.assertEqual((self.dist / 'catalog.json').read_bytes(), before)

    def test_tampered_catalog_cannot_be_resigned(self):
        self.publish(self.release('first', 1))
        path = self.dist / 'catalog.json'
        envelope = json.loads(path.read_text())
        envelope['payload'] = base64.b64encode(b'{"apps":[]}').decode()
        path.write_text(json.dumps(envelope))
        with self.assertRaises(InvalidSignature):
            self.publish(self.release('second', 1))
        self.assertFalse((self.dist / 'second-1.uaf').exists())

    def test_expired_bundle_rejected(self):
        with self.assertRaisesRegex(ValueError, 'METADATA_EXPIRED'):
            self.publish(self.release('first', 1, expired=True))
        self.assertFalse((self.dist / 'catalog.json').exists())

    def test_modified_bundle_rejected_before_copy(self):
        release = self.release('first', 1)
        (release / 'first-1.uaf').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'digest'):
            self.publish(release)
        self.assertFalse((self.dist / 'first-1.uaf').exists())

    def test_generator_rejects_existing_directory_without_running_flutter(self):
        with self.assertRaises(FileExistsError):
            load('new-app').create(self.dist, 'example', 'dev.example.app', 'Example')
        self.assertEqual((self.dist / 'host-update.json').read_text(), 'untouched host metadata')

    def test_generator_rejects_host_identity(self):
        with self.assertRaisesRegex(ValueError, 'host package'):
            load('new-app').create(self.root / 'app', 'example', 'dev.uniapp.frame', 'Example')

    def test_generator_rejects_invalid_package(self):
        with self.assertRaisesRegex(ValueError, 'Java package'):
            load('new-app').create(self.root / 'app', 'example', '../escape', 'Example')


if __name__ == '__main__':
    unittest.main()
