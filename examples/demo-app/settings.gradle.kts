pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OTel Mobile Demo"

include(":android")
include(":otel-android-mobile")

// Point to the actual otel-android-mobile library
project(":otel-android-mobile").projectDir = file("../../otel-android-mobile")
