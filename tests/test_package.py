import copy
import io
from pathlib import Path
import tempfile
import unittest
import warnings
import zipfile

from cryptography.hazmat.primitives.asymmetric import rsa

from uni_frame.package import Rejected, pack, parse_json, verify


class PackageTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.key = rsa.generate_private_key(public_exponent=65537, key_size=2048)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.payload = self.root / "payload"
        self.payload.mkdir()
        (self.payload / "module.apk").write_bytes(b"test fixture only, not an Android APK")
        self.metadata = {
            "formatVersion": 1, "projectId": "hello", "buildId": "build-1", "channel": "dev",
            "sourceCommit": "a" * 40, "releaseSequence": 2, "expiresAt": 2000,
            "hostApi": 1, "minAndroidApi": 24, "abis": ["arm64-v8a"],
            "runtimeFingerprint": "b" * 64, "requiredCapabilities": ["ble.scan.v1"],
        }
        self.host = {"androidApi": 34, "abi": "arm64-v8a", "hostApi": 1,
                     "runtimeFingerprint": "b" * 64, "capabilities": ["ble.scan.v1"]}
        self.bundle = self.root / "module.uaf"
        pack(self.payload, self.metadata, self.key, self.bundle)

    def check(self, package=None, **changes):
        options = dict(project="hello", channel="dev", highest_sequence=1, now=1000)
        options.update(changes)
        return verify(package or self.bundle, self.key.public_key(), self.host, **options)

    def reject(self, code, **changes):
        with self.assertRaises(Rejected) as caught:
            self.check(**changes)
        self.assertEqual(caught.exception.code, code)

    def rewrite(self, changes=None, extra=None):
        data = io.BytesIO()
        with zipfile.ZipFile(self.bundle) as source, zipfile.ZipFile(data, "w") as target:
            for name in source.namelist():
                target.writestr(name, (changes or {}).get(name, source.read(name)))
            for name, value in extra or []:
                target.writestr(name, value)
        data.seek(0)
        return data

    def test_accept_complete_signed_release(self):
        self.assertEqual(self.check()["buildId"], "build-1")

    def test_android_versions_are_a_minimum_not_a_brand_allowlist(self):
        for api in (24, 30, 31, 33, 34, 35, 36):
            with self.subTest(api=api):
                self.host["androidApi"] = api
                self.assertEqual(self.check()["projectId"], "hello")
        self.host["androidApi"] = 23
        self.reject("ANDROID_TOO_OLD")

    def test_reject_wrong_key(self):
        other = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        with self.assertRaisesRegex(Rejected, "SIGNATURE_INVALID"):
            verify(self.bundle, other.public_key(), self.host, project="hello", channel="dev", highest_sequence=0, now=1000)

    def test_reject_tampered_manifest_before_compatibility(self):
        self.reject("SIGNATURE_INVALID", package=self.rewrite({"manifest.json": b'{"minAndroidApi":1}'}))

    def test_reject_tampered_payload(self):
        original = (self.payload / "module.apk").read_bytes()
        self.reject("DIGEST_MISMATCH", package=self.rewrite({"payload/module.apk": b"X" + original[1:]}))

    def test_reject_unlisted_executable(self):
        self.reject("FILE_SET_MISMATCH", package=self.rewrite(extra=[("payload/extra.dex", b"code")]))

    def test_reject_missing_payload(self):
        data = io.BytesIO()
        with zipfile.ZipFile(self.bundle) as source, zipfile.ZipFile(data, "w") as target:
            for name in ("manifest.json", "signature.json"):
                target.writestr(name, source.read(name))
        data.seek(0)
        self.reject("FILE_SET_MISMATCH", package=data)

    def test_reject_wrong_size(self):
        self.reject("SIZE_MISMATCH", package=self.rewrite({"payload/module.apk": b"short"}))

    def test_reject_abi_runtime_host_capability_mismatches(self):
        for field, value, code in [
            ("abi", "x86_64", "ABI_MISMATCH"),
            ("runtimeFingerprint", "c" * 64, "RUNTIME_MISMATCH"),
            ("hostApi", 2, "HOST_API_MISMATCH"),
            ("capabilities", [], "CAPABILITY_MISSING"),
        ]:
            original = copy.deepcopy(self.host)
            with self.subTest(field=field):
                self.host[field] = value
                self.reject(code)
            self.host = original

    def test_remote_replay_and_expiry_are_rejected(self):
        self.reject("RELEASE_REPLAY", highest_sequence=2)
        self.reject("RELEASE_REPLAY", highest_sequence=3)
        self.reject("METADATA_EXPIRED", now=2000)

    def test_release_cannot_be_substituted_across_project_or_channel(self):
        self.reject("RELEASE_SCOPE_MISMATCH", project="DemoSecond")
        self.reject("RELEASE_SCOPE_MISMATCH", channel="stable")

    def test_reject_traversal_and_duplicate_entries(self):
        for name in ("../escape.dex", "/absolute.dex", "payload/../escape.dex", "payload\\escape.dex", "payload//code.dex"):
            with self.subTest(name=name):
                self.reject("UNSAFE_PATH", package=self.rewrite(extra=[(name, b"code")]))
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            duplicate = self.rewrite(extra=[("payload/module.apk", b"code")])
        self.reject("DUPLICATE_ZIP_ENTRY", package=duplicate)

    def test_reject_symlink(self):
        link = zipfile.ZipInfo("payload/link")
        link.create_system = 3
        link.external_attr = 0o120777 << 16
        self.reject("UNSAFE_FILE_TYPE", package=self.rewrite(extra=[(link, b"/tmp/elsewhere")]))

    def test_reject_corrupted_archive(self):
        self.reject("ARCHIVE_INVALID", package=io.BytesIO(self.bundle.read_bytes()[:80]))

    def test_metadata_parser_rejects_ambiguity(self):
        for content in (b'{"a":1,"a":2}', b'{"a":NaN}', b"not json"):
            with self.subTest(content=content), self.assertRaises(Rejected):
                parse_json(content)

    def test_pack_never_overwrites_a_published_file(self):
        before = self.bundle.read_bytes()
        with self.assertRaises(FileExistsError):
            pack(self.payload, self.metadata, self.key, self.bundle)
        self.assertEqual(before, self.bundle.read_bytes())

    def test_pack_rejects_symlink_input(self):
        (self.payload / "link.dex").symlink_to(self.payload / "module.apk")
        with self.assertRaisesRegex(Rejected, "UNSAFE_FILE_TYPE"):
            pack(self.payload, self.metadata, self.key, self.root / "new.uaf")

    def test_pack_rejects_missing_fingerprint_and_boolean_version(self):
        for field, value in (("runtimeFingerprint", "unknown"), ("releaseSequence", True)):
            with self.subTest(field=field):
                metadata = dict(self.metadata, **{field: value})
                with self.assertRaises(Rejected):
                    pack(self.payload, metadata, self.key, self.root / (field + ".uaf"))
                self.assertFalse((self.root / (field + ".uaf")).exists())
                self.assertEqual(list(self.root.glob(".uaf-*")), [])


if __name__ == "__main__":
    unittest.main()
