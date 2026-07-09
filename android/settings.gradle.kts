// Settings file defines which modules are included in the build
// and where Gradle should look for plugins and dependencies

pluginManagement {
    repositories {
        google()            // Google's Maven repo (Android SDK, AndroidX, etc.)
        mavenCentral()      // Central Maven repository for most libraries
        gradlePluginPortal() // Gradle plugin repository
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    // FAIL_ON_PROJECT_REPOS: all repos must be declared here, not in individual modules
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ClipSync"
include(":app")
