# Publishing to Google Drive

Goal: anyone who clones this repository can publish to their own Google Drive and
share **one link**, without generating access tokens by hand. Sign-in happens once
with a short code typed on any phone or browser, so it works the same on a laptop,
CI, or an ephemeral cloud session.

## Every publisher (two commands, once)

```bash
python3 tools/drive.py login                 # shows a code; open google.com/device and enter it
python3 tools/drive.py init --name 'My apps' # creates a public (view-only) distribution folder
```

Then publish whenever a distribution changes:

```bash
python3 tools/publish-drive.py --directory /work/distribution --key /secure/publisher.pem
```

It prints the catalog link. Users paste that link into Uni Frame once; later
publishes overwrite the same catalog file, so the link never changes.
Running it again without changes just renews the catalog.

Credentials are stored owner-only in `~/.config/uni-frame/drive-credentials.json`
(`UNI_FRAME_CONFIG_DIR` overrides the folder). `tools/drive.py status` shows the
folder and the catalog link; `tools/drive.py logout` removes the local credentials.

### Cloud and CI sessions

Ephemeral machines lose the credential file. `login` and `init` print two values;
store them in the environment's secret settings instead:

| Variable | Meaning |
| --- | --- |
| `UNI_FRAME_DRIVE_REFRESH_TOKEN` | Long-lived sign-in; access tokens are minted per run |
| `UNI_FRAME_DRIVE_FOLDER` | Distribution folder ID (not secret) |

`GOOGLE_DRIVE_ACCESS_TOKEN` still works and takes precedence, for callers that
already have a short-lived token.

## What the publisher can touch

The sign-in uses the `drive.file` scope: the tools can only see and change files
they created themselves (the distribution folder and its contents). They cannot
read the rest of your Drive, such as a key backup. Publishing also refuses to run
when the local distribution or the Drive folder contains private key material
(`.pem`, `.keystore`, `.jks`, `.p12`, signing properties), or when `--key` lives
inside the distribution directory.

Keep signing keys out of any link-shared folder. The distribution folder is public
by design; a key backup must be restricted to its owner.

## Expiry

* The catalog's freshness is independent of module expiry (default 90 days,
  `--catalog-valid-days`, max 365). An expired module no longer takes the whole
  library offline: the catalog keeps refreshing, and only that one app refuses new
  installs until a newer release is published. Publishing warns about it.
* Module bundles default to 90 days (`release-app.py --valid-days`).
* Installed apps keep working offline after expiry.

## Framework maintainer: the OAuth client (once per framework, not per publisher)

`uni_frame/drive_client.json` holds the framework-wide OAuth client. For installed
apps Google does not treat this client secret as confidential, so it is committed.
A fork may use its own client through `UNI_FRAME_DRIVE_CLIENT_ID` and
`UNI_FRAME_DRIVE_CLIENT_SECRET`.

To create it in the Google Cloud console:

1. Create a project and enable **Google Drive API**.
2. **OAuth consent screen**: user type *External*, set an app name and support
   email, add only the scope `.../auth/drive.file`. Then **Publish app** to
   *In production*. (In *Testing* status Google expires refresh tokens after 7
   days. `drive.file` is a non-sensitive scope, so no verification review is needed.)
3. **Credentials → Create OAuth client ID**, type **TVs and Limited Input devices**.
4. Put the client ID and secret into `uni_frame/drive_client.json` and commit.

## Migrating a folder created with another tool

Because of `drive.file`, folders and catalogs created by other clients (for example
with an OAuth Playground token) are invisible to these tools. Run `tools/drive.py
init` and publish the full distribution there once; users paste the new catalog
link one time, after which it stays stable.
