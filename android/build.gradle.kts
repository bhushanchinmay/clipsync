// Top-level build file for ClipSync Android project
// This file configures plugins used across all modules but doesn't apply them here
plugins {
    // Android Gradle Plugin - builds Android apps
    id("com.android.application") version "9.2.1" apply false
    // Kotlin Android plugin - adds Kotlin support to Android modules
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    // KSP (Kotlin Symbol Processing) - used by Room for compile-time code generation
    id("com.google.devtools.ksp") version "2.3.2" apply false
}
