#!/usr/bin/env python3
"""Set up Google Drive publishing once; afterwards publish-drive.py needs no manual tokens.

  python3 tools/drive.py login            # sign in once with a code on any phone/browser
  python3 tools/drive.py init --name 'My apps'   # create the public distribution folder
  python3 tools/drive.py status           # show folder and sign-in state
  python3 tools/drive.py logout

Cloud/CI sessions are ephemeral: copy the two values printed by `login`/`init`
(UNI_FRAME_DRIVE_REFRESH_TOKEN, UNI_FRAME_DRIVE_FOLDER) into the environment's secret storage.
"""
import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from uni_frame import drive_auth
from uni_frame.drive_api import Drive


def folder_link(folder):
    return f'https://drive.google.com/drive/folders/{folder}'


def login(args):
    record, _ = drive_auth.device_login()
    previous = drive_auth.load() or {}
    if previous.get('folder'):
        record['folder'] = previous['folder']
    path = drive_auth.save(record)
    print(f'Signed in. Credentials saved to {path} (owner-only).')
    print('For cloud/CI secret storage:')
    print('  UNI_FRAME_DRIVE_REFRESH_TOKEN=' + record['refresh_token'])


def init(args):
    record = drive_auth.load() or {}
    drive = Drive(drive_auth.access_token())
    if record.get('folder') and not args.force:
        print('Already set up: ' + folder_link(record['folder']) + ' (use --force for a new folder)')
        return
    folder = drive.create_folder(args.name)
    drive.share_public(folder)
    if record:
        record['folder'] = folder
        drive_auth.save(record)
    print('Created public distribution folder: ' + folder_link(folder))
    print('Publish with: python3 tools/publish-drive.py --directory DIST --key publisher.pem')
    print('For cloud/CI: UNI_FRAME_DRIVE_FOLDER=' + folder)
    print('Keep signing keys elsewhere; never put them in this folder.')


def status(args):
    record = drive_auth.load() or {}
    folder = record.get('folder') or __import__('os').environ.get('UNI_FRAME_DRIVE_FOLDER')
    signed_in = bool(drive_auth.refresh_token())
    print('Signed in:', 'yes' if signed_in else 'no (run: python3 tools/drive.py login)')
    print('Folder:', folder_link(folder) if folder else 'none (run: python3 tools/drive.py init)')
    if signed_in and folder:
        drive = Drive(drive_auth.access_token())
        files = drive.children(folder)
        catalog = files.get('catalog.json')
        print('Catalog link:', f"https://drive.google.com/file/d/{catalog['id']}/view" if catalog else 'not published yet')


def logout(args):
    path = drive_auth.credential_path()
    path.unlink(missing_ok=True)
    print('Removed local Drive credentials. Revoke access at https://myaccount.google.com/permissions')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('login').set_defaults(run=login)
    create = commands.add_parser('init')
    create.add_argument('--name', default='Uni Frame 应用分发')
    create.add_argument('--force', action='store_true')
    create.set_defaults(run=init)
    commands.add_parser('status').set_defaults(run=status)
    commands.add_parser('logout').set_defaults(run=logout)
    args = parser.parse_args()
    try:
        args.run(args)
    except drive_auth.AuthError as error:
        sys.exit(str(error))


if __name__ == '__main__':
    main()
