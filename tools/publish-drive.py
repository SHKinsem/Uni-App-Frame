#!/usr/bin/env python3
"""Publish signed Uni Frame releases using the standard Google Drive REST API.

Any agent/CI can run this with its own OAuth token; no GPT or Sites API is used.
The input is a complete local distribution, prepared with stage-app.py.
"""
import argparse
import base64
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.parse
import urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from uni_frame.package import safe_path, verify
from uni_frame.config import runtime_resources


def link(file_id):
    if not re.fullmatch(r'[A-Za-z0-9_-]{10,128}', file_id):
        raise ValueError('Invalid Drive file ID')
    return f'https://drive.google.com/file/d/{file_id}/view'


def read_signed(raw, public):
    envelope = json.loads(raw)
    if envelope['algorithm'] != 'RS256':
        raise ValueError('Unsupported signature')
    payload = base64.b64decode(envelope['payload'], validate=True)
    public.verify(base64.b64decode(envelope['signature'], validate=True), payload,
                  padding.PKCS1v15(), hashes.SHA256())
    return json.loads(payload)


def signed(payload, key):
    raw = json.dumps(payload, ensure_ascii=False, separators=(',', ':')).encode()
    return (json.dumps(dict(algorithm='RS256', payload=base64.b64encode(raw).decode(),
            signature=base64.b64encode(key.sign(raw, padding.PKCS1v15(), hashes.SHA256())).decode()),
            indent=2) + '\n').encode()


def local_file(directory, name):
    path = (directory / safe_path(name)).resolve()
    if not path.is_relative_to(directory.resolve()) or not path.is_file():
        raise ValueError(f'Missing or unsafe distribution file: {name}')
    return path


def load_distribution(directory, key):
    trusted = serialization.load_der_public_key((runtime_resources(ROOT) / 'publisher.der').read_bytes())
    if trusted.public_numbers() != key.public_key().public_numbers():
        raise ValueError('Publisher key differs from the installed host trust')
    catalog = read_signed((directory / 'catalog.json').read_bytes(), trusted)
    profiles = json.loads((runtime_resources(ROOT) / 'runtime_profiles.json').read_text())
    now = int(time.time())
    if catalog['formatVersion'] != 1 or catalog['expiresAt'] <= now:
        raise ValueError('Unsupported or expired local catalog; renew before publishing')
    files = set()
    for app in catalog['apps']:
        for release in app['releases']:
            path = local_file(directory, release['url'])
            if hashlib.sha256(path.read_bytes()).hexdigest() != release['sha256']:
                raise ValueError('Catalog package digest mismatch')
            abi = release['abi']
            manifest = verify(path, trusted, dict(androidApi=36, abi=abi, hostApi=1,
                runtimeFingerprint=profiles[abi]['fingerprint'], capabilities=[
                    'storage.project.v1', 'ble.scan.v1', 'ble.connect.v1', 'garmin.connectiq.v1']),
                project=app['projectId'], channel='dev', highest_sequence=0)
            if manifest['releaseSequence'] != release['sequence']:
                raise ValueError('Catalog sequence differs from signed package')
            catalog['expiresAt'] = min(catalog['expiresAt'], manifest['expiresAt'])
            files.add(release['url'])
    update = None
    if (directory / 'host-update.json').exists():
        update = read_signed((directory / 'host-update.json').read_bytes(), trusted)
        if (update['type'] != 'android-host-update' or update['packageName'] != 'dev.uniapp.frame'
                or update['expiresAt'] <= now):
            raise ValueError('Invalid or expired host update')
        path = local_file(directory, update['apkUrl'])
        if hashlib.sha256(path.read_bytes()).hexdigest() != update['apkSha256']:
            raise ValueError('Host APK digest mismatch')
        files.add(update['apkUrl'])
    return catalog, update, sorted(files)


def preserve_releases(previous, candidate):
    incoming = {app['projectId']: app for app in candidate['apps']}
    for app in previous['apps']:
        if app['projectId'] not in incoming:
            raise ValueError('Publication would remove an existing app; use the complete distribution mirror')
        releases = {r['abi']: r for r in incoming[app['projectId']]['releases']}
        for old in app['releases']:
            new = releases.get(old['abi'])
            if (new is None or new['sequence'] < old['sequence']
                    or (new['sequence'] == old['sequence'] and new['sha256'] != old['sha256'])):
                raise ValueError('Publication would remove an ABI, downgrade, or replace an immutable release')


def rewrite(catalog, update, mapping, key):
    catalog = copy.deepcopy(catalog)
    for app in catalog['apps']:
        for release in app['releases']:
            release['url'] = link(mapping[release['url']])
    encoded_update = None
    if update:
        update = copy.deepcopy(update)
        update['apkUrl'] = link(mapping[update['apkUrl']])
        encoded_update = signed(update, key)
        catalog['hostUpdateUrl'] = link(mapping['host-update.json'])
    else:
        catalog.pop('hostUpdateUrl', None)
    return signed(catalog, key), encoded_update


class Drive:
    def __init__(self, token):
        self.token = token

    def request(self, url, method='GET', data=None, headers=None):
        parsed = urllib.parse.urlparse(url)
        if parsed.scheme != 'https' or parsed.hostname not in ('www.googleapis.com', 'content.googleapis.com'):
            raise ValueError('Unexpected Drive API upload endpoint')
        request = urllib.request.Request(url, data=data, method=method,
            headers={'Authorization': 'Bearer ' + self.token, **(headers or {})})
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                raise ValueError('Unexpected Drive API redirect; credentials were not forwarded')
        return urllib.request.build_opener(NoRedirect).open(request, timeout=120)

    def children(self, folder):
        result, token = {}, None
        while True:
            query = {'q': f"'{folder}' in parents and trashed = false", 'pageSize': 1000,
                     'fields': 'nextPageToken,files(id,name,md5Checksum,size)'}
            if token:
                query['pageToken'] = token
            with self.request('https://www.googleapis.com/drive/v3/files?' + urllib.parse.urlencode(query)) as response:
                page = json.load(response)
            for file in page['files']:
                if file['name'] in result:
                    raise ValueError('Duplicate Drive filename: ' + file['name'])
                result[file['name']] = file
            token = page.get('nextPageToken')
            if not token:
                return result

    def upload(self, folder, name, data, existing=None):
        metadata = {'name': name}
        method, suffix = 'POST', ''
        if existing:
            method, suffix = 'PATCH', '/' + existing
        else:
            metadata['parents'] = [folder]
        url = 'https://www.googleapis.com/upload/drive/v3/files' + suffix + '?uploadType=resumable&fields=id,name,md5Checksum'
        with self.request(url, method, json.dumps(metadata).encode(), {
            'Content-Type': 'application/json; charset=UTF-8',
            'X-Upload-Content-Type': 'application/octet-stream', 'X-Upload-Content-Length': str(len(data))}) as response:
            location = response.headers['Location']
        with self.request(location, 'PUT', data, {'Content-Type': 'application/octet-stream'}) as response:
            result = json.load(response)
        if result['md5Checksum'] != hashlib.md5(data).hexdigest():
            raise ValueError('Drive upload checksum differs')
        return result['id']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--folder', required=True, help='Existing public distribution folder ID (publisher only)')
    parser.add_argument('--key', type=Path, required=True)
    args = parser.parse_args()
    link(args.folder)
    token = os.environ.get('GOOGLE_DRIVE_ACCESS_TOKEN')
    if not token:
        parser.error('Provide GOOGLE_DRIVE_ACCESS_TOKEN through your secret manager, with Drive write access')
    key = serialization.load_pem_private_key(args.key.read_bytes(), password=None)
    catalog, update, files = load_distribution(args.directory, key)
    drive = Drive(token)
    children = drive.children(args.folder)
    if 'catalog.json' in children:
        with drive.request('https://www.googleapis.com/drive/v3/files/' + children['catalog.json']['id'] + '?alt=media') as response:
            previous = read_signed(response.read(), key.public_key())
        preserve_releases(previous, catalog)
    mapping = {name: file['id'] for name, file in children.items()}
    for name in files:
        data = local_file(args.directory, name).read_bytes()
        old = children.get(name)
        if old:
            if old.get('md5Checksum') != hashlib.md5(data).hexdigest():
                raise ValueError('Refusing to overwrite immutable Drive file: ' + name)
        else:
            mapping[name] = drive.upload(args.folder, name, data)
    if update and 'host-update.json' not in mapping:
        # Allocate a stable ID before signing the catalog; catalog is published last.
        mapping['host-update.json'] = drive.upload(args.folder, 'host-update.json', b'{}')
    encoded_catalog, encoded_update = rewrite(catalog, update, mapping, key)
    if encoded_update:
        drive.upload(args.folder, 'host-update.json', encoded_update, mapping['host-update.json'])
    mapping['catalog.json'] = drive.upload(args.folder, 'catalog.json', encoded_catalog, mapping.get('catalog.json'))
    print(json.dumps({'catalogUrl': link(mapping['catalog.json']), 'folderUrl': f'https://drive.google.com/drive/folders/{args.folder}',
                      'expiresAt': catalog['expiresAt'], 'files': mapping}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
