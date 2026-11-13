#!/usr/bin/env python3
"""Package an already built Shadow APK for the pinned P1 host."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
from cryptography.hazmat.primitives import serialization

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from uni_frame.package import pack

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--apk', type=Path, required=True)
p.add_argument('--repository', type=Path, required=True)
p.add_argument('--project', required=True)
p.add_argument('--entry', required=True)
p.add_argument('--sequence', type=int, required=True)
p.add_argument('--abi', choices=['arm64-v8a','x86_64'], required=True)
p.add_argument('--output', type=Path, required=True)
p.add_argument('--key', type=Path, required=True)
p.add_argument('--valid-days', type=int, default=7)
p.add_argument('--min-api', type=int, default=24)
args = p.parse_args()
if not 1 <= args.valid_days <= 365 or args.min_api < 24:
    p.error('valid-days must be 1–365 and min-api must be at least 24')
profiles = json.loads((ROOT / 'android/host/src/main/res/raw/runtime_profiles.json').read_text())
profile = profiles[args.abi]
commit = subprocess.check_output(['git','-C',str(args.repository),'rev-parse','HEAD'], text=True).strip()
subprocess.run(['git','-C',str(args.repository),'diff','--exit-code','HEAD'],check=True,stdout=subprocess.DEVNULL)
if subprocess.check_output(['git','-C',str(args.repository),'status','--porcelain'], text=True).strip():
    raise ValueError('Commit tracked and untracked project source before packaging')
with zipfile.ZipFile(args.apk) as archive:
    assert hashlib.sha256(archive.read(f'lib/{args.abi}/libflutter.so')).hexdigest() == profile['engineSha256'], 'Engine differs from frozen host profile'
    assert f'lib/{args.abi}/libapp.so' in archive.namelist()
metadata = {'formatVersion':1,'projectId':args.project,'buildId':f'{commit[:12]}-{args.sequence}',
            'sourceCommit':commit,'releaseSequence':args.sequence,'expiresAt':int(time.time())+args.valid_days*86400,
            'channel':'dev','hostApi':1,'minAndroidApi':args.min_api,'abis':[args.abi],
            'runtimeFingerprint':profile['fingerprint'],'requiredCapabilities':['storage.project.v1'],
            'entryActivity':args.entry,'dataSchemaVersion':1}
key = serialization.load_pem_private_key(args.key.read_bytes(),password=None)
trusted = serialization.load_der_public_key((ROOT / 'android/host/src/main/res/raw/publisher.der').read_bytes())
if key.public_key().public_numbers() != trusted.public_numbers():
    raise ValueError('Signing key does not match the host publisher key')
args.output.parent.mkdir(parents=True,exist_ok=True)
with tempfile.TemporaryDirectory() as directory:
    shutil.copyfile(args.apk,Path(directory)/'project.apk')
    pack(directory,metadata,key,args.output)
print(json.dumps({'output':str(args.output),'sourceCommit':commit,'sha256':hashlib.sha256(args.output.read_bytes()).hexdigest()},indent=2))
