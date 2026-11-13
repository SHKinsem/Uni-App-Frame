import copy
import importlib.util
import unittest
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric import rsa

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('drive_publish', ROOT / 'tools/publish-drive.py')
drive = importlib.util.module_from_spec(spec)
spec.loader.exec_module(drive)


class DrivePublishTests(unittest.TestCase):
    def setUp(self):
        self.catalog = {'apps': [{'projectId': 'example', 'releases': [
            {'abi': 'x86_64', 'sequence': 3, 'sha256': 'a' * 64, 'url': 'example.uaf'}]}]}

    def test_rewrite_signs_absolute_drive_links_and_keeps_local_mirror(self):
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        before = copy.deepcopy(self.catalog)
        update = {'apkUrl': 'host.apk'}
        catalog, announcement = drive.rewrite(self.catalog, update, {
            'example.uaf': 'public_module_id', 'host.apk': 'public_apk_id',
            'host-update.json': 'public_update_id'}, key)
        decoded = drive.read_signed(catalog, key.public_key())
        self.assertEqual(decoded['apps'][0]['releases'][0]['url'], drive.link('public_module_id'))
        self.assertEqual(decoded['hostUpdateUrl'], drive.link('public_update_id'))
        self.assertEqual(drive.read_signed(announcement, key.public_key())['apkUrl'], drive.link('public_apk_id'))
        self.assertEqual(self.catalog, before)
        self.assertEqual(update, {'apkUrl': 'host.apk'})

    def test_rejects_removing_other_app(self):
        with self.assertRaisesRegex(ValueError, 'remove an existing app'):
            drive.preserve_releases(self.catalog, {'apps': []})

    def test_rejects_downgrade_or_changed_same_sequence(self):
        for change in [{'sequence': 2}, {'sha256': 'b' * 64}, {'abi': 'arm64-v8a'}]:
            candidate = copy.deepcopy(self.catalog)
            candidate['apps'][0]['releases'][0].update(change)
            with self.assertRaises(ValueError):
                drive.preserve_releases(self.catalog, candidate)

    def test_accepts_republish_and_higher_sequence(self):
        drive.preserve_releases(self.catalog, self.catalog)
        candidate = copy.deepcopy(self.catalog)
        candidate['apps'][0]['releases'][0].update(sequence=4, sha256='b' * 64)
        drive.preserve_releases(self.catalog, candidate)

    def test_refuses_sending_oauth_token_to_unexpected_hosts(self):
        for url in ['http://www.googleapis.com/upload', 'https://www.googleapis.com.evil.test/upload']:
            with self.assertRaises(ValueError):
                drive.Drive('test-value').request(url)

    def test_rejects_invalid_file_id(self):
        with self.assertRaises(ValueError):
            drive.link('id&other=oops')


if __name__ == '__main__':
    unittest.main()
