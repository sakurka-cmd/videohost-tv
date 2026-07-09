plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.videohost.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.videohost.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 11
        versionName = "2.0.3"
    }

    // Unified signing config — same keystore for BOTH debug and release builds.
    // This prevents INSTALL_FAILED_UPDATE_INCOMPATIBLE when updating APK across
    // different build machines (each machine has its own auto-generated debug keystore).
    // The keystore is committed to the repo at app/videohost-release.keystore.
    signingConfigs {
        create("unified") {
            storeFile = file("videohost-release.keystore")
            storePassword = "***REMOVED-KEYSTORE-PASS***"
            keyAlias = "videohost"
            keyPassword = "***REMOVED-KEYSTORE-PASS***"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("unified")
        }
        debug {
            // No applicationIdSuffix — updates install in place
            signingConfig = signingConfigs.getByName("unified")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    // Material3 (works on TV too with proper focus handling)
    implementation("androidx.compose.material3:material3")

    // Core Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling:preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Kotlinx serialization + coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Retrofit + OkHttp for networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Media3 ExoPlayer for video playback
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")
    implementation("androidx.media3:media3-datasource:1.3.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.3.1")

    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // DataStore for preferences (URL, session)
    implementation("androidx.datastore:datastore-preferences:1.1.1")
}
