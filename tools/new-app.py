#!/usr/bin/env python3
"""Create a new Flutter Android project with standalone and Uni Frame flavors."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]


def create(directory, name, package, title, source_url=None, framework_repository=None):
    if not re.fullmatch(r'[a-z][a-z0-9]*(?:_[a-z0-9]+)*', name):
        raise ValueError('Use a lower_case Flutter project name')
    if not re.fullmatch(r'[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*){2,}', package):
        raise ValueError('Use a Java package such as dev.example.my_app')
    if package == 'dev.uniapp.frame':
        raise ValueError('The standalone app cannot use the host package')
    if not title.strip() or len(title) > 80:
        raise ValueError('Display name must contain 1–80 characters')
    directory = directory.absolute()
    if directory.exists():
        raise FileExistsError(f'Refusing to replace {directory}')
    if not framework_repository:
        framework_repository = subprocess.check_output(['git', '-C', str(ROOT), 'remote', 'get-url', 'origin'], text=True).strip()
    framework_commit = subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip()
    if subprocess.check_output(['git', '-C', str(ROOT), 'status', '--porcelain']).strip():
        raise ValueError('Commit framework changes before generating an app pinned to this version')
    version = json.loads(subprocess.check_output(['flutter', '--version', '--machine'], text=True))
    if version['frameworkVersion'] != '3.41.6':
        raise ValueError('This host requires Flutter 3.41.6; do not silently upgrade the runtime')
    subprocess.run(['flutter', 'create', '--platforms', 'android', '--android-language', 'kotlin',
                    '--org', package.rsplit('.', 1)[0], '--project-name', name,
                    '--no-pub', str(directory)], check=True)
    # Remove only the activity created in this brand-new directory.
    for file in (directory / 'android/app/src/main/kotlin').rglob('MainActivity.kt'):
        file.unlink()
    for source in (ROOT / 'templates/flutter-android').rglob('*'):
        if not source.is_file():
            continue
        relative = source.relative_to(ROOT / 'templates/flutter-android')
        if source.suffix == '.kt':
            relative = Path('android/app/src/main/kotlin') / package.replace('.', '/') / source.name
        target = directory / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source.read_text().replace('__PACKAGE__', package))
    manifest = directory / 'android/app/src/main/AndroidManifest.xml'
    text = manifest.read_text().replace('${applicationName}', package + '.UniProjectApplication')
    text = text.replace('android:name=".MainActivity"', f'android:name="{package}.MainActivity"')
    text = text.replace(f'android:label="{name}"', f'android:label="{escape(title, {chr(34): "&quot;"})}"')
    text = text.replace('<application', '<uses-permission android:name="android.permission.INTERNET"/>\n    <application', 1)
    manifest.write_text(text)
    (directory / 'uni.project.json').write_text(json.dumps({
        'formatVersion': 1, 'projectId': name.replace('_', '-'), 'name': title,
        'description': '通过 Uni Frame 下载、更新和测试', 'package': package,
        'entryActivity': package + '.MainActivity', 'minAndroidApi': 24,
        'abis': ['arm64-v8a', 'x86_64'],
        'siteUrl': source_url or os.environ.get('UNI_FRAME_SOURCE_URL', ''),
    }, ensure_ascii=False, indent=2) + '\n')
    with (directory / '.gitignore').open('a') as output:
        output.write('\n# External signing material must never enter source control.\nandroid/key.properties\n*.keystore\n*.jks\n*.pem\nreleases/\n')
    (directory / 'AGENTS.md').write_text('''# Android development defaults

Use the `uni-frame-app-development` skill for this app. Uni Frame means the
Flutter/Shadow Android container, not DCloud uni-app. Build only in the cloud.
Read uni.project.json; keep projectId and package stable across updates.
Use Flutter 3.41.6 and the pinned Shadow runtime. Produce both normal APK and
plugin .uaf releases. Keep the business code shared between flavors.
Do not claim hardware or Android-version coverage from a successful build.
Use the configured distribution source; never expose signing keys in source or public files.
Explicit user choices override these defaults.
''')
    subprocess.run(['git', 'init', '-b', 'main', str(directory)], check=True)
    subprocess.run(['git', 'submodule', 'add', framework_repository, 'vendor/uni-frame'], cwd=directory, check=True)
    subprocess.run(['git', 'checkout', '--detach', framework_commit], cwd=directory / 'vendor/uni-frame', check=True)
    subprocess.run(['git', 'add', 'vendor/uni-frame'], cwd=directory, check=True)
    (directory / 'tools').mkdir(exist_ok=True)
    (directory / 'tools/uni-frame.py').write_text('''#!/usr/bin/env python3
from pathlib import Path
import subprocess
import sys
root = Path(__file__).resolve().parents[1]
entry = root / 'vendor/uni-frame/tools/app.py'
if not entry.is_file():
    raise SystemExit('Run git submodule update --init --recursive first')
subprocess.run([sys.executable, str(entry), '--project', str(root), *sys.argv[1:]], check=True)
''')
    print(directory)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--name', required=True)
    parser.add_argument('--package', required=True)
    parser.add_argument('--title', required=True)
    parser.add_argument('--source-url', help='Website or public Drive catalog link; optional during development')
    parser.add_argument('--framework-repository', help='Defaults to this framework checkout origin')
    args = parser.parse_args()
    create(args.directory, args.name, args.package, args.title, args.source_url, args.framework_repository)
