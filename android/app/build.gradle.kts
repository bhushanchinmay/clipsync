// App module build configuration
// This module contains all the ClipSync application code
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // KSP processes Room annotations at compile time to generate DAO implementations
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.clipsync"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.clipsync"
        // minSdk 29 = Android 10 (the version that restricted background clipboard access)
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // ViewBinding generates type-safe binding classes for each XML layout
    // Replaces error-prone findViewById() calls
    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // AndroidX Core - Kotlin extensions for Android framework classes
    implementation("androidx.core:core-ktx:1.12.0")
    // AppCompat - backward-compatible versions of Android UI components
    implementation("androidx.appcompat:appcompat:1.6.1")
    // Material Design 3 components (cards, buttons, themes)
    implementation("com.google.android.material:material:1.11.0")
    // ConstraintLayout for flexible UI layouts
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Lifecycle components for coroutine-aware lifecycle management
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    // LiveData with Kotlin extensions
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    // ViewModel for UI state management
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // Kotlin Coroutines for async operations (TCP I/O, database)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Room database - SQLite abstraction layer
    implementation("androidx.room:room-runtime:2.8.4")
    // Room Kotlin extensions (coroutine support)
    implementation("androidx.room:room-ktx:2.8.4")
    // Room annotation processor via KSP (generates DAO implementations)
    ksp("androidx.room:room-compiler:2.8.4")

    // Activity KTX for result APIs and extensions
    implementation("androidx.activity:activity-ktx:1.8.2")
}
