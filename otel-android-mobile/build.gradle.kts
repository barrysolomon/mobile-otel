plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("maven-publish")
}

android {
    namespace = "io.opentelemetry.android.mobile"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        targetSdk = 34

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // OpenTelemetry SDK - Core dependencies
    api("io.opentelemetry:opentelemetry-api:1.34.1")
    api("io.opentelemetry:opentelemetry-sdk:1.34.1")
    api("io.opentelemetry:opentelemetry-sdk-logs:1.34.1-alpha")

    // OpenTelemetry Android Instrumentation
    api("io.opentelemetry.android:instrumentation:0.4.0-alpha")

    // OTLP Exporter
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.34.1")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp-logs:1.34.1-alpha")

    // Semantic Conventions
    implementation("io.opentelemetry.semconv:opentelemetry-semconv:1.23.1-alpha")

    // Room for local persistence
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Android dependencies
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Testing - Unit Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.20")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // Testing - Android Instrumented Tests
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "io.opentelemetry.android"
            artifactId = "mobile"
            version = "0.1.0-alpha"

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("OpenTelemetry Android Mobile Extensions")
                description.set("Mobile-specific extensions for OpenTelemetry Android including buffering and conditional export")
                url.set("https://github.com/open-telemetry/opentelemetry-android-contrib")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("opentelemetry")
                        name.set("OpenTelemetry")
                        email.set("cncf-opentelemetry-maintainers@lists.cncf.io")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/open-telemetry/opentelemetry-android-contrib.git")
                    developerConnection.set("scm:git:ssh://github.com/open-telemetry/opentelemetry-android-contrib.git")
                    url.set("https://github.com/open-telemetry/opentelemetry-android-contrib")
                }
            }
        }
    }
}
