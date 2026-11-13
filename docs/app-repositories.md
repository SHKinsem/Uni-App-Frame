# One framework, separate applications

Maintain this framework in one repository. Each business application owns its
source, package identity, release sequence, signing configuration and deployment
configuration in its own repository. The framework does not list downstream
applications or store their metadata.

Applications reference the framework as a Git submodule at `vendor/uni-frame`.
The parent repository records an exact commit, so publishing a framework change
never silently changes a business application's next build.

Clone an application with `git clone --recurse-submodules`, or initialize an
existing checkout with `git submodule update --init --recursive`.

## Android integration

The app's Android settings load plugin versions from
`vendor/uni-frame/integrations/android/toolchain.properties`. After applying the
Android, Shadow, Kotlin and Flutter plugins, its app Gradle file sets
`extra["uniFrameRoot"]` and applies `integrations/android/app.gradle`.

That shared script registers the common Kotlin source directory, the plugin
flavor's host package ID, resource package ID and compile-only Shadow runtime.
Application-specific Android classes are small wrappers extending
`UniFrameActivity` and `UniFrameApplication`. Context/asset fixes live in the
framework, not in copied business-project code. Keep permissions, standalone
package names, data migration, signing and project-specific Gradle logic in the app.

## App commands

`uni.project.json` belongs at the application repository root. Set
`flutterDirectory` when the Flutter application lives in a subdirectory.
Optional `checks` entries contain a repository-relative `directory` and an argv
array `command`; these run in addition to Flutter analyze/test before release.

```bash
python3 tools/uni-frame.py check
python3 tools/uni-frame.py release --sequence 4 \
  --output /work/releases/my-app-4 --key /secure/publisher.pem
```

The app entry point delegates to the pinned framework's scripts. The module
release sequence is independent of the standalone APK version in pubspec.yaml;
update the APK version there when releasing a new independently installed app.

Set `UNI_FRAME_RUNTIME_DIR` to an external folder containing the publisher's
`publisher.der` and `runtime_profiles.json`, or generate those ignored resources
inside the framework checkout. `UNI_FRAME_SOURCE_URL` can provide a deployment
default during project generation. Never commit private keys or access tokens.

## Explicit upgrades

Fetch the desired framework release inside the submodule, check out its exact
commit, run app tests and both build flavors, then commit the submodule pointer
in the app repository. Roll out framework changes to each app deliberately.
The framework must never depend on, clone, test against or package a personal
business repository as part of its own public build.
