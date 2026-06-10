plugins {
    id("com.android.library")
    id("com.google.devtools.ksp")
    id("maven-publish")
    kotlin("plugin.serialization") version "1.9.20"
}

android {
    namespace = "io.opentelemetry.android.mobile"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    lint {
        targetSdk = 36
        baseline = file("lint-baseline.xml")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    testOptions {
        targetSdk = 36
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            // AGP's Dokka-backed Javadoc generation in this stack cannot parse Java 25
            // version strings. Keep local Java 25 publishing usable for demo consumers.
            if (Runtime.version().feature() < 25) {
                withJavadocJar()
            }
        }
    }
}

dependencies {
    // Core interfaces and instrumentation modules
    api(project(":otel-android-mobile-core"))
    api(project(":instrumentation-lifecycle"))
    api(project(":instrumentation-screen"))
    api(project(":instrumentation-scroll"))
    api(project(":instrumentation-text-input"))
    api(project(":instrumentation-back-press"))
    api(project(":instrumentation-freeze"))
    api(project(":instrumentation-tap"))
    api(project(":instrumentation-errors"))
    api(project(":instrumentation-network"))
    api(project(":instrumentation-vitals"))
    api(project(":instrumentation-screenshot"))
    api(project(":instrumentation-wireframe"))
    api(project(":instrumentation-compose-click"))
    api(project(":instrumentation-screen-orientation"))
    api(project(":instrumentation-database"))
    api(project(":instrumentation-file-io"))
    api(project(":instrumentation-timber"))
    api(project(":instrumentation-system-events"))

    // OpenTelemetry SDK - Core dependencies
    api("io.opentelemetry:opentelemetry-api:1.58.0")
    api("io.opentelemetry:opentelemetry-sdk:1.58.0")
    api("io.opentelemetry:opentelemetry-sdk-logs:1.58.0")

    // OTLP Exporter (includes logs, traces, and metrics)
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:1.58.0")

    // Semantic Conventions
    implementation("io.opentelemetry.semconv:opentelemetry-semconv:1.39.0")

    // Room for local persistence
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // Android dependencies
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    // AndroidX Security for EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // SQLCipher for at-rest encryption of the Room/SQLite disk buffer.
    // Room integrates via its openHelperFactory(SupportFactory) hook; the
    // SQLCipher passphrase is wrapped by an Android Keystore key
    // (see DiskBufferKeyManager). androidx.sqlite SupportFactory bridges
    // SQLCipher's SupportSQLiteOpenHelper into Room.
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite-ktx:2.6.2")

    // HTTP client for policy fetching
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Testing - Unit Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.20")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.58.0")

    // Core library desugaring required by io.opentelemetry.android:instrumentation
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // Testing - Android Instrumented Tests
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "io.opentelemetry.android"
            artifactId = "mobile"
            version = "0.2.0-alpha"

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

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/barrysolomon/mobile-otel")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
                password = project.findProperty("gpr.token") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
