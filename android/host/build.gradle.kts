import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Keep signing identity outside the checkout so it can be restored on another builder.
val signingPath = System.getenv("UNI_FRAME_SIGNING_PROPERTIES")
val signingProperties = Properties()
val signingDirectory = signingPath?.let { path ->
    val config = file(path)
    require(config.isFile) { "UNI_FRAME_SIGNING_PROPERTIES must point to an existing file" }
    config.inputStream().use { signingProperties.load(it) }
    config.parentFile
}
fun signingValue(name: String): String = signingProperties.getProperty(name)
    ?.takeIf { it.isNotBlank() } ?: error("Missing APK signing property: $name")
val configuredKeystore = if (signingDirectory != null) {
    signingDirectory.resolve(signingValue("storeFile"))
} else {
    file(System.getenv("UNI_FRAME_DEBUG_KEYSTORE")
        ?: error("Set UNI_FRAME_SIGNING_PROPERTIES, or an existing UNI_FRAME_DEBUG_KEYSTORE for previews"))
}
require(configuredKeystore.isFile) { "APK keystore missing; restore the original key instead of generating a replacement" }
if (signingDirectory != null) {
    val expected = signingValue("certificateSha256").replace(":", "").lowercase()
    require(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid certificateSha256 in APK signing properties" }
    val store = KeyStore.getInstance(configuredKeystore, signingValue("storePassword").toCharArray())
    val alias = signingValue("keyAlias")
    require(store.isKeyEntry(alias)) { "APK signing alias has no private key" }
    val actual = MessageDigest.getInstance("SHA-256").digest(store.getCertificate(alias).encoded)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    require(actual == expected) { "APK signing certificate differs from the pinned identity" }
}

android {
    namespace = "dev.uniapp.frame"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.uniapp.frame"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "0.5.0-preview"
        // Optional library a fresh install connects to on first launch. Supplied at build
        // time so deployment links never live in this repository.
        val defaultCatalog = (findProperty("uniFrameDefaultCatalog") as String?)
            ?: System.getenv("UNI_FRAME_DEFAULT_CATALOG") ?: ""
        require(defaultCatalog.isEmpty() || defaultCatalog.startsWith("https://")) { "Default catalog must be an HTTPS link" }
        require(!defaultCatalog.contains("/drive/folders/")) { "Default catalog must be the catalog.json file link, not a Drive folder" }
        resValue("string", "default_catalog_url", defaultCatalog)
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        if (signingDirectory != null) {
            create("publisher") {
                storeFile = configuredKeystore
                storePassword = signingValue("storePassword")
                keyAlias = signingValue("keyAlias")
                keyPassword = signingValue("keyPassword")
                signingProperties.getProperty("storeType")?.let { storeType = it }
            }
        } else {
            getByName("debug") { storeFile = configuredKeystore }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (signingDirectory != null) "publisher" else "debug")
        }
    }
}
dependencies {
    implementation("com.tencent.shadow.core:loader:local-b2a7a74-SNAPSHOT")
    implementation("com.tencent.shadow.core:activity-container:local-b2a7a74-SNAPSHOT")
    implementation("com.tencent.shadow.core:common:local-b2a7a74-SNAPSHOT")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
