import java.util.Properties

plugins {
    id("com.android.application")
    // Shadow must transform Android classes before kotlin-android takes over source sets.
    id("com.tencent.shadow.plugin")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Release signing: copy android/key.properties.example to android/key.properties
// and fill it in. Without it, release builds fall back to the debug key.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "__PACKAGE__"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "__PACKAGE__"
        minSdk = 24
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getenv("UNI_FRAME_DEBUG_KEYSTORE") ?: error("Set UNI_FRAME_DEBUG_KEYSTORE to the existing cloud test keystore"))
        }
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // P1 isolates Shadow/Flutter integration from shrinker adaptation.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }

}

val uniFrameRoot = rootProject.file("../vendor/uni-frame")
extra["uniFrameRoot"] = uniFrameRoot.absolutePath
apply(from = uniFrameRoot.resolve("integrations/android/app.gradle"))

flutter {
    source = "../.."
}
