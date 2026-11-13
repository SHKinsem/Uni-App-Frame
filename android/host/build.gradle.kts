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
