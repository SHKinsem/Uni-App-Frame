#!/usr/bin/env python3
"""Generate a publisher's own public trust resource and frozen runtime profiles."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--flutter-sdk', type=Path, required=True)
parser.add_argument('--shadow-maven-repo', type=Path, required=True)
parser.add_argument('--llvm-strip', type=Path, required=True)
parser.add_argument('--publisher-key', type=Path, required=True)
parser.add_argument('--create-key', action='store_true', help='Create a new external publisher key if absent')
args = parser.parse_args()
if args.publisher_key.resolve().is_relative_to(ROOT):
    parser.error('Publisher private key must remain outside the framework repository')
raw = ROOT / 'android/host/src/main/res/raw'
raw.mkdir(parents=True, exist_ok=True)
if not args.publisher_key.exists():
    if not args.create_key:
        parser.error('Key missing; pass --create-key only when setting up your own new publisher')
    args.publisher_key.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    with args.publisher_key.open('xb') as output:
        args.publisher_key.chmod(0o600)
        output.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
key = serialization.load_pem_private_key(args.publisher_key.read_bytes(), None)
public = key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
if (raw / 'publisher.der').exists() and (raw / 'publisher.der').read_bytes() != public:
    parser.error('Existing publisher trust differs; intentional key rotation needs a separate migration')
version = 'local-b2a7a74-SNAPSHOT'
shadow_hashes = {}
for name in ('loader', 'runtime', 'activity-container', 'common', 'load-parameters'):
    path = args.shadow_maven_repo / 'com/tencent/shadow/core' / name / version / f'{name}-{version}.jar'
    shadow_hashes[name] = hashlib.sha256(path.read_bytes()).hexdigest()
profiles = {}
for abi, arch in [('arm64-v8a', 'android-arm64-release'), ('x86_64', 'android-x64-release')]:
    jar = args.flutter_sdk / 'bin/cache/artifacts/engine' / arch / 'flutter.jar'
    with zipfile.ZipFile(jar) as archive, tempfile.TemporaryDirectory() as tmp:
        library = Path(tmp) / 'libflutter.so'
        library.write_bytes(archive.read(f'lib/{abi}/libflutter.so'))
        subprocess.run([str(args.llvm_strip), '--strip-unneeded', str(library)], check=True)
        engine = hashlib.sha256(library.read_bytes()).hexdigest()
    identity = dict(hostApi=1, loaderProfile='static-shadow-p1-v1', engineSha256=engine,
        flutterJarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(), shadowJars=shadow_hashes)
    fingerprint = hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
    profiles[abi] = dict(fingerprint=fingerprint, engineSha256=engine, identity=identity)
(raw / 'publisher.der').write_bytes(public)
(raw / 'runtime_profiles.json').write_text(json.dumps(profiles, indent=2) + '\n')
print('Prepared your own publisher trust and two runtime profiles; no private key was copied into the repository.')
