"""Long-lived Google Drive publishing credentials without manual access tokens.

The publisher signs in once with Google's device flow (a code entered on any phone or
browser), so it works from laptops, CI and ephemeral cloud sessions alike. Only the
refresh token is kept; access tokens are minted per run. The scope is ``drive.file``:
the publisher can only see and change files it created itself, never the rest of the
user's Drive (for example a key backup folder).

Resolution order for a publish run:
1. GOOGLE_DRIVE_ACCESS_TOKEN (legacy, short-lived token supplied by the caller)
2. UNI_FRAME_DRIVE_REFRESH_TOKEN (secret storage on cloud/CI)
3. the credential file written by ``tools/drive.py login``
"""
import json
import os
from pathlib import Path
import stat
import time
import urllib.error
import urllib.parse
import urllib.request

SCOPE = 'https://www.googleapis.com/auth/drive.file'
DEVICE_ENDPOINT = 'https://oauth2.googleapis.com/device/code'
TOKEN_ENDPOINT = 'https://oauth2.googleapis.com/token'
ROOT = Path(__file__).resolve().parents[1]
CLIENT_FILE = ROOT / 'uni_frame' / 'drive_client.json'
SETUP_HINT = ('No Drive OAuth client configured. Set UNI_FRAME_DRIVE_CLIENT_ID and '
              'UNI_FRAME_DRIVE_CLIENT_SECRET, or fill uni_frame/drive_client.json '
              '(see docs/drive-publishing.md).')


class AuthError(Exception):
    pass


def config_dir(environ=os.environ):
    if environ.get('UNI_FRAME_CONFIG_DIR'):
        return Path(environ['UNI_FRAME_CONFIG_DIR'])
    base = environ.get('XDG_CONFIG_HOME') or (Path.home() / '.config')
    return Path(base) / 'uni-frame'


def credential_path(environ=os.environ):
    return config_dir(environ) / 'drive-credentials.json'


def client(environ=os.environ, client_file=CLIENT_FILE):
    """OAuth client of type 'TVs and Limited Input devices'. Its secret is not confidential
    for installed apps (Google documents this), so a framework-wide default may live in git."""
    client_id = environ.get('UNI_FRAME_DRIVE_CLIENT_ID', '').strip()
    secret = environ.get('UNI_FRAME_DRIVE_CLIENT_SECRET', '').strip()
    if not client_id and client_file.is_file():
        data = json.loads(client_file.read_text())
        client_id, secret = data.get('client_id', '').strip(), data.get('client_secret', '').strip()
    if not client_id or not secret:
        raise AuthError(SETUP_HINT)
    return client_id, secret


def _post(url, fields, opener=None):
    if url not in (DEVICE_ENDPOINT, TOKEN_ENDPOINT):
        raise ValueError('Unexpected OAuth endpoint')
    request = urllib.request.Request(url, data=urllib.parse.urlencode(fields).encode(), method='POST',
                                     headers={'Content-Type': 'application/x-www-form-urlencoded'})
    try:
        with (opener or urllib.request.urlopen)(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        try:
            return json.load(error)
        except ValueError:
            raise AuthError(f'OAuth HTTP {error.code}') from error


def save(record, environ=os.environ):
    path = credential_path(environ)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix('.tmp')
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, stat.S_IRUSR | stat.S_IWUSR)
    with os.fdopen(descriptor, 'w') as output:
        json.dump(record, output, indent=2)
    os.replace(temporary, path)
    return path


def load(environ=os.environ):
    path = credential_path(environ)
    return json.loads(path.read_text()) if path.is_file() else None


def device_login(environ=os.environ, post=_post, sleep=time.sleep, show=print):
    client_id, secret = client(environ)
    start = post(DEVICE_ENDPOINT, {'client_id': client_id, 'scope': SCOPE})
    if 'device_code' not in start:
        raise AuthError('Device sign-in refused: ' + start.get('error', 'unknown'))
    url = start.get('verification_url') or start.get('verification_uri')
    show(f'在任意设备打开 {url} ，输入代码：{start["user_code"]}')
    show(f'Open {url} on any device and enter code: {start["user_code"]}')
    interval, deadline = int(start.get('interval', 5)), time.time() + int(start.get('expires_in', 1800))
    while time.time() < deadline:
        sleep(interval)
        result = post(TOKEN_ENDPOINT, {'client_id': client_id, 'client_secret': secret,
                      'device_code': start['device_code'],
                      'grant_type': 'urn:ietf:params:oauth:grant-type:device_code'})
        error = result.get('error')
        if error == 'authorization_pending':
            continue
        if error == 'slow_down':
            interval += 5
            continue
        if error:
            raise AuthError('Sign-in failed: ' + error)
        if 'refresh_token' not in result:
            raise AuthError('Google returned no refresh token')
        record = {'client_id': client_id, 'refresh_token': result['refresh_token'],
                  'scope': result.get('scope', SCOPE), 'created': int(time.time())}
        return record, result['access_token']
    raise AuthError('Sign-in code expired; run login again')


def refresh_token(environ=os.environ):
    value = environ.get('UNI_FRAME_DRIVE_REFRESH_TOKEN', '').strip()
    if value:
        return value
    record = load(environ)
    return record and record.get('refresh_token')


def access_token(environ=os.environ, post=_post):
    direct = environ.get('GOOGLE_DRIVE_ACCESS_TOKEN', '').strip()
    if direct:
        return direct
    token = refresh_token(environ)
    if not token:
        raise AuthError('Not signed in to Drive. Run: python3 tools/drive.py login')
    client_id, secret = client(environ)
    result = post(TOKEN_ENDPOINT, {'client_id': client_id, 'client_secret': secret,
                  'refresh_token': token, 'grant_type': 'refresh_token'})
    if 'access_token' not in result:
        raise AuthError('Drive sign-in expired or revoked (' + result.get('error', 'unknown')
                        + '). Run: python3 tools/drive.py login')
    return result['access_token']
