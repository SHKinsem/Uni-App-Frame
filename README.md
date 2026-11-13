# Uni Frame

A preview Android container for trusted, signed Flutter modules. One catalog
link lists multiple apps. Sources can use ordinary HTTPS hosting or a public
Google Drive catalog file. Business apps remain in separate repositories.

This framework repository contains no personal app implementations, deployment
URLs, account IDs, publisher keys, production APKs or historical Git commits.
Test archives contain synthetic bytes, not an executable business application.

## Repository relationship

Maintain one framework repository. Business applications reference an exact
framework commit as the `vendor/uni-frame` Git submodule; they do not copy or
fork the framework implementation. Shared Android adapters and build conventions
live in `integrations/android`. See [app integration](docs/app-repositories.md).

## Build prerequisites

Use a cloud Linux build machine with Flutter 3.41.6 / Dart 3.11.4, JDK 21,
Android SDK 36, build-tools 36.0.0 and NDK 28.2.13676358. Host settings pin
AGP 8.11.1, Kotlin 2.2.20 and Gradle 8.14. Python dependencies are in
requirements.txt. Set ANDROID_HOME, ANDROID_SDK_ROOT and the usual SDK paths.

Build Tencent Shadow from commit b2a7a7487d8dddd26e2e22e990965ea3805080cf
in a separate checkout using JDK 17, `CI=false`, and
`DISABLE_TENCENT_MAVEN_MIRROR=1`; run `./gradlew publishToMavenLocal`.
The expected Maven version is local-b2a7a74-SNAPSHOT. Use the same Maven
repository when building apps and host. Return to JDK 21 for this project.

Generate your own publisher key and runtime profiles; paths below are examples:

```bash
python3 tools/prepare-runtime.py \
  --flutter-sdk /opt/flutter-3.41.6 \
  --shadow-maven-repo /opt/maven-repository \
  --llvm-strip /opt/android-sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip \
  --publisher-key /secure/uni-frame/publisher.pem --create-key
export UNI_FRAME_DEBUG_KEYSTORE=/secure/uni-frame/test.keystore
cd android
./gradlew :host:testReleaseUnitTest :host:assembleRelease
```

The test keystore must be created separately, using Android's standard debug
alias/password for the preview build. Production signing needs your own signing
configuration. Keep keystores and publisher private keys outside repositories.
Generated public trust resources are ignored by Git. Never rotate an existing
publisher key without planning migration of installed hosts.

## Create and publish an app

```bash
python3 tools/new-app.py /work/my_app \
  --name my_app --package dev.example.my_app --title 'My App'
```

The generator initializes an app Git repository and a framework submodule pinned
to the current framework commit. The framework must be committed and available
at its origin (or supply --framework-repository). The template generates a generic
Flutter counter. Replace it with your own
business UI and tests. Commit source and pubspec.lock in that app's repository.
Configure its website/Drive source with --source-url or UNI_FRAME_SOURCE_URL;
there is no built-in deployment or default account in this framework.

Use release-app.py to produce normal APKs and signed plugin bundles. Use
stage-app.py to merge releases into a complete local distribution directory.
All signing commands require --key. A plugin APK is an intermediate artifact,
not an APK to install over the host.

For Drive, create a dedicated folder and set it to anyone-with-link / viewer.
Provide a short-lived OAuth token through GOOGLE_DRIVE_ACCESS_TOKEN, then run:

```bash
python3 tools/publish-drive.py --directory /work/distribution \
  --folder YOUR_DISTRIBUTION_FOLDER_ID --key /secure/uni-frame/publisher.pem
```

The publisher preserves the catalog's Drive file ID and prints a single share
link for users to paste into Uni Frame. Internal folder/file IDs are publisher
configuration; app users never need to enter them separately. Serialize
publications, preserve the complete local distribution, and keep immutable
release files. Uploaded app modules are verified by publisher signature and
digest before installation. Drive may show a browser confirmation for host APK
downloads; the host does not bypass it or silently install updates.

## Preview limitations

- Minimum declared Android API 24; ARM64 and x86_64 runtime profiles only.
- One module runs at a time. Project Android services are not supported yet.
- Trusted publisher model; this is not an isolation sandbox for arbitrary code.
- Catalogs and bundles expire. Renew signed metadata before expiry; there is
  no automatic renewal scheduler in this source distribution.
- Build and protocol tests do not establish real-device, BLE or Garmin coverage.
- Google Drive availability and download limits remain provider constraints.

Choose an open-source license and review third-party notices before publishing.
No license choice is imposed by this export.
