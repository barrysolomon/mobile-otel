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
    implementation("androidx.core:core-ktx:1.17.0")
    // Kotlin Serialization (used by JourneyBreadcrumb / JourneyBreadcrumbBuffer)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.20")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.58.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
