<div align="center">

# Uni Frame

**Download, update and run Flutter apps in one Android client.**

Build standalone APKs and signed modules. Publish an app library over HTTPS or Google Drive.

English · [简体中文](README.zh-CN.md)

[Getting started](docs/getting-started.md) · [App integration](docs/app-repositories.md) · [Agent skill](.agents/skills/uni-frame-app-development/SKILL.md)

</div>

## A look inside

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/welcome.png" width="240" alt="Uni Frame before connecting an app library"><br><b>Connect a library</b></td>
    <td align="center"><img src="docs/screenshots/library.png" width="240" alt="A sample app available in the library"><br><b>Choose an app</b></td>
    <td align="center"><img src="docs/screenshots/counter.png" width="240" alt="The Flutter counter template running inside Uni Frame"><br><b>Open it in Uni Frame</b></td>
  </tr>
</table>

Android 14 emulator captures: the 0.4.0 client and the Flutter counter template. The client UI is currently in Chinese. [Capture details](docs/screenshots/README.md).

## What it does

- **One library link.** Use an HTTPS site or a public Google Drive catalog file. Users paste the link; no project IDs to enter.
- **An app library.** Browse available apps, install updates and reopen downloaded apps offline.
- **Two build outputs.** Keep one Flutter codebase and produce both a standalone Android APK and a signed `.uaf` module.
- **Verified releases.** The client checks publisher signatures, file hashes and runtime compatibility before loading a module.
- **Client updates.** Download a newer Uni Frame APK and install it through Android's normal confirmation flow.

Uni Frame uses [Flutter](https://flutter.dev) and [Tencent Shadow](https://github.com/Tencent/Shadow). It runs apps built for its runtime; it cannot import arbitrary APKs.

## Use an app library

1. Install the Uni Frame client supplied by your library's publisher.
2. Choose **连接应用库** and paste the library URL. For Drive, use the catalog file's share link.
3. Install an app from the list, then open it. Refresh the library to check for app updates.

Use **更多选项 → 检查更新** to check for a newer client. The client and its modules must use the same publisher trust and compatible runtime profiles.

## Build an app

Start with the [build setup](docs/getting-started.md), then create a project:

```bash
python3 tools/new-app.py /work/my_app \
  --name my_app --package dev.example.my_app --title 'My App'
```

The generated project contains a Flutter counter, Android build flavors and a framework dependency pinned at `vendor/uni-frame`. Replace the counter with your app, commit its source and lockfile, then build a release:

```bash
cd /work/my_app
python3 tools/uni-frame.py release --sequence 1 \
  --output /work/releases/my-app-1 --key /secure/uni-frame/publisher.pem
```

The release command runs analysis and tests, builds both flavors for each configured ABI, and packages signed modules. [Stage and publish the release](docs/getting-started.md#publish-a-library) to make it available in the client.

Existing projects can use the [Android integration guide](docs/app-repositories.md). Coding agents can follow the included [development skill](.agents/skills/uni-frame-app-development/SKILL.md); publishing uses ordinary HTTPS hosting or the Google Drive API.

## Current support

| Area | Preview support |
| --- | --- |
| Android | Declared minimum: Android 7.0 / API 24; emulator validation on Android 14 |
| Architectures | ARM64 and x86_64 |
| Runtime | Flutter 3.41.6 / Dart 3.11.4, pinned Shadow build |
| App sessions | One running module at a time |
| Distribution | HTTPS and public Google Drive catalog files |
| Project Android services | Not supported yet |

Modules run as trusted publisher code; the host is not a security sandbox for arbitrary apps. Catalogs and bundles expire and need renewal. Device permissions, BLE and other hardware integrations need testing on the target devices. Drive downloads are subject to Google's availability and download limits.

## In this repository

| Path | Contents |
| --- | --- |
| [`android/host`](android/host) | App library UI, module loading and client updates |
| [`integrations/android`](integrations/android) | Shared Android adapters and toolchain settings |
| [`templates/flutter-android`](templates/flutter-android) | App template with standalone and module flavors |
| [`tools`](tools) | Project generation, builds, signing and publishing |
| [`uni_frame`](uni_frame) · [`tests`](tests) | Package format, verification and tests |
| [`docs`](docs) | Setup, app integration and screenshots |
