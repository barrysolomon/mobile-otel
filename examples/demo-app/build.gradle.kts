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
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
