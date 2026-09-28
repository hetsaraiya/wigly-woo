plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val wiglyVersionName = providers.environmentVariable("WIGLY_VERSION_NAME").orElse("0.1").get()
val wiglyVersionCode = providers.environmentVariable("WIGLY_VERSION_CODE")
    .map { it.toInt() }
    .orElse(1)
    .get()
val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull

android {
    namespace = "com.wiglywoo"
    compileSdk = 34
    // Pin to the NDK baked into the Docker image so AGP doesn't try to download
    // its own default NDK at build time.
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.wiglywoo"
        minSdk = 24
        targetSdk = 34
        versionCode = wiglyVersionCode
        versionName = wiglyVersionName

        ndk {
            // Build the JNI shim for these ABIs; the Go .so must already exist
            // in jniLibs/<abi>/ (run ../build-core.sh first).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=none" }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // The prebuilt Go core .so lives here, packaged alongside the JNI shim.
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    signingConfigs {
        val debugKeystore = rootProject.file("signing/wigly-debug.keystore")
        if (debugKeystore.exists()) {
            create("persistentDebug") {
                storeFile = debugKeystore
                storePassword = "wiglywoo-debug"
                keyAlias = "wiglywoo"
                keyPassword = "wiglywoo-debug"
            }
        }

        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD")
                    .orNull ?: error("ANDROID_KEYSTORE_PASSWORD is required")
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS")
                    .orNull ?: error("ANDROID_KEY_ALIAS is required")
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD")
                    .orNull ?: error("ANDROID_KEY_PASSWORD is required")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
                ?: signingConfigs.findByName("persistentDebug")?.let { signingConfig = it }
        }
        getByName("release") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
        aidl = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
