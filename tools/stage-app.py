#!/usr/bin/env python3
"""Merge one verified app release into an existing local Site distribution."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from uni_frame.package import safe_path, verify
from uni_frame.config import runtime_resources


def read_catalog(path, public_key):
    envelope = json.loads(path.read_text())
    if envelope['algorithm'] != 'RS256':
        raise ValueError('Unsupported catalog signature')
    payload = base64.b64decode(envelope['payload'], validate=True)
    public_key.verify(base64.b64decode(envelope['signature'], validate=True), payload,
                      padding.PKCS1v15(), hashes.SHA256())
    catalog = json.loads(payload)
    if catalog['formatVersion'] != 1:
        raise ValueError('Unsupported catalog format')
    return catalog  # An expired catalog can be renewed after verifying all packages.


def check_bundle(path, public_key, profiles, project):
    with zipfile.ZipFile(path) as archive:
        metadata = json.loads(archive.read('manifest.json'))
    if len(metadata['abis']) != 1:
        raise ValueError('Exactly one ABI per release is required')
    abi = metadata['abis'][0]
    profile = profiles[abi]
    return verify(path, public_key, dict(androidApi=36, abi=abi, hostApi=1,
                  runtimeFingerprint=profile['fingerprint'], capabilities=['storage.project.v1']),
                  project=project, channel='dev', highest_sequence=0)


def stage(release_dir, directory, key_path):
    release_dir, directory = release_dir.resolve(), directory.resolve()
    if not directory.is_dir():
        raise ValueError('Site distribution must already exist')
    key = serialization.load_pem_private_key(key_path.read_bytes(), password=None)
    trusted = serialization.load_der_public_key((runtime_resources(ROOT) / 'publisher.der').read_bytes())
    if key.public_key().public_numbers() != trusted.public_numbers():
        raise ValueError('Signing key differs from the host publisher key')
    profiles = json.loads((runtime_resources(ROOT) / 'runtime_profiles.json').read_text())
    receipt = json.loads((release_dir / 'release.json').read_text())
    destination = directory / 'catalog.json'
    catalog = read_catalog(destination, trusted) if destination.exists() else {
        'formatVersion': 1, 'name': 'Uni Frame 应用库', 'apps': []}
    old = next((a for a in catalog['apps'] if a['projectId'] == receipt['projectId']), None)
    if old and receipt['sequence'] <= max(r['sequence'] for r in old['releases']):
        raise ValueError('Release sequence must increase; rollback also needs a new sequence')
    incoming, abis = [], set()
    for release in receipt['releases']:
        name = safe_path(release['package'])
        path = (release_dir / name).resolve()
        if path.parent != release_dir:
            raise ValueError('Release packages must be direct files in the release directory')
        if hashlib.sha256(path.read_bytes()).hexdigest() != release['sha256']:
            raise ValueError('Release receipt digest mismatch')
        m = check_bundle(path, trusted, profiles, receipt['projectId'])
        if (m['releaseSequence'] != receipt['sequence'] or m['sourceCommit'] != receipt['sourceCommit']
                or m['abis'] != [release['abi']] or release['abi'] in abis):
            raise ValueError('Release receipt does not match signed metadata')
        abis.add(release['abi'])
        target = directory / name
        if target.exists() and target.read_bytes() != path.read_bytes():
            raise ValueError('Refusing to overwrite an immutable package')
        incoming.append((path, target))
    if not incoming or (old and not {r['abi'] for r in old['releases']} <= abis):
        raise ValueError('Release must preserve every previously published ABI')
    spec = {'name': catalog['name'], 'apps': []}
    for app in catalog['apps']:
        if app['projectId'] == receipt['projectId']:
            continue
        paths = []
        for release in app['releases']:
            path = (directory / safe_path(release['url'])).resolve()
            if not path.is_relative_to(directory):
                raise ValueError('Catalog package path leaves the distribution directory')
            if hashlib.sha256(path.read_bytes()).hexdigest() != release['sha256']:
                raise ValueError('Existing catalog digest mismatch')
            check_bundle(path, trusted, profiles, app['projectId'])
            paths.append(release['url'])
        spec['apps'].append({'name': app['name'], 'description': app['description'], 'packages': paths})
    spec['apps'].append({'name': receipt['name'], 'description': receipt['description'],
                         'packages': [target.name for _, target in incoming]})
    # Validate everything before adding files. Catalog publication itself is atomic.
    for source, target in incoming:
        if not target.exists():
            with tempfile.NamedTemporaryFile(dir=directory, delete=False) as output:
                temporary = Path(output.name)
                try:
                    with source.open('rb') as content:
                        shutil.copyfileobj(content, output)
                    output.flush()
                    os.fsync(output.fileno())
                    os.link(temporary, target)
                finally:
                    temporary.unlink(missing_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        specification = Path(tmp) / 'spec.json'
        specification.write_text(json.dumps(spec, ensure_ascii=False))
        subprocess.run([sys.executable, str(ROOT / 'tools/build-catalog.py'), '--spec', str(specification),
                        '--output', str(destination), '--key', str(key_path)], check=True)
    result = read_catalog(destination, trusted)
    print(json.dumps({'catalog': str(destination), 'apps': len(result['apps']),
                      'expiresAt': result['expiresAt'],
                      'renewWithinDays': round((result['expiresAt'] - time.time()) / 86400, 2),
                      'published': False}, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('release', type=Path)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--key', type=Path, required=True)
    args = parser.parse_args()
    stage(args.release, args.directory, args.key)
