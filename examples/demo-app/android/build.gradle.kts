// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

plugins {
    id("com.android.application")
}

// Read .env file from the demo-app directory (parent of android/)
fun loadEnv(): Map<String, String> {
    val envFile = File(projectDir.parent, ".env")
    if (!envFile.exists()) return emptyMap()
    return envFile.readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx < 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }
        .toMap()
}

// Generate otel-config.json from template + .env before assets are merged
tasks.register("generateOtelConfig") {
    val templateFile = file("src/debug/assets/otel-config.json.template")
    val outputFile = file("src/debug/assets/otel-config.json")
    val envFile = File(projectDir.parent, ".env")

    inputs.file(templateFile).optional()
    inputs.file(envFile).optional()
    outputs.file(outputFile)

    doLast {
        if (!templateFile.exists()) {
            logger.warn("otel-config.json.template not found — skipping generation")
            return@doLast
        }
        val env = loadEnv()
        var content = templateFile.readText()
        env["DASH0_ENDPOINT"]?.let { content = content.replace("https://YOUR_COLLECTOR_ENDPOINT:4317", it) }
        env["DASH0_AUTH_TOKEN"]?.let { content = content.replace("YOUR_AUTH_TOKEN", it) }
        env["DASH0_DATASET"]?.let { content = content.replace("YOUR_DATASET_NAME", it) }
        outputFile.writeText(content)
        if (env.isEmpty()) {
            logger.warn("No .env file found — otel-config.json still contains placeholders. Copy .env.template to .env and fill in your values.")
        } else {
            logger.lifecycle("Generated otel-config.json from .env (endpoint: ${env["DASH0_ENDPOINT"] ?: "not set"})")
        }
    }
}

afterEvaluate {
    tasks.named("mergeDebugAssets") { dependsOn("generateOtelConfig") }
}

android {
    namespace = "io.opentelemetry.android.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.opentelemetry.android.demo"
        minSdk = 26
        targetSdk = 36
        versionCode = 20260306
        versionName = "1.1.0-20260306"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["clearPackageData"] = "false"
        // Crash tests intentionally kill the process — run via demo-control-center.sh, not Gradle
        testInstrumentationRunnerArguments["notClass"] =
            "io.opentelemetry.android.demo.scenarios.RealCrashPhase1Test," +
            "io.opentelemetry.android.demo.scenarios.RealCrashPhase2Test"
    }

    buildTypes {
        release {
            // R8 ON, deliberately: this demo is the SDK's reference consumer, so its
            // release build is the gate that proves consumer-rules.pro actually works
            // in a minified app (CI builds it on every push). Real consumer apps ship
            // minified — an SDK that has never met R8 fails on integration day 1.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Core library desugaring for OpenTelemetry instrumentation
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // OTEL Android Mobile Library (our library)
    implementation(project(":otel-android-mobile"))

    // Debug widget (incubating — development/demo builds only)
    implementation(project(":instrumentation-debug-widget"))

    // Android Core
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.cardview:cardview:1.0.0")

    // RecyclerView and SwipeRefreshLayout
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Fragment and Navigation
    implementation("androidx.fragment:fragment-ktx:1.8.7")
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.6")

    // Lifecycle and ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // HTTP Client for real network calls in demo scenarios
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Testing - Unit Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.20")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Testing - Instrumented Tests
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestUtil("androidx.test:orchestrator:1.5.1")
}
