"""Minimal Google Drive v3 client used by the publishing tools (standard library only)."""
import hashlib
import json
import urllib.parse
import urllib.request

FOLDER = 'application/vnd.google-apps.folder'
# Private signing material must never sit next to public distribution files.
SECRET_SUFFIXES = ('.pem', '.key', '.keystore', '.jks', '.p12', '.pfx')
SECRET_NAMES = ('apk-signing.properties', 'key.properties', 'drive-credentials.json')


def looks_secret(name):
    lowered = name.lower()
    return lowered.endswith(SECRET_SUFFIXES) or lowered.rsplit('/', 1)[-1] in SECRET_NAMES


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

    def json(self, url, method='GET', body=None):
        data = None if body is None else json.dumps(body).encode()
        headers = {} if body is None else {'Content-Type': 'application/json; charset=UTF-8'}
        with self.request(url, method, data, headers) as response:
            return json.load(response)

    def metadata(self, file_id, fields='id,name,mimeType,trashed'):
        return self.json(f'https://www.googleapis.com/drive/v3/files/{file_id}?'
                         + urllib.parse.urlencode({'fields': fields}))

    def create_folder(self, name):
        return self.json('https://www.googleapis.com/drive/v3/files?fields=id,name', 'POST',
                         {'name': name, 'mimeType': FOLDER})['id']

    def share_public(self, file_id):
        """Anyone with the link may view; items inside inherit it."""
        self.json(f'https://www.googleapis.com/drive/v3/files/{file_id}/permissions?fields=id', 'POST',
                  {'role': 'reader', 'type': 'anyone', 'allowFileDiscovery': False})

    def children(self, folder):
        result, token = {}, None
        while True:
            query = {'q': f"'{folder}' in parents and trashed = false", 'pageSize': 1000,
                     'fields': 'nextPageToken,files(id,name,md5Checksum,size)'}
            if token:
                query['pageToken'] = token
            page = self.json('https://www.googleapis.com/drive/v3/files?' + urllib.parse.urlencode(query))
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
