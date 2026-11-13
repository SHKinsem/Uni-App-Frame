#!/usr/bin/env python3
"""App-side entry point from a pinned framework submodule."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

FRAMEWORK = Path(__file__).resolve().parents[1]


def inspect(project):
    config = json.loads((project / 'uni.project.json').read_text())
    flutter = (project / config.get('flutterDirectory', '.')).resolve()
    if not flutter.is_relative_to(project) or not (flutter / 'pubspec.yaml').is_file():
        raise ValueError('Flutter application directory is missing or outside the app repository')
    relative = FRAMEWORK.relative_to(project).as_posix()
    record = subprocess.check_output(['git', 'ls-files', '--stage', '--', relative], cwd=project, text=True).strip()
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=FRAMEWORK, text=True).strip()
    if not record.startswith('160000 ') or record.split()[1] != actual:
        raise ValueError('Framework must match the commit pinned by the app submodule')
    if subprocess.check_output(['git', 'status', '--porcelain'], cwd=FRAMEWORK).strip():
        raise ValueError('Framework submodule contains uncommitted changes')
    return {'project': config['projectId'], 'flutterDirectory': str(flutter), 'frameworkCommit': actual}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, default=Path.cwd())
    parser.add_argument('command', choices=['check', 'release'])
    args, remaining = parser.parse_known_args()
    project = args.project.resolve()
    result = inspect(project)
    print(json.dumps(result, indent=2), flush=True)
    if args.command == 'release':
        subprocess.run([sys.executable, str(FRAMEWORK / 'tools/release-app.py'), str(project), *remaining], check=True)
    elif remaining:
        parser.error('Unexpected arguments for check')


if __name__ == '__main__':
    main()
