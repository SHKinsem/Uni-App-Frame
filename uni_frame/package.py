"""Signed full-package prototype; verification never extracts or executes code.

RSA/SHA-256 is intentionally usable by Android's standard Signature API on API 24+.
Trust keys and expected project/channel come from the host, never from the archive.
The signature covers exact manifest bytes, avoiding cross-language JSON canonicalization.
This is a P1 protocol, not TUF and not a complete phone update manager.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import tempfile
import time
import zipfile

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

MAX_METADATA = 1024 * 1024
MAX_FILES = 4096
MAX_TOTAL = 1024 * 1024 * 1024
RESERVED = {"manifest.json", "signature.json"}
IDENTIFIER = re.compile(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}\Z")


class Rejected(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def require(condition, code):
    if not condition:
        raise Rejected(code)


def json_bytes(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True, allow_nan=False).encode()


def parse_json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "DUPLICATE_JSON_KEY")
            result[key] = value
        return result

    def invalid_constant(_):
        raise Rejected("INVALID_JSON")

    try:
        return json.loads(data, object_pairs_hook=pairs, parse_constant=invalid_constant)
    except (ValueError, UnicodeError, RecursionError) as error:
        if isinstance(error, Rejected):
            raise
        raise Rejected("INVALID_JSON") from error


def safe_path(name):
    require(isinstance(name, str) and bool(name), "UNSAFE_PATH")
    path = PurePosixPath(name)
    require(not path.is_absolute() and "\\" not in name and ":" not in name
            and all(part not in {"", ".", ".."} for part in name.split("/"))
            and all(ord(c) >= 32 for c in name) and len(name) <= 240, "UNSAFE_PATH")
    return name


def validate_manifest(m):
    require(isinstance(m, dict), "INVALID_MANIFEST")
    require(m.get("formatVersion") == 1 and type(m.get("formatVersion")) is int, "FORMAT_UNSUPPORTED")
    for field in ("projectId", "buildId", "channel"):
        require(isinstance(m.get(field), str) and IDENTIFIER.fullmatch(m[field]), "INVALID_ID")
    for field in ("releaseSequence", "expiresAt", "minAndroidApi", "hostApi"):
        require(type(m.get(field)) is int and m[field] > 0, "INVALID_INTEGER")
    for field in ("runtimeFingerprint", "sourceCommit"):
        require(isinstance(m.get(field), str) and bool(m[field]), "MISSING_BUILD_IDENTITY")
    require(re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", m["sourceCommit"]) is not None, "INVALID_SOURCE_COMMIT")
    require(re.fullmatch(r"[0-9a-f]{64}", m["runtimeFingerprint"]) is not None, "INVALID_RUNTIME_FINGERPRINT")
    for field in ("abis", "requiredCapabilities"):
        values = m.get(field)
        require(isinstance(values, list) and all(isinstance(v, str) and v for v in values), "INVALID_REQUIREMENTS")
        require(len(values) == len(set(values)), "INVALID_REQUIREMENTS")
    require(bool(m["abis"]) and set(m["abis"]) <= {"arm64-v8a", "armeabi-v7a", "x86_64"}, "INVALID_ABI")
    files = m.get("files")
    require(isinstance(files, list) and 0 < len(files) <= MAX_FILES, "INVALID_FILES")
    paths, total = set(), 0
    for item in files:
        require(isinstance(item, dict), "INVALID_FILE")
        name = safe_path(item.get("path"))
        require(name.startswith("payload/") and name not in paths, "DUPLICATE_OR_RESERVED_PATH")
        paths.add(name)
        require(type(item.get("size")) is int and 0 <= item["size"] <= MAX_TOTAL, "INVALID_SIZE")
        require(isinstance(item.get("sha256"), str) and re.fullmatch(r"[0-9a-f]{64}", item["sha256"]), "INVALID_DIGEST")
        total += item["size"]
    require(total <= MAX_TOTAL, "PACKAGE_TOO_LARGE")


def verify(package, public_key, host, *, project, channel, highest_sequence, now=None):
    """Validate a newly received release. Caller persists high-water mark per project/channel.

    Already accepted offline versions need a separate local recovery flow; do not feed them
    through remote sequence/expiry checks. This function does not mutate activation state.
    """
    require(type(highest_sequence) is int and highest_sequence >= 0, "INVALID_HIGH_WATER_MARK")
    require(isinstance(host, dict), "INVALID_HOST")
    require(isinstance(public_key, rsa.RSAPublicKey) and public_key.key_size >= 2048, "INVALID_TRUST_KEY")
    try:
        with zipfile.ZipFile(package) as archive:
            infos = archive.infolist()
            names = [safe_path(item.filename) for item in infos]
            require(len(names) == len(set(names)), "DUPLICATE_ZIP_ENTRY")
            require(len(names) <= MAX_FILES + 2, "TOO_MANY_FILES")
            require(RESERVED <= set(names), "MISSING_METADATA")
            require(sum(i.file_size for i in infos) <= MAX_TOTAL + MAX_METADATA * 2, "PACKAGE_TOO_LARGE")
            for info in infos:
                require(not info.is_dir() and stat.S_IFMT(info.external_attr >> 16) in {0, stat.S_IFREG}, "UNSAFE_FILE_TYPE")
                require(info.compress_type in {zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED}
                        and not info.flag_bits & 1, "UNSUPPORTED_ZIP")
            for name in RESERVED:
                require(archive.getinfo(name).file_size <= MAX_METADATA, "METADATA_TOO_LARGE")
            raw = archive.read("manifest.json")
            signature = parse_json(archive.read("signature.json"))
            require(isinstance(signature, dict) and signature.get("algorithm") == "RS256", "SIGNATURE_ALGORITHM")
            try:
                public_key.verify(base64.b64decode(signature["signature"], validate=True), raw,
                                  padding.PKCS1v15(), hashes.SHA256())
            except (InvalidSignature, ValueError, KeyError, TypeError) as error:
                raise Rejected("SIGNATURE_INVALID") from error
            manifest = parse_json(raw)
            validate_manifest(manifest)
            require(manifest["projectId"] == project and manifest["channel"] == channel, "RELEASE_SCOPE_MISMATCH")
            require(manifest["expiresAt"] > (int(time.time()) if now is None else now), "METADATA_EXPIRED")
            require(manifest["releaseSequence"] > highest_sequence, "RELEASE_REPLAY")
            require(type(host.get("androidApi")) is int and host["androidApi"] >= manifest["minAndroidApi"], "ANDROID_TOO_OLD")
            require(host.get("abi") in manifest["abis"], "ABI_MISMATCH")
            require(type(host.get("hostApi")) is int and host["hostApi"] == manifest["hostApi"], "HOST_API_MISMATCH")
            require(host.get("runtimeFingerprint") == manifest["runtimeFingerprint"], "RUNTIME_MISMATCH")
            require(isinstance(host.get("capabilities"), list)
                    and all(isinstance(c, str) for c in host["capabilities"]), "INVALID_HOST_CAPABILITIES")
            require(set(manifest["requiredCapabilities"]) <= set(host["capabilities"]), "CAPABILITY_MISSING")
            expected = {entry["path"] for entry in manifest["files"]} | RESERVED
            require(set(names) == expected, "FILE_SET_MISMATCH")
            for entry in manifest["files"]:
                require(archive.getinfo(entry["path"]).file_size == entry["size"], "SIZE_MISMATCH")
                digest, read = hashlib.sha256(), 0
                with archive.open(entry["path"]) as source:
                    while chunk := source.read(1024 * 1024):
                        read += len(chunk)
                        require(read <= entry["size"], "SIZE_MISMATCH")
                        digest.update(chunk)
                require(read == entry["size"] and digest.hexdigest() == entry["sha256"], "DIGEST_MISMATCH")
            return manifest
    except (zipfile.BadZipFile, EOFError, NotImplementedError, RuntimeError) as error:
        raise Rejected("ARCHIVE_INVALID") from error


def pack(directory, metadata, private_key, output):
    require(isinstance(private_key, rsa.RSAPrivateKey) and private_key.key_size >= 2048, "INVALID_SIGNING_KEY")
    require(isinstance(metadata, dict), "INVALID_MANIFEST")
    directory, output = Path(directory).resolve(), Path(output).resolve()
    require(directory.is_dir() and not output.is_relative_to(directory), "OUTPUT_INSIDE_PAYLOAD")
    # Only a fully written signed archive receives the requested publication filename.
    descriptor, staging = tempfile.mkstemp(prefix=".uaf-", dir=output.parent)
    try:
        with os.fdopen(descriptor, "w+b") as target:
            manifest = _write_package(directory, metadata, private_key, target)
            target.flush()
            os.fsync(target.fileno())
        os.link(staging, output)  # Atomic no-replace; never overwrite an immutable release.
        return manifest
    finally:
        os.unlink(staging)


def _write_package(directory, metadata, private_key, output):
    entries, manifest = [], dict(metadata)
    for source in sorted(directory.rglob("*")):
        require(not source.is_symlink(), "UNSAFE_FILE_TYPE")
        if source.is_file():
            name = safe_path("payload/" + source.relative_to(directory).as_posix())
            entries.append((source, name))
    require(0 < len(entries) <= MAX_FILES, "INVALID_FILES")
    # Hash and write the same open stream; a changed input cannot produce a signed wrong digest.
    manifest["files"] = []
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        total = 0
        for source, name in entries:
            digest, size = hashlib.sha256(), 0
            with source.open("rb") as incoming, archive.open(name, "w") as outgoing:
                while chunk := incoming.read(1024 * 1024):
                    size += len(chunk)
                    total += len(chunk)
                    require(total <= MAX_TOTAL, "PACKAGE_TOO_LARGE")
                    outgoing.write(chunk)
                    digest.update(chunk)
            manifest["files"].append({"path": name, "size": size, "sha256": digest.hexdigest()})
        validate_manifest(manifest)
        raw = json_bytes(manifest)
        require(len(raw) <= MAX_METADATA, "METADATA_TOO_LARGE")
        signature = private_key.sign(raw, padding.PKCS1v15(), hashes.SHA256())
        archive.writestr("manifest.json", raw)
        archive.writestr("signature.json", json_bytes({"algorithm": "RS256", "signature": base64.b64encode(signature).decode()}))
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="action", required=True)
    make = actions.add_parser("pack")
    make.add_argument("--payload", type=Path, required=True)
    make.add_argument("--metadata", type=Path, required=True)
    make.add_argument("--private-key", type=Path, required=True)
    make.add_argument("--output", type=Path, required=True)
    check = actions.add_parser("verify")
    check.add_argument("--package", type=Path, required=True)
    check.add_argument("--public-key", type=Path, required=True)
    check.add_argument("--host", type=Path, required=True)
    check.add_argument("--project", required=True)
    check.add_argument("--channel", required=True)
    check.add_argument("--highest-sequence", type=int, required=True)
    args = parser.parse_args()
    try:
        if args.action == "pack":
            key = serialization.load_pem_private_key(args.private_key.read_bytes(), password=None)
            result = pack(args.payload, parse_json(args.metadata.read_bytes()), key, args.output)
        else:
            key = serialization.load_pem_public_key(args.public_key.read_bytes())
            result = verify(args.package, key, parse_json(args.host.read_bytes()), project=args.project,
                            channel=args.channel, highest_sequence=args.highest_sequence)
        print(json.dumps({"status": "verified" if args.action == "verify" else "packed", "manifest": result}, indent=2))
    except (Rejected, OSError, ValueError) as error:
        parser.exit(1, f"rejected: {error}\n")


if __name__ == "__main__":
    main()
