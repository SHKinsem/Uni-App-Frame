# Build and publish

[English overview](../README.md) · [中文概览](../README.zh-CN.md) · [App integration](app-repositories.md)

This guide sets up a publisher's client and first app release. Run the commands
from the framework checkout on a Linux cloud build machine unless stated otherwise.

## Build prerequisites

| Dependency | Version |
| --- | --- |
| Flutter / Dart | 3.41.6 / 3.11.4 |
| JDK | 21 for Uni Frame; 17 for building Shadow |
| Android SDK / build-tools | 36 / 36.0.0 |
| Android NDK | 28.2.13676358 |
| AGP / Kotlin / Gradle | 8.11.1 / 2.2.20 / 8.14 |

Set `ANDROID_HOME` and `ANDROID_SDK_ROOT` to the SDK directory. Put Flutter,
Java, Android command-line tools and platform-tools on `PATH`, and accept the
SDK licenses. Install the Python dependencies in a virtual environment:

```bash
python3 -m venv /work/uni-frame-venv
source /work/uni-frame-venv/bin/activate
python3 -m pip install -r requirements.txt
flutter precache --android
```

Build Tencent Shadow from commit b2a7a7487d8dddd26e2e22e990965ea3805080cf
in a separate checkout using JDK 17, `CI=false`, and
`DISABLE_TENCENT_MAVEN_MIRROR=1`; run `./gradlew publishToMavenLocal`.
The expected Maven version is local-b2a7a74-SNAPSHOT. Use the same Maven
repository when building apps and host. By default this is `$HOME/.m2/repository`.
Return to JDK 21 for this project.

## Build the client

If you are moving an existing client to another builder, follow
[signing and migration](signing.md) first. Restore its original keys rather than
running the first-time key generation steps below.

Generate your own publisher key and runtime profiles; paths below are examples:

```bash
python3 tools/prepare-runtime.py \
  --flutter-sdk /opt/flutter-3.41.6 \
  --shadow-maven-repo "$HOME/.m2/repository" \
  --llvm-strip /opt/android-sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip \
  --publisher-key /secure/uni-frame/publisher.pem --create-key
export UNI_FRAME_DEBUG_KEYSTORE=/secure/uni-frame/test.keystore
keytool -genkeypair -keystore "$UNI_FRAME_DEBUG_KEYSTORE" \
  -alias androiddebugkey -storepass android -keypass android \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname 'CN=Android Debug,O=Android,C=US'
export UNI_FRAME_RUNTIME_DIR="$PWD/android/host/src/main/res/raw"
(cd android && ./gradlew :host:testReleaseUnitTest :host:assembleRelease)
```

Create the test keystore only once; reuse it for later preview builds. The APK
is written to `android/host/build/outputs/apk/release/host-release.apk`.
The publisher key signs modules and catalogs; the Android keystore signs APKs.
Keep both private keys outside source control. Production APK signing needs your
own signing configuration through `UNI_FRAME_SIGNING_PROPERTIES`; see
[signing and migration](signing.md). Preserve publisher trust and APK signing identity
when updating installed clients.

## Create an app

```bash
python3 tools/new-app.py /work/my_app \
  --name my_app --package dev.example.my_app --title 'My App'
```

The generator initializes an app Git repository and a framework submodule pinned
to the current framework commit. The framework must be committed and available
at its origin (or supply --framework-repository). The template generates a generic
Flutter counter. Replace it with your app and tests. Configure its website or
Drive source with `--source-url` or `UNI_FRAME_SOURCE_URL`.

From the generated app directory, resolve dependencies and commit the project:

```bash
cd /work/my_app
flutter pub get
git add .
git commit -m 'Create app'
python3 tools/uni-frame.py check
python3 tools/uni-frame.py release --sequence 1 \
  --output /work/releases/my-app-1 --key /secure/uni-frame/publisher.pem
```

`check` verifies the framework dependency. `release` runs analysis and tests,
builds standalone and plugin APKs, and signs `.uaf` modules. Keep the source and
`pubspec.lock` committed; use a new output directory and an increasing sequence
for each release. A plugin APK is an intermediate artifact, not an APK to install
over the host. Verify the standalone app and module startup before publishing.

## Publish a library

From the framework checkout, merge the release into a local distribution folder:

```bash
mkdir -p /work/distribution
python3 tools/stage-app.py /work/releases/my-app-1 \
  --directory /work/distribution --key /secure/uni-frame/publisher.pem
```

The folder contains a signed `catalog.json` and the module files it references.
Keep it between releases: staging merges an app update while preserving the
other apps and their supported architectures.

### HTTPS

Upload the distribution folder to an HTTPS host that serves the files without
requiring login. Share the directory URL or the full `catalog.json` URL.

### Google Drive

For Drive, create a dedicated folder and set it to anyone-with-link / viewer.
Provide a short-lived OAuth token through GOOGLE_DRIVE_ACCESS_TOKEN, then run:

```bash
python3 tools/publish-drive.py --directory /work/distribution \
  --folder YOUR_DISTRIBUTION_FOLDER_ID --key /secure/uni-frame/publisher.pem
```

The publisher preserves the catalog's Drive file ID and prints a single share
link for users to paste into Uni Frame. Internal folder/file IDs are publisher
configuration; app users never need to enter them separately. Publish one release
at a time and keep published package files immutable.

### Update the client

Build a newer client with the same Android signing identity and a higher version
code, then prepare its signed update announcement:

```bash
python3 tools/publish-host-update.py \
  --apk /work/client-new.apk --previous-apk /work/client-old.apk \
  --directory /work/distribution --notes 'Describe the changes here.' \
  --key /secure/uni-frame/publisher.pem
```

Publish the updated distribution folder to the same source. The client opens the
APK download link in a browser; Android asks the user to confirm installation.
Drive may also show a download confirmation.

### Renew releases

Catalogs expire after at most seven days; bundles default to thirty days. Publish
a newer app release before its bundle expires and regenerate signed catalog
metadata before the catalog expires. There is no automatic renewal scheduler.
Already installed apps remain available offline.
