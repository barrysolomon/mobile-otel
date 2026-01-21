# Android SDK Integration Guide

Complete guide for integrating the Mobile Observability SDK into your Android app.

## Table of Contents

1. [Overview](#overview)
2. [Installation](#installation)
3. [Initialization](#initialization)
4. [Basic Usage](#basic-usage)
5. [Advanced Features](#advanced-features)
6. [Best Practices](#best-practices)
7. [Performance Considerations](#performance-considerations)
8. [Troubleshooting](#troubleshooting)
9. [API Reference](#api-reference)

## Overview

The Mobile Observability SDK provides:
- Local event buffering (RAM + disk)
- Automatic workflow evaluation
- Selective data flushing
- Crash recovery
- Low overhead (<5% performance impact)

### Architecture

```
Your App                     SDK
   │                         │
   ├──► captureEvent()──────►│ Ring Buffer (RAM + Disk)
   │                         │       │
   │                         │       ├──► Workflow Evaluator
   │                         │       │         │
   │                         │       │         ├──► Trigger match?
   │                         │       │         │         │
   │                         │       │         │         └──► Yes: Flush to Gateway
   │                         │       │         │
   │                         │       │         └──► No: Keep buffering
   │                         │       │
   │                         │       └──► Auto-eviction (50MB, 24h)
   │                         │
   └──► (App crash)──────────┼──► Mark crash
                             │
   (Next launch)             │
                             └──► Recover and flush crash context
```

## Installation

### Step 1: Copy SDK Files

Copy these files to your project:

```
your-app/src/main/java/com/yourcompany/observability/
├── ObservabilitySDK.kt
├── buffer/
│   ├── RingBufferManager.kt
│   ├── EventEntity.kt
│   ├── EventDao.kt
│   ├── CrashMarkerEntity.kt
│   └── CrashMarkerDao.kt
├── workflow/
│   ├── WorkflowEvaluator.kt
│   └── Models.kt
└── network/
    └── GatewayClient.kt
```

### Step 2: Add Dependencies

In your `build.gradle.kts`:

```kotlin
dependencies {
    // Room for local database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // OkHttp for networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gson for JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
```

Add KSP plugin in your app's `build.gradle.kts`:

```kotlin
plugins {
    id("com.google.devtools.ksp") version "1.9.20-1.0.14"
}
```

### Step 3: Sync Gradle

```bash
./gradlew build
```

## Initialization

### Basic Initialization

Initialize the SDK in your Application class:

```kotlin
// MyApplication.kt
package com.yourcompany.app

import android.app.Application
import com.yourcompany.observability.ObservabilitySDK

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize SDK
        ObservabilitySDK.initialize(
            context = this,
            gatewayUrl = "https://gateway.yourcompany.com"
        )
    }
}
```

Register your Application class in `AndroidManifest.xml`:

```xml
<manifest>
    <application
        android:name=".MyApplication"
        ...>
    </application>
</manifest>
```

### Advanced Initialization

Configure SDK with custom settings:

```kotlin
ObservabilitySDK.initialize(
    context = this,
    gatewayUrl = "https://gateway.yourcompany.com",
    appId = "my-app",  // Default: package name
    diskMb = 100,      // Default: 50
    ramEvents = 10000, // Default: 5000
    configPollIntervalMs = 120000  // Default: 60000 (1 minute)
)
```

## Basic Usage

### Capturing Events

#### Simple Events

```kotlin
class MainActivity : AppCompatActivity() {
    private val sdk = ObservabilitySDK.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Screen view event
        sdk.captureEvent("screen.view", mapOf(
            "screen_name" to "MainActivity"
        ))
    }
}
```

#### Events with Attributes

```kotlin
// User interaction
button.setOnClickListener {
    sdk.captureEvent("button.click", mapOf(
        "button_id" to "submit",
        "screen" to "MainActivity",
        "user_id" to currentUserId
    ))
}

// Business event
sdk.captureEvent("purchase.completed", mapOf(
    "amount" to 99.99,
    "currency" to "USD",
    "product_id" to "premium-plan",
    "user_id" to currentUserId
))
```

### Tracking Performance

#### UI Freeze Detection

```kotlin
class MainActivity : AppCompatActivity() {
    private val sdk = ObservabilitySDK.getInstance()

    private fun performHeavyOperation() {
        val startTime = System.currentTimeMillis()

        try {
            // Heavy operation
            Thread.sleep(3000)  // Simulated freeze
        } finally {
            val duration = System.currentTimeMillis() - startTime

            if (duration > 2000) {
                sdk.captureEvent("ui.freeze", mapOf(
                    "duration_ms" to duration,
                    "screen" to "MainActivity",
                    "operation" to "performHeavyOperation"
                ))
            }
        }
    }
}
```

#### Network Requests

```kotlin
// Using OkHttp Interceptor
class ObservabilityInterceptor(
    private val sdk: ObservabilitySDK
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            // Network error
            sdk.captureEvent("http.error", mapOf(
                "method" to request.method,
                "url" to request.url.toString(),
                "error" to e.message,
                "duration_ms" to (System.currentTimeMillis() - startTime)
            ))
            throw e
        }

        val duration = System.currentTimeMillis() - startTime

        // Capture all requests
        sdk.captureEvent("http.request", mapOf(
            "method" to request.method,
            "url" to request.url.toString(),
            "status_code" to response.code,
            "duration_ms" to duration,
            "success" to response.isSuccessful
        ))

        // Capture errors specifically
        if (!response.isSuccessful) {
            sdk.captureEvent("http.error", mapOf(
                "method" to request.method,
                "url" to request.url.toString(),
                "status_code" to response.code,
                "duration_ms" to duration
            ))
        }

        return response
    }
}

// Add to OkHttpClient
val client = OkHttpClient.Builder()
    .addInterceptor(ObservabilityInterceptor(sdk))
    .build()
```

### Error Tracking

#### Caught Exceptions

```kotlin
try {
    riskyOperation()
} catch (e: Exception) {
    sdk.captureEvent("error.caught", mapOf(
        "exception_type" to e::class.java.simpleName,
        "message" to (e.message ?: ""),
        "stack_trace" to e.stackTraceToString(),
        "screen" to "MainActivity",
        "operation" to "riskyOperation"
    ))

    // Handle error
}
```

#### Uncaught Exception Handler

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val sdk = ObservabilitySDK.initialize(this, gatewayUrl)

        // Install crash handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Capture crash event
            sdk.captureEvent("crash", mapOf(
                "exception_type" to throwable::class.java.simpleName,
                "message" to (throwable.message ?: ""),
                "stack_trace" to throwable.stackTraceToString(),
                "thread" to thread.name
            ))

            // Mark crash for recovery
            sdk.markCrash()

            // Call default handler
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
```

## Advanced Features

### Custom Attributes

Add attributes to all events automatically:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        val sdk = ObservabilitySDK.initialize(this, gatewayUrl)

        // Set global attributes
        sdk.setGlobalAttributes(mapOf(
            "app_version" to BuildConfig.VERSION_NAME,
            "build_number" to BuildConfig.VERSION_CODE.toString(),
            "environment" to if (BuildConfig.DEBUG) "dev" else "prod"
        ))
    }
}
```

### User Identification

Associate events with users:

```kotlin
// On login
fun onUserLoggedIn(userId: String, email: String) {
    val sdk = ObservabilitySDK.getInstance()

    sdk.identifyUser(userId, mapOf(
        "email" to email,
        "account_type" to "premium"
    ))

    // All subsequent events will include user info
}

// On logout
fun onUserLoggedOut() {
    val sdk = ObservabilitySDK.getInstance()
    sdk.clearUser()
}
```

### Manual Flush

Force flush events (rare, usually automatic):

```kotlin
// Example: Before app backgrounding
override fun onPause() {
    super.onPause()

    lifecycleScope.launch {
        sdk.manualFlush()
    }
}
```

### Custom Workflows

Configure workflows programmatically (advanced):

```kotlin
// Usually workflows are managed via Control Plane UI
// But you can override locally for testing:

val customConfig = DSLConfig(
    version = 999,
    limits = Limits(50, 5000, 24),
    workflows = listOf(
        DSLWorkflow(
            id = "local-test",
            enabled = true,
            trigger = Trigger(
                any = listOf(
                    EventTrigger("test.event", emptyList())
                )
            ),
            actions = listOf(
                DSLAction(
                    flushWindow = FlushWindowAction(2, "session")
                )
            )
        )
    )
)

sdk.setLocalConfig(customConfig)
```

## Best Practices

### Event Naming

Use a consistent naming scheme:

```kotlin
// Good: category.action
"screen.view"
"button.click"
"http.request"
"error.caught"
"purchase.completed"

// Bad: inconsistent
"screenView"
"click_button"
"HttpRequest"
"ERROR"
```

### Attribute Types

Use appropriate data types:

```kotlin
// Good
sdk.captureEvent("event", mapOf(
    "duration_ms" to 1500,          // Number, not string
    "success" to true,               // Boolean, not "true"
    "count" to 5,                    // Number
    "user_id" to "user-123"          // String
))

// Bad
sdk.captureEvent("event", mapOf(
    "duration_ms" to "1500",         // Should be number
    "success" to "true",             // Should be boolean
    "count" to "5"                   // Should be number
))
```

### Sensitive Data

Never capture sensitive data:

```kotlin
// BAD - Don't do this!
sdk.captureEvent("login", mapOf(
    "password" to userPassword,      // Never!
    "credit_card" to cardNumber,     // Never!
    "ssn" to socialSecurity          // Never!
))

// Good - Capture only non-sensitive data
sdk.captureEvent("login", mapOf(
    "user_id" to userId,
    "success" to true,
    "method" to "email"
))
```

### Event Volume

Balance detail vs. volume:

```kotlin
// Good - Important events
sdk.captureEvent("screen.view")
sdk.captureEvent("button.click")
sdk.captureEvent("http.error")
sdk.captureEvent("crash")

// Avoid - Too frequent events
// Don't capture:
// - Every touch event
// - Every scroll position
// - Every animation frame
```

### Lifecycle Events

Capture key lifecycle events:

```kotlin
class MainActivity : AppCompatActivity() {
    private val sdk = ObservabilitySDK.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sdk.captureEvent("screen.created", mapOf(
            "screen_name" to "MainActivity"
        ))
    }

    override fun onResume() {
        super.onResume()
        sdk.captureEvent("screen.resumed", mapOf(
            "screen_name" to "MainActivity"
        ))
    }

    override fun onPause() {
        super.onPause()
        sdk.captureEvent("screen.paused", mapOf(
            "screen_name" to "MainActivity"
        ))
    }
}
```

## Performance Considerations

### SDK Overhead

The SDK is designed for minimal overhead:

- **CPU**: < 1% average
- **Memory**: ~10-50 MB (buffer + database)
- **Network**: Only when flushing (workflow-triggered)
- **Battery**: Negligible

### Optimizations

#### 1. Async Operations

All SDK operations are async:

```kotlin
// Capture is non-blocking
sdk.captureEvent("event")  // Returns immediately

// Event is processed on background thread
```

#### 2. Buffer Management

Events are efficiently buffered:

```kotlin
// RAM buffer: Fast, limited size (default 5000 events)
// Disk buffer: Larger, auto-evicts old events (default 50 MB, 24h)

// No need to manage manually - SDK handles it
```

#### 3. Batch Flushing

Events are batched when flushed:

```kotlin
// When workflow triggers:
// - Collects all matching events from buffer
// - Sends in single HTTP request
// - Efficient use of network
```

### Proguard Rules

Add to `proguard-rules.pro`:

```proguard
# ObservabilitySDK
-keep class com.yourcompany.observability.** { *; }
-keep class com.yourcompany.observability.buffer.** { *; }
-keep class com.yourcompany.observability.workflow.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
```

## Troubleshooting

### SDK Not Initializing

**Problem**: `IllegalStateException: SDK not initialized`

**Solution**:
```kotlin
// Ensure initialization in Application.onCreate()
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ObservabilitySDK.initialize(this, gatewayUrl)
    }
}

// And registered in AndroidManifest.xml
```

### Events Not Captured

**Problem**: No events in logs

**Solution**:
```bash
# Check logs
adb logcat | grep "ObservabilitySDK"

# Should see:
# "Captured event: [event_name]"
# "Adding event to buffer"

# If not, check initialization
```

### Gateway Connection Fails

**Problem**: `Connection refused`

**Solution**:
```kotlin
// Emulator: Use 10.0.2.2
private const val GATEWAY_URL = "http://10.0.2.2:8080"

// Physical device: Use local IP
private const val GATEWAY_URL = "http://192.168.1.100:8080"

// Production: Use domain
private const val GATEWAY_URL = "https://gateway.yourcompany.com"
```

### Workflows Not Triggering

**Problem**: Events captured but not flushed

**Solution**:
```bash
# Check workflow config
adb logcat | grep "Fetched config version"

# Check evaluation
adb logcat | grep "WorkflowEvaluator"

# Ensure event attributes match workflow predicates exactly
```

## API Reference

### ObservabilitySDK

#### initialize()

```kotlin
fun initialize(
    context: Context,
    gatewayUrl: String,
    appId: String = context.packageName,
    diskMb: Long = 50,
    ramEvents: Int = 5000,
    configPollIntervalMs: Long = 60000
): ObservabilitySDK
```

Initialize the SDK (call once in Application.onCreate()).

**Parameters:**
- `context`: Application context
- `gatewayUrl`: Gateway API URL
- `appId`: Application identifier (default: package name)
- `diskMb`: Disk buffer size in MB (default: 50)
- `ramEvents`: RAM buffer size in events (default: 5000)
- `configPollIntervalMs`: Config polling interval (default: 60000ms)

**Returns:** SDK instance

#### getInstance()

```kotlin
fun getInstance(): ObservabilitySDK
```

Get SDK instance (must call initialize() first).

**Returns:** SDK instance

**Throws:** `IllegalStateException` if not initialized

#### captureEvent()

```kotlin
fun captureEvent(
    eventName: String,
    attributes: Map<String, Any> = emptyMap()
)
```

Capture an event with optional attributes.

**Parameters:**
- `eventName`: Event identifier (e.g., "screen.view")
- `attributes`: Event attributes (default: empty)

#### setGlobalAttributes()

```kotlin
fun setGlobalAttributes(attributes: Map<String, Any>)
```

Set attributes to be added to all events.

**Parameters:**
- `attributes`: Global attributes

#### identifyUser()

```kotlin
fun identifyUser(
    userId: String,
    attributes: Map<String, Any> = emptyMap()
)
```

Associate events with a user.

**Parameters:**
- `userId`: User identifier
- `attributes`: User attributes (default: empty)

#### clearUser()

```kotlin
fun clearUser()
```

Clear user identification (e.g., on logout).

#### markCrash()

```kotlin
fun markCrash()
```

Mark a crash for recovery on next launch.

#### manualFlush()

```kotlin
suspend fun manualFlush()
```

Manually flush all buffered events (rare, usually automatic).

### Event

```kotlin
data class Event(
    val eventName: String,
    val timestamp: Long,
    val attributes: Map<String, Any> = emptyMap()
)
```

### DSLConfig

```kotlin
data class DSLConfig(
    val version: Int,
    val limits: Limits,
    val workflows: List<DSLWorkflow>
)

data class Limits(
    val diskMb: Long,
    val ramEvents: Int,
    val retentionHours: Int
)

data class DSLWorkflow(
    val id: String,
    val enabled: Boolean,
    val trigger: Trigger,
    val actions: List<DSLAction>
)
```

## Examples

### Example 1: E-commerce App

```kotlin
class CheckoutActivity : AppCompatActivity() {
    private val sdk = ObservabilitySDK.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Screen view
        sdk.captureEvent("screen.view", mapOf(
            "screen_name" to "Checkout"
        ))
    }

    private fun processPayment(amount: Double) {
        val startTime = System.currentTimeMillis()

        try {
            // Process payment
            paymentApi.charge(amount)

            // Success
            sdk.captureEvent("purchase.completed", mapOf(
                "amount" to amount,
                "currency" to "USD",
                "duration_ms" to (System.currentTimeMillis() - startTime)
            ))

        } catch (e: PaymentException) {
            // Failure
            sdk.captureEvent("purchase.failed", mapOf(
                "amount" to amount,
                "currency" to "USD",
                "error_code" to e.code,
                "error_message" to e.message,
                "duration_ms" to (System.currentTimeMillis() - startTime)
            ))

            showError()
        }
    }
}
```

### Example 2: Social Media App

```kotlin
class FeedActivity : AppCompatActivity() {
    private val sdk = ObservabilitySDK.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadFeed()
    }

    private fun loadFeed() {
        val startTime = System.currentTimeMillis()

        lifecycleScope.launch {
            try {
                val posts = feedApi.getPosts()
                val duration = System.currentTimeMillis() - startTime

                // Track load time
                if (duration > 3000) {
                    sdk.captureEvent("feed.slow_load", mapOf(
                        "duration_ms" to duration,
                        "post_count" to posts.size
                    ))
                }

                displayPosts(posts)

            } catch (e: Exception) {
                sdk.captureEvent("feed.load_error", mapOf(
                    "error" to (e.message ?: ""),
                    "duration_ms" to (System.currentTimeMillis() - startTime)
                ))
            }
        }
    }

    private fun onPostClick(postId: String) {
        sdk.captureEvent("post.click", mapOf(
            "post_id" to postId,
            "screen" to "Feed"
        ))

        // Navigate to post detail
    }
}
```

## Related Documentation

- [Quick Start](QUICK_START.md) - Get up and running
- [User Guide](USER_GUIDE.md) - Control Plane UI usage
- [API Reference](API_REFERENCE.md) - Gateway API docs
- [Troubleshooting](TROUBLESHOOTING_GUIDE.md) - Common issues

---

**SDK Version:** 1.0.0
**Last Updated:** 2024-01-20
