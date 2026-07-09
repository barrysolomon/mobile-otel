// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "9.0.1" apply false
    id("com.android.library") version "9.0.1" apply false
    id("com.google.devtools.ksp") version "2.3.4" apply false
    kotlin("android") version "2.2.10" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0" apply false
}

subprojects {
    // opentelemetry-android 1.5.0 transitively declares kotlin-stdlib 2.4.0,
    // whose metadata version our bundled Kotlin compiler (2.2.10, reads up to
    // 2.3.0) cannot parse — compile fails with "incompatible version of Kotlin".
    // Pin the transitive stdlib down to the compiler's version across all
    // modules. stdlib is backward-compatible, and upstream only uses basic APIs.
    // This force is BUILD-LOCAL — it does not propagate to published metadata, so
    // external consumers on Kotlin < 2.3 must apply the same pin themselves. That
    // is by design (issue #60 / docs/ANDROID_SDK_GUIDE.md "Kotlin toolchain
    // compatibility"): a published `strictly` cap would downgrade 2.4.x consumers.
    configurations.all {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
        }
    }

    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.3.1")
        android.set(true)
    }

    // Auto-apply Maven publishing to every library module in this build so
    // external consumers (like the RN demo) can pull a full dependency tree
    // from mavenLocal. Without this, sub-module deps publish as
    // groupId=<rootProject.name> with version=unspecified — unresolvable.
    val isCore = path == ":otel-android-mobile-core"
    val isInstrumentation = path.startsWith(":instrumentation-")

    if (isCore || isInstrumentation) {
        apply(plugin = "maven-publish")

        // Android library modules need singleVariant("release") to emit an
        // AAR when `from(components["release"])` is used. Without this, only
        // a POM publishes — silently — and consumers can't find the classes.
        afterEvaluate {
            extensions.findByType<com.android.build.api.dsl.LibraryExtension>()?.apply {
                publishing {
                    singleVariant("release") {}
                }
            }
        }

        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications {
                    register<MavenPublication>("release") {
                        // Coordinates come from gradle.properties (single source of
                        // truth) — same groupId/version as the umbrella, so the
                        // umbrella POM's sibling refs always resolve. See docs/MAVEN_CENTRAL.md.
                        groupId = (project.findProperty("sdkGroupId") as String?) ?: "io.github.barrysolomon"
                        artifactId = if (isCore) {
                            "mobile-core"
                        } else {
                            "mobile-instrumentation-${path.removePrefix(":instrumentation-")}"
                        }
                        version = (project.findProperty("sdkVersionName") as String?) ?: "1.0.0"
                        afterEvaluate {
                            from(components.findByName("release"))
                        }
                        // Maven Central requires complete POM metadata on EVERY
                        // artifact (name/description/url/license/scm/developers);
                        // GitHub Packages tolerates its absence, Central rejects.
                        // See docs/MAVEN_CENTRAL.md.
                        pom {
                            name.set(if (isCore) "Mobile OTel Core" else "Mobile OTel Instrumentation")
                            description.set("Mobile-specific OpenTelemetry Android module: buffering, conditional export, auto-instrumentation")
                            url.set("https://github.com/barrysolomon/mobile-otel")
                            licenses {
                                license {
                                    name.set("The Apache License, Version 2.0")
                                    url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                                }
                            }
                            developers {
                                developer {
                                    id.set("barrysolomon")
                                    name.set("Barry Solomon")
                                    email.set("barry@testingalchemy.com")
                                }
                            }
                            scm {
                                connection.set("scm:git:git://github.com/barrysolomon/mobile-otel.git")
                                developerConnection.set("scm:git:ssh://github.com/barrysolomon/mobile-otel.git")
                                url.set("https://github.com/barrysolomon/mobile-otel")
                            }
                        }
                    }
                }
                // Publish core + every instrumentation module to GitHub Packages,
                // not just mavenLocal. The umbrella `io.opentelemetry.android:mobile`
                // POM declares ~20 sibling deps (mobile-core + mobile-instrumentation-*);
                // without this repo those siblings only landed in mavenLocal, so external
                // consumers hit `Could not find io.opentelemetry.android:mobile-core`
                // (reported by Loper, 2026-06). Same repo name ("GitHubPackages") as the
                // umbrella module, so one `publishReleasePublicationToGitHubPackagesRepository`
                // run publishes the whole tree. mavenLocal stays available for dev.
                repositories {
                    // PUBLIC, no-auth Maven repo (GitHub Pages). Same local dir as
                    // the umbrella so ONE gh-pages deploy carries the whole tree
                    // (umbrella + mobile-core + every mobile-instrumentation-*).
                    maven {
                        name = "Pages"
                        url = uri(rootProject.layout.buildDirectory.dir("pages-maven"))
                    }
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

            // Central-ready signing — active only when CI signing secrets exist
            // (see docs/MAVEN_CENTRAL.md). No-op for local + Pages/GitHub Packages.
            System.getenv("SIGNING_KEY")?.let { signingKey ->
                apply(plugin = "signing")
                extensions.configure<SigningExtension>("signing") {
                    useInMemoryPgpKeys(signingKey, System.getenv("SIGNING_PASSWORD"))
                    sign(extensions.getByType<PublishingExtension>().publications["release"])
                }
            }
        }
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
