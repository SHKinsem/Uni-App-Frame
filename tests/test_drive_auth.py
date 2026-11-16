import json
import os
from pathlib import Path
import stat
import tempfile
import unittest

from uni_frame import drive_auth
from uni_frame.drive_api import looks_secret


class FakeGoogle:
    def __init__(self, responses):
        self.responses, self.calls = list(responses), []

    def __call__(self, url, fields):
        self.calls.append((url, fields))
        return self.responses.pop(0)


class DriveAuthTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.env = {'UNI_FRAME_CONFIG_DIR': self.tmp.name,
                    'UNI_FRAME_DRIVE_CLIENT_ID': 'cid', 'UNI_FRAME_DRIVE_CLIENT_SECRET': 'csecret'}

    def test_missing_client_explains_setup(self):
        empty = Path(self.tmp.name) / 'client.json'
        empty.write_text(json.dumps({'client_id': '', 'client_secret': ''}))
        with self.assertRaisesRegex(drive_auth.AuthError, 'drive-publishing'):
            drive_auth.client({}, empty)

    def test_direct_access_token_wins(self):
        google = FakeGoogle([])
        self.assertEqual(drive_auth.access_token({**self.env, 'GOOGLE_DRIVE_ACCESS_TOKEN': 'direct'}, google), 'direct')
        self.assertEqual(google.calls, [])

    def test_not_signed_in(self):
        with self.assertRaisesRegex(drive_auth.AuthError, 'drive.py login'):
            drive_auth.access_token(self.env, FakeGoogle([]))

    def test_refresh_from_environment_secret(self):
        google = FakeGoogle([{'access_token': 'fresh'}])
        token = drive_auth.access_token({**self.env, 'UNI_FRAME_DRIVE_REFRESH_TOKEN': 'rt'}, google)
        self.assertEqual(token, 'fresh')
        url, fields = google.calls[0]
        self.assertEqual(url, drive_auth.TOKEN_ENDPOINT)
        self.assertEqual(fields['grant_type'], 'refresh_token')
        self.assertEqual(fields['refresh_token'], 'rt')

    def test_revoked_refresh_token_asks_to_login_again(self):
        google = FakeGoogle([{'error': 'invalid_grant'}])
        with self.assertRaisesRegex(drive_auth.AuthError, 'invalid_grant.*login'):
            drive_auth.access_token({**self.env, 'UNI_FRAME_DRIVE_REFRESH_TOKEN': 'rt'}, google)

    def test_device_login_polls_then_saves_owner_only(self):
        google = FakeGoogle([
            {'device_code': 'dc', 'user_code': 'ABCD-EFGH', 'verification_url': 'https://www.google.com/device',
             'interval': 1, 'expires_in': 600},
            {'error': 'authorization_pending'}, {'error': 'slow_down'},
            {'access_token': 'at', 'refresh_token': 'rt', 'scope': drive_auth.SCOPE}])
        shown = []
        record, token = drive_auth.device_login(self.env, google, lambda s: None, shown.append)
        self.assertEqual((record['refresh_token'], token), ('rt', 'at'))
        self.assertEqual(google.calls[0][1]['scope'], drive_auth.SCOPE)
        self.assertTrue(any('ABCD-EFGH' in line for line in shown))
        path = drive_auth.save(record, self.env)
        self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)
        self.assertEqual(drive_auth.refresh_token(self.env), 'rt')

    def test_device_login_denied(self):
        google = FakeGoogle([{'device_code': 'dc', 'user_code': 'X', 'verification_url': 'u', 'interval': 1},
                             {'error': 'access_denied'}])
        with self.assertRaisesRegex(drive_auth.AuthError, 'access_denied'):
            drive_auth.device_login(self.env, google, lambda s: None, lambda s: None)

    def test_scope_is_limited_to_files_the_publisher_created(self):
        self.assertTrue(drive_auth.SCOPE.endswith('/drive.file'))

    def test_only_google_oauth_endpoints(self):
        with self.assertRaises(ValueError):
            drive_auth._post('https://oauth2.googleapis.com.evil.test/token', {})

    def test_folder_precedence_matches_between_publish_and_status(self):
        import importlib.util
        from unittest.mock import patch
        root = Path(__file__).resolve().parents[1]
        modules = []
        for name in ('publish-drive', 'drive'):
            spec = importlib.util.spec_from_file_location(name.replace('-', '_'), root / 'tools' / f'{name}.py')
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            modules.append(module)
        drive_auth.save({'refresh_token': 'rt', 'folder': 'saved_folder_id'}, self.env)
        for environment, expected in [({}, 'saved_folder_id'),
                                      ({'UNI_FRAME_DRIVE_FOLDER': 'env_folder_id'}, 'env_folder_id')]:
            with patch.dict('os.environ', {**self.env, **environment}, clear=True):
                self.assertEqual(modules[0].default_folder(), expected)
                self.assertEqual(modules[1].default_folder(), expected)

    def test_secret_file_detection(self):
        for name in ['publisher.pem', 'host.keystore', 'apk-signing.properties', 'x.JKS']:
            self.assertTrue(looks_secret(name), name)
        for name in ['catalog.json', 'app-x86_64-3.uaf', 'publisher.der', 'host.apk']:
            self.assertFalse(looks_secret(name), name)


if __name__ == '__main__':
    unittest.main()
