plugins {
    id("com.android.application")
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
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
}
