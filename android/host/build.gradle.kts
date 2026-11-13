plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.uniapp.frame"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.uniapp.frame"
        minSdk = 24
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0-preview"
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
        getByName("debug") { storeFile = file(System.getenv("UNI_FRAME_DEBUG_KEYSTORE") ?: error("Set your external test keystore path")) }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
