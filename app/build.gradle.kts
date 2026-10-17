plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.appforge"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.appforge"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    signingConfigs {
        create("aospTest") {
            storeFile = file("testkey.keystore")
            storePassword = "android"
            keyAlias = "testkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        // debug juga ditandatangani kunci yang sama, karena workflow GitHub Actions membangun debug
        debug {
            signingConfig = signingConfigs.getByName("aospTest")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("aospTest")
        }
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
