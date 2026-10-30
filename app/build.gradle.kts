plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.movierecap"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.movierecap"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.media3.common.util.UnstableApi")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47")

    implementation("androidx.media3:media3-common:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.media3:media3-transformer:1.4.1")
}
