# Signing and changing build environments

[English overview](../README.md) · [中文说明](#更换云端环境时保留签名)

An Android app's update identity belongs to its signing key, not to its build
machine. Restore the same key on a new builder and the app can still be updated.
A newly generated keystore with the same filename, alias and password is a
different signing identity.

## Keep both identities

| Material | Purpose | What to preserve |
| --- | --- | --- |
| APK keystore | Android installation and replacement | Original private key, certificate, alias and passwords |
| `publisher.pem` | Module, catalog and update-announcement signatures | Original publisher private key |
| `publisher.der` | Publisher public key embedded in the client | Must match `publisher.pem` |
| `runtime_profiles.json` | Runtime compatibility checks | Profiles matching the client and module toolchain |

Store private keys and their passwords in a password manager or private secret
store that is independent of the build machine. Keep an encrypted recovery copy.
Mount or restore the files only on authorized builders. A copy elsewhere on the
same cloud workspace is useful for recovery from accidental edits, but does not
survive loss of that workspace.

Do not commit these secrets or place them in a public app distribution folder.
The APK contains public certificates; it cannot restore a lost private key.

## Configure the host

Create `/secure/uni-frame/apk-signing.properties` outside the checkout, readable
only by the build account:

```properties
storeFile=host.keystore
storePassword=<provided by your secret store>
keyAlias=<existing signing alias>
keyPassword=<provided by your secret store>
certificateSha256=<SHA-256 certificate digest from the previously released APK>
```

Replace the placeholders with the existing identity. `storeFile` is relative to
the properties file, or can be an absolute path. `storeType` is optional. Keep the
properties file private because it contains passwords.

Use the signing certificate digest, not the SHA-256 hash of the entire APK:

```bash
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs /work/previous.apk
export UNI_FRAME_SIGNING_PROPERTIES=/secure/uni-frame/apk-signing.properties
(cd android && ./gradlew :host:testReleaseUnitTest :host:assembleRelease)
```

The build fails if the keystore is missing, the alias has no private key, or its
certificate differs from `certificateSha256`. It does not generate a replacement
key. Reuse the same properties file and keystore on every builder.

Earlier previews used an explicitly supplied Android debug keystore. Keep that
**exact file** when migrating those installations. It can be used through the
properties file above with its existing alias and passwords; changing to the
`publisher` Gradle configuration does not change the certificate. The legacy
`UNI_FRAME_DEBUG_KEYSTORE` option remains available for preview builds and also
requires an existing file.

These settings apply to the Uni Frame host. Standalone app APKs keep their own
signing settings in their app repositories; preserve those identities too.

## Restore a builder

1. Clone the framework and install the pinned toolchain from the
   [build guide](getting-started.md).
2. Restore the original APK keystore, signing properties and publisher key from
   secret storage. Do not repeat first-time key generation.
3. Restore the client's matching `publisher.der` and `runtime_profiles.json` to
   `android/host/src/main/res/raw/`. They are ignored by Git. Set
   `UNI_FRAME_RUNTIME_DIR` to these resources when building modules, or point it
   at an external copy of the same files. That variable is used by Python tools;
   the host build reads the files in its `res/raw` directory.
4. Set `UNI_FRAME_SIGNING_PROPERTIES`, build, and compare the old and new APKs
   with `apksigner verify --print-certs`. Package names must also match.
5. Increase `versionCode` for a published client update. The existing
   `publish-host-update.py --previous-apk ...` command checks package identity,
   certificate continuity and increasing version code before publishing.

The same version code can be reinstalled for a rebuild test, but the client's
update checker only offers a higher version. Android still requires the user to
confirm installation.

If the original private signing key is lost, this direct-download workflow
cannot normally update existing installs. Generating another key is not a repair.
Planned Android signing-key rotation requires a separate migration using the
original key; it is not configured here.

## 更换云端环境时保留签名

换机器本身不会影响覆盖升级，重新生成密钥才会。文件名、别名和密码相同，也不代表
生成的是同一把密钥。

需要长期保管两份私钥：

- **APK keystore**：让安卓确认新版和已安装的 APP 来自同一签名者。连同别名、密码和
  证书指纹一起保管。
- **`publisher.pem`**：给模块、应用目录和客户端更新公告签名。新客户端继续内置对应的
  `publisher.der`，并保留匹配的 `runtime_profiles.json`。

把它们存入独立于云端构建机的密码管理器或私有密钥存储，并保留加密恢复备份。不要放进
公开仓库或公开分发文件夹；同一云端里的备份也不能代替异地备份。

在新云端恢复原文件，按上面的格式配置 `apk-signing.properties`，设置
`UNI_FRAME_SIGNING_PROPERTIES` 后构建。构建会校验保存的证书指纹；密钥缺失或指纹不符
会直接失败，不会自动换一把密钥。以前使用 debug keystore 发布的预览版，同样必须沿用
原文件，不能只改成一份新生成的“正式签名”。

发布升级还需要包名一致、`versionCode` 增大。只重新编译同一版本可用于覆盖安装验证，
但不会让客户端的「检查更新」显示新版本。私钥无法从 APK 中提取恢复。
