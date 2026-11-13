---
name: uni-frame-app-development
description: Develop Android apps with cloud Flutter builds, standalone APKs and signed Uni Frame modules; publish through standard HTTPS hosting or Google Drive. Respect explicit alternative stack choices.
---

# Uni Frame application workflow

Read README.md and the current project's AGENTS.md. Locate the framework with
UNI_FRAME_ROOT or its tools/new-app.py script. Uni Frame is this Flutter/Shadow
container, not DCloud uni-app. Compile in the cloud, not the user's local PC.

1. Apps reference an exact framework commit through vendor/uni-frame.
   Read docs/app-repositories.md and docs/getting-started.md; use the shared
   Android adapters and tools/app.py. Keep app-specific code in the app repository.
2. Use Flutter 3.41.6 and the pinned Shadow runtime. Run tools/new-app.py for new
   apps; evaluate existing apps before adapting their platform code.
3. Implement user requirements and a clear UI. Share Dart code between both
   flavors, lock dependencies, test meaningful behavior, and commit source.
4. Use tools/release-app.py with an increasing sequence and external publisher
   key. Preserve package identity and standalone APK signing across updates.
   Before moving builders or rebuilding a released client, read docs/signing.md.
   Restore existing keys; do not generate new signing identities for an update.
5. Verify independent APK startup and Uni Frame HTTPS download/startup. Exercise
   update/data persistence. Record only actual results; distinguish hardware,
   emulator and static verification. Do not inject bundles by ADB as download proof.
6. Stage a complete distribution using tools/stage-app.py. Publish with ordinary
   HTTPS upload tools or tools/publish-drive.py and the user's OAuth credentials.
   The user pastes one catalog link to browse the published apps.
7. Keep publisher keys and OAuth tokens in secret storage. Preserve catalog IDs,
   other apps and all published ABIs. Serialize publishing and reject downgrades.
8. Deliver APK/source links, tested platforms and metadata expiry dates. Host
   updates use Android's normal browser/download/installation confirmation.

Never imply that declared API compatibility or successful compilation proves
physical-device or peripheral compatibility. Honor explicit user requirements.
