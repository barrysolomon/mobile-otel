// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

plugins {
    id("com.android.library")
    id("maven-publish")
    kotlin("plugin.serialization") version "1.9.20"
}

android {
    namespace = "io.opentelemetry.android.mobile.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        targetSdk = 36
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        targetSdk = 36
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    api("io.opentelemetry:opentelemetry-api:1.58.0")
    api("io.opentelemetry:opentelemetry-sdk:1.58.0")
    api("io.opentelemetry:opentelemetry-sdk-logs:1.58.0")
    // Upstream opentelemetry-android interfaces for adapter compatibility
    // Exclude api-incubator to avoid ExtendedAttributeKey conflict with our OTel SDK 1.58.0
    api("io.opentelemetry.android:session:1.2.0-alpha") {
        exclude(group = "io.opentelemetry", module = "opentelemetry-api-incubator")
    }
    api("io.opentelemetry.android.instrumentation:android-instrumentation:1.2.0-alpha") {
        exclude(group = "io.opentelemetry", module = "opentelemetry-api-incubator")
    }
    implementation("androidx.core:core-ktx:1.17.0")
    // Fragment lifecycle callbacks for FragmentLifecycleInstrumentation.
    // compileOnly so the SDK doesn't force this dep on consumers that don't
    // ship Fragment-hosting activities — the runtime path checks for the
    // class via reflection and short-circuits when the dep isn't on the
    // host classpath. Test classpath gets it explicitly below.
    compileOnly("androidx.fragment:fragment-ktx:1.8.5")
    // Kotlin Serialization (used by JourneyBreadcrumb / JourneyBreadcrumbBuffer)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.20")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.58.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Real fragment-ktx so FragmentLifecycleInstrumentation tests can
    // actually exercise FragmentManager + FragmentLifecycleCallbacks under
    // Robolectric. compileOnly above keeps the dep off SDK consumers; this
    // test-only line is the matching test-side wiring.
    testImplementation("androidx.fragment:fragment-ktx:1.8.5")
}
