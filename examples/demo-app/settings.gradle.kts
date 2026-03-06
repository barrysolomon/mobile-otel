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

include(":otel-android-mobile-core")
project(":otel-android-mobile-core").projectDir = file("../../otel-android-mobile-core")

// Instrumentation modules
include(":instrumentation-lifecycle")
project(":instrumentation-lifecycle").projectDir = file("../../instrumentation/lifecycle")

include(":instrumentation-screen")
project(":instrumentation-screen").projectDir = file("../../instrumentation/screen")

include(":instrumentation-tap")
project(":instrumentation-tap").projectDir = file("../../instrumentation/tap")

include(":instrumentation-scroll")
project(":instrumentation-scroll").projectDir = file("../../instrumentation/scroll")

include(":instrumentation-text-input")
project(":instrumentation-text-input").projectDir = file("../../instrumentation/text-input")

include(":instrumentation-back-press")
project(":instrumentation-back-press").projectDir = file("../../instrumentation/back-press")

include(":instrumentation-freeze")
project(":instrumentation-freeze").projectDir = file("../../instrumentation/freeze")

include(":instrumentation-errors")
project(":instrumentation-errors").projectDir = file("../../instrumentation/errors")

include(":instrumentation-network")
project(":instrumentation-network").projectDir = file("../../instrumentation/network")

include(":instrumentation-vitals")
project(":instrumentation-vitals").projectDir = file("../../instrumentation/vitals")
