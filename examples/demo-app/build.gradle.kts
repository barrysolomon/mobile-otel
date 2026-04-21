// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "9.0.1" apply false
    id("com.android.library") version "9.0.1" apply false
    id("com.google.devtools.ksp") version "2.3.4" apply false
    kotlin("android") version "2.2.10" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0" apply false
}

subprojects {
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
                        groupId = "io.opentelemetry.android"
                        artifactId = if (isCore) {
                            "mobile-core"
                        } else {
                            "mobile-instrumentation-${path.removePrefix(":instrumentation-")}"
                        }
                        version = "0.1.0-alpha"
                        afterEvaluate {
                            from(components.findByName("release"))
                        }
                    }
                }
            }
        }
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
