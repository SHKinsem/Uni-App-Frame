#!/usr/bin/env python3
"""Cloud-only release gate: tests, normal/plugin builds, signed immutable bundles."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools'))
from audit_apk import audit


def run(*args, cwd):
    subprocess.run([str(a) for a in args], cwd=cwd, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('project', type=Path)
    parser.add_argument('--sequence', type=int, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--key', type=Path, required=True)
    parser.add_argument('--valid-days', type=int, default=30)
    args = parser.parse_args()
    project, output = args.project.resolve(), args.output.resolve()
    config = json.loads((project / 'uni.project.json').read_text())
    if config.get('formatVersion') != 1 or not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}', config['projectId']):
        parser.error('Invalid project configuration or internal project ID')
    if args.sequence < 1 or not 1 <= args.valid_days <= 365:
        parser.error('sequence must be positive; valid-days must be 1–365')
    if output.exists() or output.is_relative_to(project):
        parser.error('output must be a new directory outside the source project')
    if not config['abis'] or not set(config['abis']) <= {'arm64-v8a', 'x86_64'}:
        parser.error('Only arm64-v8a and x86_64 are supported by this host')
    if subprocess.check_output(['git', 'status', '--porcelain'], cwd=project).strip():
        parser.error('Commit project source and pubspec.lock before building')
    version = json.loads(subprocess.check_output(['flutter', '--version', '--machine'], text=True))
    if version['frameworkVersion'] != '3.41.6':
        parser.error('Host runtime is pinned to Flutter 3.41.6')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=project, text=True).strip()
    lock = (project / 'pubspec.lock').read_bytes()
    run('flutter', 'pub', 'get', '--enforce-lockfile', cwd=project)
    run('flutter', 'analyze', cwd=project)
    run('flutter', 'test', cwd=project)
    output.mkdir(parents=True)
    releases = []
    for abi in config['abis']:
        platform = {'arm64-v8a': 'android-arm64', 'x86_64': 'android-x64'}[abi]
        apk_paths = {}
        for flavor in ('normal', 'plugin'):
            # Normal pub resolution is needed to remove test-only native registrants.
            # Do not use --no-pub after analyze/test. Verify the lock after each build.
            run('flutter', 'build', 'apk', '--release', '--flavor', flavor,
                '--target-platform', platform, '--build-number', args.sequence, cwd=project)
            if (project / 'pubspec.lock').read_bytes() != lock:
                raise ValueError('Dependency lock changed during release')
            apk = output / f"{config['projectId']}-{abi}-{args.sequence}-{flavor}.apk"
            shutil.copyfile(project / f'build/app/outputs/flutter-apk/app-{flavor}-release.apk', apk)
            sdk = Path(os.environ['ANDROID_HOME'])
            analyzer = sdk / 'cmdline-tools/latest/bin/apkanalyzer'
            manifest = ET.fromstring(subprocess.check_output([str(analyzer), 'manifest', 'print', str(apk)]))
            expected = config['package'] if flavor == 'normal' else 'dev.uniapp.frame'
            if manifest.get('package') != expected:
                raise ValueError('Unexpected Android package identity')
            android = '{http://schemas.android.com/apk/res/android}'
            actual_min = int(manifest.find('uses-sdk').get(android + 'minSdkVersion'))
            if actual_min != config['minAndroidApi']:
                raise ValueError('uni.project.json minAndroidApi differs from merged manifest')
            if flavor == 'plugin' and manifest.findall('.//service'):
                raise ValueError('Host P1 does not support project services; adapt host before publishing')
            report = audit(apk)
            (output / (apk.name + '.audit.json')).write_text(json.dumps(report, indent=2) + '\n')
            apk_paths[flavor] = apk
        with zipfile.ZipFile(apk_paths['normal']) as normal, zipfile.ZipFile(apk_paths['plugin']) as plugin:
            if normal.read(f'lib/{abi}/libapp.so') != plugin.read(f'lib/{abi}/libapp.so'):
                raise ValueError('Business Dart AOT differs between normal and plugin flavors')
        bundle = output / f"{config['projectId']}-{abi}-{args.sequence}.uaf"
        run(sys.executable, ROOT / 'tools/bundle-project.py', '--apk', apk_paths['plugin'],
            '--repository', project, '--project', config['projectId'], '--entry', config['entryActivity'],
            '--sequence', args.sequence, '--abi', abi, '--output', bundle, '--key', args.key,
            '--valid-days', args.valid_days, '--min-api', config['minAndroidApi'], cwd=project)
        releases.append({'abi': abi, 'package': bundle.name,
                         'sha256': hashlib.sha256(bundle.read_bytes()).hexdigest(),
                         'standaloneApk': apk_paths['normal'].name})
    receipt = {'formatVersion': 1, 'projectId': config['projectId'], 'name': config['name'],
               'description': config['description'], 'sourceCommit': commit,
               'sequence': args.sequence, 'releases': releases,
               'validation': {'analyze': 'passed', 'tests': 'passed', 'build': 'passed',
                              'runtime': 'not-run', 'physicalDevice': 'not-run'}}
    (output / 'release.json').write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + '\n')
    print(f'Release ready for runtime verification and Site staging: {output}')


if __name__ == '__main__':
    main()
