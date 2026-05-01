import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

android {
    namespace = "io.opentelemetry.android.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.opentelemetry.android.demo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    flavorDimensions += "sdk"
    productFlavors {
        create("upstream") {
            dimension = "sdk"
            applicationIdSuffix = ".upstream"
            manifestPlaceholders["appNameSuffix"] = "(Upstream)"
        }
        create("dash0") {
            dimension = "sdk"
            applicationIdSuffix = ".dash0"
            manifestPlaceholders["appNameSuffix"] = "(Dash0)"
            // Default values; per-mode flavors in Task 0.4 will override these.
            buildConfigField("String", "DASH0_EXPORT_MODE", "\"cont\"")
            buildConfigField("String", "DASH0_EXPORT_MODE_ENUM", "\"CONTINUOUS\"")
        }
    }

    buildTypes {
        all {
            manifestPlaceholders.put("appName", "OpenTelemetry Android Demo")
        }
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs["debug"]
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    val javaVersion = JavaVersion.VERSION_11
    compileOptions {
        sourceCompatibility(javaVersion)
        targetCompatibility(javaVersion)
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.7")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.7")
    implementation("io.opentelemetry:opentelemetry-api-incubator:1.60.1-alpha")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.compose.material:material-icons-core:1.7.8")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // Upstream flavor — published Maven artifacts only
    "upstreamImplementation"("io.opentelemetry.android:android-agent:0.11.0-alpha") {
        exclude(group = "io.opentelemetry", module = "opentelemetry-api-incubator")
    }
    "upstreamImplementation"("io.opentelemetry.android.instrumentation:sessions:0.11.0-alpha") {
        exclude(group = "io.opentelemetry", module = "opentelemetry-api-incubator")
    }

    // Dash0 flavor — our SDK via project reference
    "dash0Implementation"(project(":otel-android-mobile"))

    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.60.1")

    testImplementation("org.junit.jupiter:junit-jupiter-api:6.0.3")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:6.0.3")
    testImplementation("org.junit.vintage:junit-vintage-engine:6.0.3")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

configurations.all {
    resolutionStrategy {
        eachDependency {
            if (requested.group == "com.squareup.okhttp3" && requested.name == "okhttp-jvm") {
                useTarget("com.squareup.okhttp3:okhttp:${requested.version}")
                because("choosing okhttp over okhttp-jvm")
            }
        }
    }
}
