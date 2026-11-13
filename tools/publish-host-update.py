#!/usr/bin/env python3
"""Publish an immutable APK and an atomic, signed host-update.json beside catalog.json."""
import argparse, base64, hashlib, json, os, re, shutil, subprocess, tempfile, time
import xml.etree.ElementTree as ET
from pathlib import Path
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--apk', type=Path, required=True)
p.add_argument('--previous-apk', type=Path, required=True)
p.add_argument('--directory', type=Path, required=True)
p.add_argument('--notes', required=True)
p.add_argument('--key', type=Path, required=True)
a = p.parse_args()
sdk = Path(os.environ['ANDROID_HOME'])
def inspect(apk):
    manifest = ET.fromstring(subprocess.check_output([str(sdk/'cmdline-tools/latest/bin/apkanalyzer'), 'manifest', 'print', str(apk)], text=True))
    signing = subprocess.check_output([str(sdk/'build-tools/36.0.0/apksigner'), 'verify', '--print-certs', str(apk)], text=True)
    certificates = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)', signing)
    assert certificates, 'APK signature not found'
    attr = '{http://schemas.android.com/apk/res/android}'
    return dict(packageName=manifest.get('package'), versionCode=int(manifest.get(attr+'versionCode')),
                versionName=manifest.get(attr+'versionName'), minAndroidApi=int(manifest.find('uses-sdk').get(attr+'minSdkVersion')),
                certificates=certificates)
new, old = inspect(a.apk), inspect(a.previous_apk)
assert new['packageName'] == old['packageName'] == 'dev.uniapp.frame', 'Package changed'
assert new['certificates'] == old['certificates'], 'Signing identity changed'
assert new['versionCode'] > old['versionCode'], 'Version code must increase'
filename = f"uni-frame-{new['versionCode']}.apk"
a.directory.mkdir(parents=True, exist_ok=True)
destination = a.directory/filename
if destination.exists():
    assert destination.read_bytes() == a.apk.read_bytes(), 'Immutable APK path already used'
else:
    with destination.open('xb') as output, a.apk.open('rb') as source: shutil.copyfileobj(source, output)
payload = dict(formatVersion=1, type='android-host-update', packageName=new['packageName'],
               versionCode=new['versionCode'], versionName=new['versionName'], minAndroidApi=new['minAndroidApi'],
               expiresAt=int(time.time())+30*86400, apkUrl=filename, notes=a.notes,
               apkSha256=hashlib.sha256(destination.read_bytes()).hexdigest())
raw = json.dumps(payload, ensure_ascii=False, separators=(',', ':')).encode()
key = serialization.load_pem_private_key(a.key.read_bytes(), password=None)
sig = key.sign(raw, padding.PKCS1v15(), hashes.SHA256())
envelope = dict(algorithm='RS256', payload=base64.b64encode(raw).decode(), signature=base64.b64encode(sig).decode())
with tempfile.NamedTemporaryFile(mode='w', dir=a.directory, delete=False) as staging:
    temp = Path(staging.name)
    try:
        staging.write(json.dumps(envelope, indent=2)+'\n'); staging.flush(); os.fsync(staging.fileno())
        os.replace(temp, a.directory/'host-update.json')
    finally: temp.unlink(missing_ok=True)
print(json.dumps(payload, ensure_ascii=False, indent=2))
