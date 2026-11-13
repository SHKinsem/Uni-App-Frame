#!/usr/bin/env python3
"""Build a signed catalog from named, already signed releases. Paths are relative to the output directory."""
import argparse, base64, hashlib, json, os, tempfile, time, zipfile
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--spec', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--key', type=Path, required=True)
a = p.parse_args()
key = serialization.load_pem_private_key(a.key.read_bytes(), password=None)
spec = json.loads(a.spec.read_text())
apps = []
expirations = [int(time.time()) + 86400 * 7]
for item in spec['apps']:
    releases = []
    project = None
    for path in item['packages']:
        file = (a.output.parent / path).resolve()
        assert file.is_relative_to(a.output.parent.resolve()), 'Package must reside in distribution directory'
        with zipfile.ZipFile(file) as z:
            raw = z.read('manifest.json')
            envelope = json.loads(z.read('signature.json'))
            assert envelope['algorithm'] == 'RS256'
            key.public_key().verify(base64.b64decode(envelope['signature']), raw, padding.PKCS1v15(), hashes.SHA256())
            m = json.loads(raw)
        assert m['expiresAt'] > time.time(), 'Expired package'
        expirations.append(m['expiresAt'])
        assert project is None or project == m['projectId'], 'Mixed project identity'
        project = m['projectId']
        releases.append(dict(abi=m['abis'][0], sequence=m['releaseSequence'], version=m['buildId'],
                             minAndroidApi=m['minAndroidApi'], runtimeFingerprint=m['runtimeFingerprint'],
                             sha256=hashlib.sha256(file.read_bytes()).hexdigest(), url=path))
    assert releases and len({r['abi'] for r in releases}) == len(releases)
    apps.append(dict(projectId=project, name=item['name'], description=item['description'], releases=releases))
assert len({item['projectId'] for item in apps}) == len(apps)
payload = json.dumps(dict(formatVersion=1, name=spec['name'], expiresAt=min(expirations), apps=apps),
                     ensure_ascii=False, separators=(',', ':')).encode()
signature = key.sign(payload, padding.PKCS1v15(), hashes.SHA256())
encoded = json.dumps(dict(algorithm='RS256', payload=base64.b64encode(payload).decode(),
                         signature=base64.b64encode(signature).decode()), indent=2)+'\n'
with tempfile.NamedTemporaryFile(mode='w', dir=a.output.parent, delete=False) as staging:
    temporary = Path(staging.name)
    try:
        staging.write(encoded); staging.flush(); os.fsync(staging.fileno())
        os.replace(temporary, a.output)
    finally:
        temporary.unlink(missing_ok=True)
print(f'Wrote catalog with {len(apps)} applications: {a.output}')
