package com.example.appforge

object Templates {
    // Workflow bawaan. Kalau proyek punya .github/workflows/build.yml sendiri, yang itu yang dipakai
    // (wajib punya trigger workflow_dispatch dan upload-artifact berisi APK).
    val workflow = """
name: Build
on: workflow_dispatch
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - uses: gradle/actions/setup-gradle@v4
        with: { gradle-version: "8.9" }
      - run: |
          if [ -f gradlew ]; then chmod +x gradlew; ./gradlew assembleDebug --no-daemon; else gradle assembleDebug --no-daemon; fi
      - uses: actions/upload-artifact@v4
        with:
          name: apk
          path: "**/build/outputs/apk/debug/*.apk"
""".trimIndent()

    val hello = mapOf(
        "settings.gradle.kts" to """
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "Hello"
include(":app")
""".trimIndent(),
        "build.gradle.kts" to """
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
""".trimIndent(),
        "gradle.properties" to "org.gradle.jvmargs=-Xmx2g\nandroid.useAndroidX=true\n",
        "app/build.gradle.kts" to """
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.hello"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.hello"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
""".trimIndent(),
        "app/src/main/AndroidManifest.xml" to """
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="Hello">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
""".trimIndent(),
        "app/src/main/java/com/example/hello/MainActivity.kt" to """
package com.example.hello

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Hello dari AppForge!"
            textSize = 24f
            setPadding(48, 96, 48, 48)
        })
    }
}
""".trimIndent()
    )
}
