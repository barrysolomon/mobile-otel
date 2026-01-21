# Build System Notes

**Last Updated**: January 21, 2026
**Status**: ✅ Build System Fully Operational

This document records all build fixes applied to get the Android demo app building and running with AGP 9.0 and OpenTelemetry SDK 1.58.0.

---

## Build Environment

### Versions
- **Android Gradle Plugin (AGP)**: 9.0.0
- **Gradle**: 8.9 (via wrapper)
- **Kotlin**: Bundled with AGP 9.0 (no separate plugin needed)
- **KSP**: 2.3.4
- **OpenTelemetry SDK**: 1.58.0
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 36 (Android 15)
- **Compile SDK**: 36

### JDK Requirements
- **Library (otel-android-mobile)**: JVM 17
- **Demo App (android module)**: JVM 1.8 with desugaring

---

## Critical Fixes Applied

### 1. AGP 9.0 Breaking Changes

#### Kotlin Plugin No Longer Required
**Issue**: AGP 9.0 includes Kotlin support natively
**Fix**: Removed `id("org.jetbrains.kotlin.android")` from all build files

**Files Modified**:
- `otel-android-mobile/build.gradle.kts`
- `examples/demo-app/build.gradle.kts`
- `examples/demo-app/android/build.gradle.kts`

#### targetSdk Location Change (Libraries Only)
**Issue**: `targetSdk` is deprecated in `defaultConfig` for libraries in AGP 9.0
**Fix**: Moved to `testOptions` and `lint` blocks

**Before**:
```kotlin
android {
    defaultConfig {
        minSdk = 26
        targetSdk = 36  // ❌ Deprecated for libraries
    }
}
```

**After**:
```kotlin
android {
    defaultConfig {
        minSdk = 26
        // No targetSdk here
    }
    testOptions {
        targetSdk = 36  // ✅ Correct location
    }
    lint {
        targetSdk = 36  // ✅ Correct location
    }
}
```

**File**: `otel-android-mobile/build.gradle.kts`

#### Publishing Configuration Required
**Issue**: AGP 9.0 requires explicit variant declaration for publishing
**Fix**: Added `singleVariant("release")` block

```kotlin
android {
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}
```

**File**: `otel-android-mobile/build.gradle.kts`

#### Repository Configuration
**Issue**: `allprojects` block conflicts with `settings.gradle.kts` `FAIL_ON_PROJECT_REPOS` mode
**Fix**: Removed `allprojects` block, centralized repositories in `settings.gradle.kts`

**File**: `examples/demo-app/build.gradle.kts`

---

### 2. OpenTelemetry SDK 1.58.0 API Changes

#### LogRecordData.getBody() Return Type Changed
**Issue**: SDK changed from `Value<*>` to `Body` type
**Fix**: Updated imports and type declarations

**Before**:
```kotlin
import io.opentelemetry.api.common.Value

private data class LogRecordDataImpl(
    private val body: Value<*>,  // ❌ Wrong type
    ...
) : LogRecordData
```

**After**:
```kotlin
import io.opentelemetry.sdk.logs.data.Body

private data class LogRecordDataImpl(
    private val body: Body,  // ✅ Correct type
    ...
) : LogRecordData
```

**Files Modified**:
- `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/export/EnrichingLogRecordExporter.kt`
- `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt`

#### Body.asString() Method Removed
**Issue**: `Body` type doesn't have `asString()` method in SDK 1.58.0
**Fix**: Changed to `toString()` method

**Before**:
```kotlin
val eventName = logRecord.body.asString()  // ❌ Method doesn't exist
```

**After**:
```kotlin
val eventName = logRecord.body.toString()  // ✅ Works
```

**File**: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt:172`

---

### 3. Kotlin Coroutines Issues

#### Missing runBlocking Import
**Issue**: `runBlocking` used but not imported
**Fix**: Added import statement

```kotlin
import kotlinx.coroutines.runBlocking
```

**File**: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/MobileLogRecordProcessor.kt`

#### whenComplete Lambda Parameter Handling
**Issue**: `CompletableResultCode.whenComplete` takes no-arg lambda, not single-arg
**Fix**: Changed from parameter lambda to no-arg lambda accessing outer variable

**Before**:
```kotlin
result.whenComplete { resultCode ->  // ❌ Wrong - takes no parameters
    if (resultCode.isSuccess) { ... }
}
```

**After**:
```kotlin
result.whenComplete {  // ✅ Correct - no parameters
    if (result.isSuccess) { ... }  // Access outer variable
}
```

**Files Modified**:
- `MobileLogRecordProcessor.kt:282-294`
- `RetryableExporter.kt:68-90, 109-116`

#### Suspend Function Calls from Non-Suspend Context
**Issue**: Calling suspend functions from regular functions
**Fix**: Wrapped in `runBlocking {}` blocks

```kotlin
// Disk buffer methods are suspend functions
val diskEvents = runBlocking {
    diskBuffer.getEventsInWindow(windowStartMs)
}
```

**Files Modified**:
- `MobileLogRecordProcessor.kt:188-190` (getEventsInWindow)
- `MobileLogRecordProcessor.kt:267-270` (getAllEvents)
- `MobileLogRecordProcessor.kt:286-288` (clearAll)

---

### 4. Kotlin Compiler Comment Parsing Bug

#### /* in Comments Confuses Compiler
**Issue**: Kotlin compiler interprets `/*` in comments as block comment start
**Fix**: Replaced `/*` with "wildcard" in all comments

**Examples**:
- Line 39: `"timezone": ["America/*"]` → `"timezone": ["America/wildcard"]`
- Line 201: `"America/*"` → `"America/wildcard"`
- Line 219: `["America/*", "US/*"]` → `["America/wildcard", "US/wildcard"]`
- Lines 291-292: Similar changes in doc comments

**File**: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt`

**Error Message**:
```
e: file:///.../PolicyEvaluator.kt:524:1 Syntax error: Unclosed comment.
```

---

### 5. Android Manifest Issues

#### Deprecated package Attribute
**Issue**: AGP 9.0 no longer supports `package` attribute in AndroidManifest.xml
**Fix**: Removed attribute, use `namespace` in build.gradle.kts instead

**Before**:
```xml
<manifest xmlns:android="..."
    package="io.opentelemetry.android.demo">  <!-- ❌ Deprecated -->
```

**After**:
```xml
<manifest xmlns:android="...">  <!-- ✅ Removed -->
```

```kotlin
// In build.gradle.kts
android {
    namespace = "io.opentelemetry.android.demo"  // ✅ Use this
}
```

**File**: `examples/demo-app/android/src/main/AndroidManifest.xml`

#### Theme Compatibility
**Issue**: `AppCompatActivity` requires AppCompat theme, not Material theme
**Error**: `You need to use a Theme.AppCompat theme (or descendant) with this activity`

**Fix**: Changed theme in manifest

**Before**:
```xml
<application
    android:theme="@android:style/Theme.Material.Light">  <!-- ❌ Wrong -->
```

**After**:
```xml
<application
    android:theme="@style/Theme.AppCompat.Light">  <!-- ✅ Correct -->
```

**File**: `examples/demo-app/android/src/main/AndroidManifest.xml:10`

---

### 6. Missing Resources

#### Layout File Missing
**Issue**: MainActivity references `R.layout.activity_main` which didn't exist
**Fix**: Created layout XML file

**Created**: `examples/demo-app/android/src/main/res/layout/activity_main.xml`

**Contents**:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout ...>
    <TextView android:id="@+id/statusText" ... />
    <Button android:id="@+id/btnScenarioA" android:text="Scenario A: UI Freeze" />
    <Button android:id="@+id/btnScenarioB" android:text="Scenario B: Crash" />
    <Button android:id="@+id/btnScenarioC" android:text="Scenario C: Network Error" />
    <Button android:id="@+id/btnForceFlush" android:text="Force Flush All Events" />
</LinearLayout>
```

---

### 7. SDK Version Mismatch

#### Demo App minSdk Too Low
**Issue**: Demo app had `minSdk = 24` but library requires `minSdk = 26`
**Error**:
```
uses-sdk:minSdkVersion 24 cannot be smaller than version 26
declared in library [:otel-android-mobile]
```

**Fix**: Increased demo app minSdk to match library

**File**: `examples/demo-app/android/build.gradle.kts`

```kotlin
defaultConfig {
    minSdk = 26  // Changed from 24
    targetSdk = 36
}
```

---

## New Build Files Created

### 1. Root Build Configuration
**File**: `examples/demo-app/build.gradle.kts`

```kotlin
plugins {
    id("com.android.application") version "9.0.0" apply false
    id("com.android.library") version "9.0.0" apply false
    id("com.google.devtools.ksp") version "2.3.4" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
```

### 2. Settings Configuration
**File**: `examples/demo-app/settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OTel Mobile Demo"
include(":android")
include(":otel-android-mobile")
project(":otel-android-mobile").projectDir = file("../../otel-android-mobile")
```

### 3. Gradle Properties
**File**: `examples/demo-app/gradle.properties`

```properties
android.useAndroidX=true
kotlin.code.style=official
org.gradle.caching=true
org.gradle.parallel=true
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.enableR8.fullMode=true
android.suppressUnsupportedCompileSdk=36
```

### 4. Comprehensive .gitignore
**File**: `.gitignore` (root)

Includes exclusions for:
- Android/Gradle build outputs (*.apk, *.class, build/, .gradle/)
- IDE files (.idea/, *.iml, .vscode/)
- OS-specific files (.DS_Store, Thumbs.db)
- Temporary files (*.tmp, *.swp, *.bak)
- Test results, profiling data
- Node modules (if using React Native)

**Action**: Also removed accidentally tracked `.gradle/` directories from git

---

## Verification Steps

### Build Success
```bash
cd examples/demo-app
./gradlew clean
./gradlew :android:assembleDebug
```

**Result**:
```
BUILD SUCCESSFUL in 3s
52 actionable tasks: 15 executed, 37 up-to-date
```

**APK Location**: `examples/demo-app/android/build/outputs/apk/debug/android-debug.apk` (8.2MB)

### Runtime Verification
Deployed to Android emulator (API 35), confirmed:
- ✅ App launches successfully
- ✅ OpenTelemetry initialized (RAM + Disk buffers)
- ✅ All 4 scenarios functional (UI Freeze, Crash, Network Error, Force Flush)
- ✅ Events captured and logged correctly
- ✅ Offline resilience working (events buffered when no collector)
- ✅ Retry logic with exponential backoff (4 attempts: 1s→2s→4s→8s)

### Log Output Verification
```
MobileLogR...dProcessor: Initialized: RAM buffer size=5000, Disk buffer=50MB, TTL=24h
OTELDemoApp: OpenTelemetry initialized: deviceId=15419d15-232e-44c6-bcb5-ad5dc3f6177b
OTELDemoApp: Scenario A complete: ui.freeze event logged
OTELDemoApp: Force flush requested
MobileLogR...dProcessor: Force flushing 11 events
RetryableExporter: Export failed after 4 attempts  // ✅ Expected - no collector
MobileLogR...dProcessor: Force flush failed, keeping events in buffer  // ✅ Correct behavior
```

---

## Common Build Errors & Solutions

### "Kotlin plugin no longer required"
**Solution**: Remove Kotlin plugin from all build.gradle.kts files (AGP 9.0 includes it)

### "targetSdk is deprecated in defaultConfig"
**Solution**: Move targetSdk to testOptions and lint blocks (library modules only)

### "SoftwareComponent 'release' not found"
**Solution**: Add android.publishing.singleVariant("release") block

### "Failed to resolve opentelemetry-exporter-otlp-logs"
**Solution**: Use opentelemetry-exporter-otlp:1.58.0 (includes logs, no separate artifact needed)

### "Suspend function can only be called from coroutine"
**Solution**: Wrap call in `runBlocking {}` or make calling function `suspend`

### "Unresolved reference 'runBlocking'"
**Solution**: Add `import kotlinx.coroutines.runBlocking`

### "Unclosed comment" in PolicyEvaluator.kt
**Solution**: Replace `/*` in comments with "wildcard" or other text

### "You need to use a Theme.AppCompat theme"
**Solution**: Use `@style/Theme.AppCompat.Light` instead of `@android:style/Theme.Material.Light`

### "minSdkVersion 24 cannot be smaller than version 26"
**Solution**: Increase demo app minSdk to 26 to match library requirement

---

## Dependencies Matrix

### Library Module (otel-android-mobile)
| Dependency | Version | Purpose |
|------------|---------|---------|
| opentelemetry-api | 1.58.0 | OTEL core API |
| opentelemetry-sdk | 1.58.0 | OTEL SDK implementation |
| opentelemetry-sdk-logs | 1.58.0 | OTEL logs SDK |
| opentelemetry-android:instrumentation | 0.4.0-alpha | Android auto-instrumentation |
| opentelemetry-exporter-otlp | 1.58.0 | OTLP/gRPC exporter |
| opentelemetry-semconv | 1.37.0 | Semantic conventions |
| room-runtime | 2.8.4 | SQLite persistence |
| room-ktx | 2.8.4 | Kotlin extensions for Room |
| kotlinx-coroutines-android | 1.10.2 | Coroutines for Android |
| core-ktx | 1.17.0 | AndroidX core extensions |
| appcompat | 1.7.1 | AppCompat library |
| okhttp | 4.12.0 | HTTP client for policy fetch |

### Demo App Module (android)
| Dependency | Version | Purpose |
|------------|---------|---------|
| project(:otel-android-mobile) | - | Our OTEL library |
| desugar_jdk_libs | 2.1.5 | Java 8+ API desugaring |
| core-ktx | 1.17.0 | AndroidX core |
| appcompat | 1.7.1 | AppCompat support |
| material | 1.13.0 | Material Design components |
| constraintlayout | 2.2.1 | Layout system |

---

## Future Considerations

### When Upgrading AGP
- Check for new breaking changes in AGP release notes
- Verify Kotlin compatibility (bundled version may change)
- Test build with `./gradlew :android:assembleDebug --warning-mode all`

### When Upgrading OTEL SDK
- Check API changes in release notes
- Verify `Body` type compatibility
- Test serialization/deserialization paths
- Update semantic conventions if needed

### When Adding New Dependencies
- Check compatibility with existing versions
- Run `./gradlew :otel-android-mobile:dependencies` to verify resolution
- Test on both JVM 17 (library) and JVM 8 + desugaring (app)

---

## Build Performance

**Clean Build**: ~8-10 seconds
**Incremental Build**: ~2-3 seconds
**APK Size**: 8.2MB (debug build, unoptimized)

**Optimization Opportunities**:
- Enable R8 code shrinking for release builds
- Consider ProGuard rules for OTEL SDK
- Use build cache (`org.gradle.caching=true` already enabled)
- Enable configuration cache when stable

---

**Build Status**: ✅ All Systems Operational
**Last Verified**: January 21, 2026
**Next Review**: Before Phase 5 (Documentation) begins
