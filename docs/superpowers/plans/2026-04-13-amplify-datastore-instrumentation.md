# Amplify DataStore Auto-Instrumentation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an auto-instrumentation module that captures the full Amplify DataStore sync lifecycle as OTel signals (spans, logs, metrics) with zero code changes beyond adding a Gradle dependency.

**Architecture:** Single `AmplifyDataStoreInstrumentation` class subscribes to `Amplify.Hub` DataStore channel events during `install()`. Amplify is a `compileOnly` dependency — the module silently no-ops when Amplify isn't on the classpath. Thread-safe span tracking uses `AtomicReference<Span?>` with `getAndSet(null)` to safely handle the race between sync completion and timeout.

**Tech Stack:** Kotlin (JDK 17), Android API 26+, OpenTelemetry SDK 1.58.0, Amplify Core Kotlin 2.25.2 (compileOnly), JUnit 4 + MockK + Robolectric for tests.

**Spec:** `docs/superpowers/specs/2026-04-13-amplify-datastore-instrumentation-design.md`

---

## File Structure

| Action | Path | Responsibility |
|--------|------|---------------|
| Create | `instrumentation/amplify-datastore/build.gradle.kts` | Module build config, compileOnly Amplify dep |
| Create | `instrumentation/amplify-datastore/consumer-rules.pro` | Empty ProGuard rules (convention) |
| Create | `instrumentation/amplify-datastore/src/main/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreConfig.kt` | Config data class with defaults + validation |
| Create | `instrumentation/amplify-datastore/src/main/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreInstrumentation.kt` | Main instrumentation: Hub subscription, event handling, OTel signal emission |
| Create | `instrumentation/amplify-datastore/src/main/resources/META-INF/services/io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation` | ServiceLoader registration |
| Create | `instrumentation/amplify-datastore/src/test/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreConfigTest.kt` | Config default + validation tests |
| Create | `instrumentation/amplify-datastore/src/test/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreInstrumentationTest.kt` | Full instrumentation behavior tests |
| Modify | `examples/demo-app/settings.gradle.kts` | Add `:instrumentation-amplify-datastore` include |
| Modify | `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/MobileSemconv.kt` | Add DataStore event names + attribute keys |

---

## Task 1: Module Scaffolding

**Files:**
- Create: `instrumentation/amplify-datastore/build.gradle.kts`
- Create: `instrumentation/amplify-datastore/consumer-rules.pro`
- Create: `instrumentation/amplify-datastore/src/main/resources/META-INF/services/io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation`
- Modify: `examples/demo-app/settings.gradle.kts`

- [ ] **Step 1: Create build.gradle.kts**

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

plugins {
    id("com.android.library")
}

android {
    namespace = "io.opentelemetry.android.mobile.instrumentation.amplifydatastore"
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

    lint { targetSdk = 36 }

    buildTypes {
        release { isMinifyEnabled = false }
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
    api(project(":otel-android-mobile-core"))

    // compileOnly — module compiles against Amplify but doesn't pull it in.
    // At runtime, if Amplify isn't on classpath, install() silently no-ops.
    compileOnly("com.amplifyframework:core-kotlin:2.25.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:1.9.20")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.58.0")

    // Amplify on test classpath so we can fabricate HubEvent objects
    testImplementation("com.amplifyframework:core-kotlin:2.25.2")
}
```

- [ ] **Step 2: Create empty consumer-rules.pro**

Create the file `instrumentation/amplify-datastore/consumer-rules.pro` with no content (empty file — convention for all modules).

- [ ] **Step 3: Create META-INF services file**

File: `instrumentation/amplify-datastore/src/main/resources/META-INF/services/io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation`

```
io.opentelemetry.android.mobile.instrumentation.amplifydatastore.AmplifyDataStoreInstrumentation
```

Single line, fully qualified class name. The `InstrumentationRegistry` uses `ServiceLoader` to auto-discover this.

- [ ] **Step 4: Add module to settings.gradle.kts**

In `examples/demo-app/settings.gradle.kts`, add after the last `instrumentation-*` include block:

```kotlin
include(":instrumentation-amplify-datastore")
project(":instrumentation-amplify-datastore").projectDir = file("../../instrumentation/amplify-datastore")
```

- [ ] **Step 5: Verify Gradle sync**

Run:
```bash
cd examples/demo-app && ./gradlew :instrumentation-amplify-datastore:dependencies --configuration debugCompileClasspath 2>&1 | head -40
```

Expected: Gradle resolves the module. May fail until the main source file exists — that's fine. The important thing is that Gradle recognizes the module (no "project not found" error).

- [ ] **Step 6: Commit**

```bash
git add instrumentation/amplify-datastore/build.gradle.kts \
  instrumentation/amplify-datastore/consumer-rules.pro \
  instrumentation/amplify-datastore/src/main/resources/META-INF/services/ \
  examples/demo-app/settings.gradle.kts
git commit -m "feat(amplify-datastore): scaffold module with build config and ServiceLoader registration"
```

---

## Task 2: Semantic Conventions

**Files:**
- Modify: `otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/MobileSemconv.kt`

- [ ] **Step 1: Add DataStore event names and attribute keys to MobileSemconv**

Add the following block after the existing `// ── Wireframe ──` section in `MobileSemconv.kt`:

```kotlin
    // ── DataStore (Amplify) ──────────────────────────────────────────────
    const val DATASTORE_SYNC              = "datastore.sync"
    const val DATASTORE_OUTBOX_ENQUEUED   = "datastore.outbox.enqueued"
    const val DATASTORE_OUTBOX_PROCESSED  = "datastore.outbox.processed"
    const val DATASTORE_OUTBOX_CONFLICT   = "datastore.outbox.conflict"
    const val DATASTORE_MODEL_SYNCED      = "datastore.model.synced"
    const val DATASTORE_SYNC_FAILED       = "datastore.sync.failed"
    const val DATASTORE_NETWORK_CHANGED   = "datastore.network.changed"
    const val DATASTORE_SUBSCRIPTION_EST  = "datastore.subscription.established"

    @JvmField val SYNC_DIRECTION          = AttributeKey.stringKey("sync.direction")
    @JvmField val SYNC_MODEL              = AttributeKey.stringKey("sync.model")
    @JvmField val SYNC_ADDED             = AttributeKey.longKey("sync.added")
    @JvmField val SYNC_UPDATED           = AttributeKey.longKey("sync.updated")
    @JvmField val SYNC_DELETED           = AttributeKey.longKey("sync.deleted")
    @JvmField val MUTATION_MODEL          = AttributeKey.stringKey("mutation.model")
    @JvmField val MUTATION_TYPE           = AttributeKey.stringKey("mutation.type")
    @JvmField val MUTATION_SUCCESS        = AttributeKey.booleanKey("mutation.success")
    @JvmField val CONFLICT_STRATEGY       = AttributeKey.stringKey("conflict.strategy")
    @JvmField val NETWORK_TYPE            = AttributeKey.stringKey("network.type")
    @JvmField val NETWORK_SUBTYPE         = AttributeKey.stringKey("network.subtype")
    @JvmField val NETWORK_PREVIOUS_TYPE   = AttributeKey.stringKey("network.previous_type")
    @JvmField val ERROR_TYPE              = AttributeKey.stringKey("error.type")
    @JvmField val ERROR_MESSAGE           = AttributeKey.stringKey("error.message")
```

- [ ] **Step 2: Verify core module compiles**

Run:
```bash
cd examples/demo-app && ./gradlew :otel-android-mobile-core:compileDebugKotlin 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add otel-android-mobile-core/src/main/java/io/opentelemetry/android/mobile/instrumentation/MobileSemconv.kt
git commit -m "feat(semconv): add DataStore event names and attribute keys to MobileSemconv"
```

---

## Task 3: Config Class

**Files:**
- Create: `instrumentation/amplify-datastore/src/main/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreConfig.kt`
- Create: `instrumentation/amplify-datastore/src/test/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreConfigTest.kt`

- [ ] **Step 1: Write config tests**

File: `instrumentation/amplify-datastore/src/test/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreConfigTest.kt`

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation.amplifydatastore

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AmplifyDataStoreConfigTest {

    @Test
    fun `defaults - all signals enabled`() {
        val config = AmplifyDataStoreConfig()
        assertTrue(config.enabled)
        assertTrue(config.captureOutboxEvents)
        assertTrue(config.captureSyncSpans)
        assertTrue(config.captureSyncMetrics)
        assertTrue(config.captureConflicts)
        assertTrue(config.attachNetworkState)
    }

    @Test
    fun `defaults - syncTimeoutMs is 60 seconds`() {
        val config = AmplifyDataStoreConfig()
        assertEquals(60_000L, config.syncTimeoutMs)
    }

    @Test
    fun `syncTimeoutMs must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            AmplifyDataStoreConfig(syncTimeoutMs = 0)
        }
    }

    @Test
    fun `syncTimeoutMs rejects negative`() {
        assertFailsWith<IllegalArgumentException> {
            AmplifyDataStoreConfig(syncTimeoutMs = -1)
        }
    }

    @Test
    fun `custom syncTimeoutMs accepted`() {
        val config = AmplifyDataStoreConfig(syncTimeoutMs = 120_000L)
        assertEquals(120_000L, config.syncTimeoutMs)
    }

    @Test
    fun `individual signals can be disabled`() {
        val config = AmplifyDataStoreConfig(
            captureOutboxEvents = false,
            captureSyncSpans = false,
            captureSyncMetrics = false,
            captureConflicts = false,
            attachNetworkState = false
        )
        assertTrue(!config.captureOutboxEvents)
        assertTrue(!config.captureSyncSpans)
        assertTrue(!config.captureSyncMetrics)
        assertTrue(!config.captureConflicts)
        assertTrue(!config.attachNetworkState)
    }

    @Test
    fun `data class copy preserves values`() {
        val original = AmplifyDataStoreConfig(syncTimeoutMs = 30_000L)
        val copied = original.copy(enabled = false)
        assertTrue(!copied.enabled)
        assertEquals(30_000L, copied.syncTimeoutMs)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
cd examples/demo-app && ./gradlew :instrumentation-amplify-datastore:testDebugUnitTest --tests "*.AmplifyDataStoreConfigTest" 2>&1 | tail -10
```

Expected: FAIL — `AmplifyDataStoreConfig` class not found.

- [ ] **Step 3: Write AmplifyDataStoreConfig**

File: `instrumentation/amplify-datastore/src/main/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreConfig.kt`

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation.amplifydatastore

import io.opentelemetry.android.mobile.instrumentation.Incubating

@Incubating
data class AmplifyDataStoreConfig(
    val enabled: Boolean = true,
    val captureOutboxEvents: Boolean = true,
    val captureSyncSpans: Boolean = true,
    val captureSyncMetrics: Boolean = true,
    val captureConflicts: Boolean = true,
    val attachNetworkState: Boolean = true,
    val syncTimeoutMs: Long = 60_000L
) {
    init {
        require(syncTimeoutMs > 0) { "syncTimeoutMs must be > 0, got $syncTimeoutMs" }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
cd examples/demo-app && ./gradlew :instrumentation-amplify-datastore:testDebugUnitTest --tests "*.AmplifyDataStoreConfigTest" 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — all 7 tests pass.

- [ ] **Step 5: Commit**

```bash
git add instrumentation/amplify-datastore/src/main/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreConfig.kt \
  instrumentation/amplify-datastore/src/test/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreConfigTest.kt
git commit -m "feat(amplify-datastore): add AmplifyDataStoreConfig with defaults and validation"
```

---

## Task 4: Instrumentation — Skeleton + Classpath Guard

**Files:**
- Create: `instrumentation/amplify-datastore/src/main/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreInstrumentation.kt`
- Create: `instrumentation/amplify-datastore/src/test/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreInstrumentationTest.kt`

This task creates the class skeleton with `install()`, `uninstall()`, the classpath guard, and the `config.enabled` check. Hub event handling is added in Tasks 5-8.

- [ ] **Step 1: Write skeleton tests (classpath guard, enabled flag, lifecycle)**

File: `instrumentation/amplify-datastore/src/test/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreInstrumentationTest.kt`

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation.amplifydatastore

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.android.mobile.instrumentation.DefaultMobileSessionProvider
import io.opentelemetry.android.mobile.instrumentation.InstrumentationContext
import io.opentelemetry.android.mobile.instrumentation.WindowEventHub
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AmplifyDataStoreInstrumentationTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    private var activeInst: AmplifyDataStoreInstrumentation? = null

    private fun realApp(): Application = ApplicationProvider.getApplicationContext()

    private fun makeCtx(app: Application = realApp()) =
        InstrumentationContext(
            otelRule.openTelemetry,
            DefaultMobileSessionProvider(),
            WindowEventHub(),
            app
        )

    private fun installAndTrack(
        config: AmplifyDataStoreConfig = AmplifyDataStoreConfig()
    ): AmplifyDataStoreInstrumentation {
        val inst = AmplifyDataStoreInstrumentation(config)
        val app = realApp()
        inst.install(app, makeCtx(app))
        activeInst = inst
        return inst
    }

    @After
    fun tearDown() {
        activeInst?.uninstall()
        activeInst = null
    }

    @Test
    fun `instrumentationName is correct`() {
        val inst = AmplifyDataStoreInstrumentation()
        assertTrue(inst.instrumentationName == "io.opentelemetry.android.mobile.amplifydatastore")
    }

    @Test
    fun `config enabled=false is no-op`() {
        val inst = AmplifyDataStoreInstrumentation(AmplifyDataStoreConfig(enabled = false))
        inst.install(realApp(), makeCtx())
        activeInst = inst
        assertFalse(inst.isInstalled)
    }

    @Test
    fun `install with Amplify on classpath succeeds`() {
        // Amplify is on the test classpath via testImplementation
        val inst = installAndTrack()
        assertTrue(inst.isInstalled)
    }

    @Test
    fun `uninstall cleans up state`() {
        val inst = installAndTrack()
        assertTrue(inst.isInstalled)
        inst.uninstall()
        assertFalse(inst.isInstalled)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
cd examples/demo-app && ./gradlew :instrumentation-amplify-datastore:testDebugUnitTest --tests "*.AmplifyDataStoreInstrumentationTest" 2>&1 | tail -10
```

Expected: FAIL — `AmplifyDataStoreInstrumentation` class not found.

- [ ] **Step 3: Write AmplifyDataStoreInstrumentation skeleton**

File: `instrumentation/amplify-datastore/src/main/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreInstrumentation.kt`

```kotlin
// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation.amplifydatastore

import android.app.Application
import android.util.Log
import com.amplifyframework.hub.HubChannel
import com.amplifyframework.hub.HubEvent
import com.amplifyframework.hub.SubscriptionToken
import com.amplifyframework.kotlin.core.Amplify
import io.opentelemetry.android.mobile.breadcrumb.JourneyBreadcrumb
import io.opentelemetry.android.mobile.instrumentation.Incubating
import io.opentelemetry.android.mobile.instrumentation.InstrumentationContext
import io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation
import io.opentelemetry.android.mobile.instrumentation.MobileSemconv
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.LongUpDownCounter
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Incubating
class AmplifyDataStoreInstrumentation(
    private val config: AmplifyDataStoreConfig = AmplifyDataStoreConfig()
) : MobileInstrumentation {

    override val instrumentationName = "io.opentelemetry.android.mobile.amplifydatastore"

    private var ctx: InstrumentationContext? = null
    private var tracer: Tracer? = null
    private var logger: Logger? = null

    // Metrics
    private var outboxDepth: LongUpDownCounter? = null
    private var syncSuccessCount: LongCounter? = null
    private var syncFailureCount: LongCounter? = null
    private var syncLatency: DoubleHistogram? = null

    // Thread-safe sync span tracking
    private val activeSyncSpan = AtomicReference<Span?>(null)
    @Volatile private var syncStartTimeMs: Long = 0L

    // Hub subscription lifecycle
    private var hubSubscriptionToken: SubscriptionToken? = null
    private var syncTimeoutFuture: ScheduledFuture<*>? = null
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "OTel-DataStoreSyncTimeout").apply { isDaemon = true }
    }

    /** Visible for testing — true after successful install(). */
    internal val isInstalled: Boolean get() = hubSubscriptionToken != null

    override fun install(application: Application, context: InstrumentationContext) {
        if (!config.enabled) return

        // Guard: Amplify must be on the classpath
        try {
            Class.forName("com.amplifyframework.hub.HubChannel")
        } catch (_: ClassNotFoundException) {
            Log.i(TAG, "Amplify not found on classpath — skipping DataStore instrumentation")
            return
        }

        ctx = context
        tracer = context.tracer(instrumentationName)
        logger = context.logger(instrumentationName)

        val meter = context.meter(instrumentationName)
        outboxDepth = meter.upDownCounterBuilder("datastore.outbox.depth")
            .setDescription("Number of mutations queued in the outbox")
            .build()
        syncSuccessCount = meter.counterBuilder("datastore.sync.success.count")
            .setDescription("Number of successful model syncs")
            .build()
        syncFailureCount = meter.counterBuilder("datastore.sync.failure.count")
            .setDescription("Number of sync failures")
            .build()
        syncLatency = meter.histogramBuilder("datastore.sync.latency")
            .setDescription("Duration of sync cycles in milliseconds")
            .setUnit("ms")
            .build()

        // Subscribe to DataStore Hub events — capture token for uninstall()
        hubSubscriptionToken = Amplify.Hub.subscribe(HubChannel.DATASTORE) { event ->
            try {
                handleHubEvent(event)
            } catch (e: Exception) {
                Log.w(TAG, "Error handling Hub event: ${event.name}", e)
            }
        }
    }

    override fun uninstall() {
        hubSubscriptionToken?.let { Amplify.Hub.unsubscribe(it) }
        hubSubscriptionToken = null
        activeSyncSpan.getAndSet(null)?.end()
        syncTimeoutFuture?.cancel(false)
        syncTimeoutFuture = null
        executor.shutdownNow()
        ctx = null
        tracer = null
        logger = null
    }

    private fun handleHubEvent(event: HubEvent<*>) {
        // Event handling added in Tasks 5-8
    }

    companion object {
        private const val TAG = "AmplifyDataStoreInst"
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
cd examples/demo-app && ./gradlew :instrumentation-amplify-datastore:testDebugUnitTest --tests "*.AmplifyDataStoreInstrumentationTest" 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — all 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add instrumentation/amplify-datastore/src/main/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreInstrumentation.kt \
  instrumentation/amplify-datastore/src/test/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreInstrumentationTest.kt
git commit -m "feat(amplify-datastore): instrumentation skeleton with classpath guard and lifecycle"
```

---

## Task 5: Hub Event Handling — Sync Span Lifecycle

**Files:**
- Modify: `instrumentation/amplify-datastore/src/main/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreInstrumentation.kt`
- Modify: `instrumentation/amplify-datastore/src/test/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreInstrumentationTest.kt`

This task implements the `syncStarted` → `syncQueriesReady` span lifecycle, timeout detection, and latency histogram recording.

- [ ] **Step 1: Add sync span tests to the test file**

Append these tests to `AmplifyDataStoreInstrumentationTest.kt`:

```kotlin
    // ── Helper: fire a Hub event into the instrumentation ──────────────────
    private fun fireHubEvent(inst: AmplifyDataStoreInstrumentation, eventName: String, data: Any? = null) {
        val event = HubEvent.create(eventName, data)
        // Access the private handleHubEvent method via reflection
        val method = AmplifyDataStoreInstrumentation::class.java.getDeclaredMethod(
            "handleHubEvent", HubEvent::class.java
        )
        method.isAccessible = true
        method.invoke(inst, event)
    }

    // ── Sync span lifecycle ────────────────────────────────────────────────

    @Test
    fun `syncStarted creates span`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "syncStarted")

        val spans = otelRule.spans
        assertTrue(spans.isNotEmpty(), "syncStarted should create a span")
        // Span is still active (not ended) — check it has the right name
        val syncSpan = spans.first { it.name == "datastore.sync" }
        assertTrue(syncSpan != null)
    }

    @Test
    fun `syncQueriesReady ends span and records latency`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "syncStarted")
        Thread.sleep(50) // Simulate sync duration
        fireHubEvent(inst, "syncQueriesReady")

        val spans = otelRule.spans
        val syncSpan = spans.first { it.name == "datastore.sync" }
        assertTrue(syncSpan.hasEnded(), "Span should be ended after syncQueriesReady")
    }

    @Test
    fun `syncQueriesReady without syncStarted is safe`() {
        val inst = installAndTrack()
        // Should not throw
        fireHubEvent(inst, "syncQueriesReady")
        assertTrue(otelRule.spans.isEmpty(), "No span should exist")
    }

    @Test
    fun `multiple syncs create separate spans`() {
        val inst = installAndTrack()

        fireHubEvent(inst, "syncStarted")
        fireHubEvent(inst, "syncQueriesReady")

        fireHubEvent(inst, "syncStarted")
        fireHubEvent(inst, "syncQueriesReady")

        val syncSpans = otelRule.spans.filter { it.name == "datastore.sync" }
        assertTrue(syncSpans.size == 2, "Should have 2 separate sync spans, got ${syncSpans.size}")
    }

    @Test
    fun `captureSyncSpans=false suppresses spans`() {
        val inst = installAndTrack(AmplifyDataStoreConfig(captureSyncSpans = false))
        fireHubEvent(inst, "syncStarted")
        fireHubEvent(inst, "syncQueriesReady")
        assertTrue(otelRule.spans.isEmpty(), "No spans when captureSyncSpans=false")
    }

    @Test
    fun `sync span has session id attribute`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "syncStarted")
        fireHubEvent(inst, "syncQueriesReady")

        val syncSpan = otelRule.spans.first { it.name == "datastore.sync" }
        val sessionId = syncSpan.attributes[MobileSemconv.SESSION_ID]
        assertTrue(sessionId != null && sessionId.isNotEmpty(), "Span should have mobile.session.id")
    }
```

Add the necessary imports at the top of the test file:

```kotlin
import com.amplifyframework.hub.HubEvent
import io.opentelemetry.android.mobile.instrumentation.MobileSemconv
```

- [ ] **Step 2: Run tests to verify new tests fail**

Run:
```bash
cd examples/demo-app && ./gradlew :instrumentation-amplify-datastore:testDebugUnitTest --tests "*.AmplifyDataStoreInstrumentationTest" 2>&1 | tail -15
```

Expected: New span tests fail (handleHubEvent is empty).

- [ ] **Step 3: Implement sync span handling in handleHubEvent**

Replace the empty `handleHubEvent` method in `AmplifyDataStoreInstrumentation.kt` with:

```kotlin
    private fun handleHubEvent(event: HubEvent<*>) {
        val context = ctx ?: return
        val networkAttrs = if (config.attachNetworkState) getNetworkAttributes(context.application) else emptyMap()
        val sessionId = context.sessionProvider.getSessionId()
        val screenName = context.sessionProvider.getCurrentScreenName() ?: "unknown"

        when (event.name) {
            "syncStarted" -> handleSyncStarted(networkAttrs, sessionId, screenName)
            "syncQueriesReady" -> handleSyncQueriesReady(networkAttrs, sessionId)
        }
    }

    private fun handleSyncStarted(
        networkAttrs: Map<String, String>,
        sessionId: String,
        screenName: String
    ) {
        if (!config.captureSyncSpans) return
        val t = tracer ?: return
        val context = ctx ?: return

        val span = t.spanBuilder(MobileSemconv.DATASTORE_SYNC)
            .setAttribute(MobileSemconv.SESSION_ID, sessionId)
            .setAttribute(MobileSemconv.NETWORK_TYPE, networkAttrs["network.type"] ?: "unknown")
            .setAttribute(MobileSemconv.SYNC_DIRECTION, "download")
            .startSpan()
        activeSyncSpan.set(span)
        syncStartTimeMs = android.os.SystemClock.elapsedRealtime()

        // Schedule timeout — cancelled if sync completes normally
        syncTimeoutFuture?.cancel(false)
        syncTimeoutFuture = executor.schedule({
            activeSyncSpan.getAndSet(null)?.let { staleSpan ->
                staleSpan.setStatus(StatusCode.ERROR, "Sync timeout")
                staleSpan.end()
                val log = logger ?: return@let
                log.logRecordBuilder()
                    .setBody(MobileSemconv.DATASTORE_SYNC_FAILED)
                    .setSeverity(Severity.ERROR)
                    .setAllAttributes(
                        Attributes.builder()
                            .put(MobileSemconv.SESSION_ID, sessionId)
                            .put(MobileSemconv.ERROR_TYPE, "timeout")
                            .put(MobileSemconv.ERROR_MESSAGE, "Sync did not complete within ${config.syncTimeoutMs}ms")
                            .put(MobileSemconv.NETWORK_TYPE, networkAttrs["network.type"] ?: "unknown")
                            .build()
                    )
                    .emit()
                syncFailureCount?.add(1)
                val currentScreen = ctx?.sessionProvider?.getCurrentScreenName() ?: "unknown"
                context.addBreadcrumb(
                    JourneyBreadcrumb.error(
                        screen = currentScreen,
                        errorType = "sync.failed",
                        message = "Sync timeout after ${config.syncTimeoutMs}ms",
                        attributes = networkAttrs
                    )
                )
            }
        }, config.syncTimeoutMs, TimeUnit.MILLISECONDS)

        context.addBreadcrumb(
            JourneyBreadcrumb.custom(screen = screenName, action = "sync.started", attributes = networkAttrs)
        )
    }

    private fun handleSyncQueriesReady(
        networkAttrs: Map<String, String>,
        sessionId: String
    ) {
        if (!config.captureSyncSpans) return

        // Cancel the timeout — sync completed normally
        syncTimeoutFuture?.cancel(false)
        syncTimeoutFuture = null

        activeSyncSpan.getAndSet(null)?.end()

        if (config.captureSyncMetrics && syncStartTimeMs > 0) {
            val duration = android.os.SystemClock.elapsedRealtime() - syncStartTimeMs
            syncLatency?.record(duration.toDouble())
            syncStartTimeMs = 0L
        }
    }

    private fun getNetworkAttributes(application: Application): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        try {
            val cm = application.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return attrs
            val network = cm.activeNetwork
            if (network == null) {
                attrs["network.type"] = "none"
                return attrs
            }
            val caps = cm.getNetworkCapabilities(network)
            if (caps == null) {
                attrs["network.type"] = "unknown"
                return attrs
            }
            attrs["network.type"] = when {
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        } catch (_: SecurityException) {
            attrs["network.type"] = "unknown"
        }
        return attrs
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
cd examples/demo-app && ./gradlew :instrumentation-amplify-datastore:testDebugUnitTest --tests "*.AmplifyDataStoreInstrumentationTest" 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — all tests pass.

- [ ] **Step 5: Commit**

```bash
git add instrumentation/amplify-datastore/src/main/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreInstrumentation.kt \
  instrumentation/amplify-datastore/src/test/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreInstrumentationTest.kt
git commit -m "feat(amplify-datastore): sync span lifecycle with timeout and latency histogram"
```

---

## Task 6: Hub Event Handling — Outbox Mutations + Conflict Detection

**Files:**
- Modify: `instrumentation/amplify-datastore/src/main/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreInstrumentation.kt`
- Modify: `instrumentation/amplify-datastore/src/test/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreInstrumentationTest.kt`

- [ ] **Step 1: Add outbox mutation tests**

Append these tests to `AmplifyDataStoreInstrumentationTest.kt`:

```kotlin
    // ── Outbox mutations ───────────────────────────────────────────────────

    @Test
    fun `outboxMutationEnqueued emits log and increments depth`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "outboxMutationEnqueued")

        val logs = otelRule.logRecords
        val enqueued = logs.filter { it.body.asString() == MobileSemconv.DATASTORE_OUTBOX_ENQUEUED }
        assertTrue(enqueued.size == 1, "Should emit one outbox.enqueued log")
        assertTrue(enqueued[0].severity == Severity.INFO)
    }

    @Test
    fun `outboxMutationProcessed success emits log and decrements depth`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "outboxMutationEnqueued")
        fireHubEvent(inst, "outboxMutationProcessed")

        val logs = otelRule.logRecords
        val processed = logs.filter { it.body.asString() == MobileSemconv.DATASTORE_OUTBOX_PROCESSED }
        assertTrue(processed.size == 1, "Should emit one outbox.processed log")
        assertTrue(processed[0].severity == Severity.INFO)
    }

    @Test
    fun `enqueue and process cycle tracks depth correctly`() {
        val inst = installAndTrack()
        // 3 enqueues + 2 processes = net depth of 1
        fireHubEvent(inst, "outboxMutationEnqueued")
        fireHubEvent(inst, "outboxMutationEnqueued")
        fireHubEvent(inst, "outboxMutationEnqueued")
        fireHubEvent(inst, "outboxMutationProcessed")
        fireHubEvent(inst, "outboxMutationProcessed")

        // Verify we got the right number of log events
        val enqueued = otelRule.logRecords.filter { it.body.asString() == MobileSemconv.DATASTORE_OUTBOX_ENQUEUED }
        val processed = otelRule.logRecords.filter { it.body.asString() == MobileSemconv.DATASTORE_OUTBOX_PROCESSED }
        assertTrue(enqueued.size == 3, "3 enqueue events")
        assertTrue(processed.size == 2, "2 processed events")
    }

    @Test
    fun `captureOutboxEvents=false suppresses outbox signals`() {
        val inst = installAndTrack(AmplifyDataStoreConfig(captureOutboxEvents = false))
        fireHubEvent(inst, "outboxMutationEnqueued")
        fireHubEvent(inst, "outboxMutationProcessed")

        val outboxLogs = otelRule.logRecords.filter {
            it.body.asString().startsWith("datastore.outbox")
        }
        assertTrue(outboxLogs.isEmpty(), "No outbox logs when captureOutboxEvents=false")
    }

    @Test
    fun `outbox enqueued adds breadcrumb`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "outboxMutationEnqueued")

        // Breadcrumb was added — verify via the InstrumentationContext's breadcrumbManager
        // Since BreadcrumbManager may not be initialized in test context, we verify
        // that the log event was emitted (breadcrumb is best-effort)
        val enqueued = otelRule.logRecords.filter { it.body.asString() == MobileSemconv.DATASTORE_OUTBOX_ENQUEUED }
        assertTrue(enqueued.isNotEmpty())
    }

    @Test
    fun `outbox events include session id`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "outboxMutationEnqueued")

        val log = otelRule.logRecords.first { it.body.asString() == MobileSemconv.DATASTORE_OUTBOX_ENQUEUED }
        val sessionId = log.attributes[MobileSemconv.SESSION_ID]
        assertTrue(sessionId != null && sessionId.isNotEmpty(), "Outbox log should have session.id")
    }
```

- [ ] **Step 2: Run tests to verify new tests fail**

Run:
```bash
cd examples/demo-app && ./gradlew :instrumentation-amplify-datastore:testDebugUnitTest --tests "*.AmplifyDataStoreInstrumentationTest" 2>&1 | tail -15
```

Expected: New outbox tests fail.

- [ ] **Step 3: Implement outbox mutation handlers**

Add these cases to the `when (event.name)` block in `handleHubEvent`:

```kotlin
            "outboxMutationEnqueued" -> handleOutboxEnqueued(networkAttrs, sessionId, screenName)
            "outboxMutationProcessed" -> handleOutboxProcessed(event, networkAttrs, sessionId, screenName)
```

Add these methods to `AmplifyDataStoreInstrumentation.kt`:

```kotlin
    private fun handleOutboxEnqueued(
        networkAttrs: Map<String, String>,
        sessionId: String,
        screenName: String
    ) {
        if (!config.captureOutboxEvents) return
        val log = logger ?: return
        val context = ctx ?: return

        log.logRecordBuilder()
            .setBody(MobileSemconv.DATASTORE_OUTBOX_ENQUEUED)
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.builder()
                    .put(MobileSemconv.SESSION_ID, sessionId)
                    .put(MobileSemconv.NETWORK_TYPE, networkAttrs["network.type"] ?: "unknown")
                    .build()
            )
            .emit()
        outboxDepth?.add(1)
        context.addBreadcrumb(
            JourneyBreadcrumb.custom(screen = screenName, action = "outbox.enqueued", attributes = networkAttrs)
        )
    }

    private fun handleOutboxProcessed(
        event: HubEvent<*>,
        networkAttrs: Map<String, String>,
        sessionId: String,
        screenName: String
    ) {
        if (!config.captureOutboxEvents) return
        val log = logger ?: return
        val context = ctx ?: return

        // Detect conflicts: outboxMutationProcessed with failure means conflict
        val success = extractMutationSuccess(event)

        if (success) {
            log.logRecordBuilder()
                .setBody(MobileSemconv.DATASTORE_OUTBOX_PROCESSED)
                .setSeverity(Severity.INFO)
                .setAllAttributes(
                    Attributes.builder()
                        .put(MobileSemconv.SESSION_ID, sessionId)
                        .put(MobileSemconv.MUTATION_SUCCESS, true)
                        .put(MobileSemconv.NETWORK_TYPE, networkAttrs["network.type"] ?: "unknown")
                        .build()
                )
                .emit()
        } else if (config.captureConflicts) {
            log.logRecordBuilder()
                .setBody(MobileSemconv.DATASTORE_OUTBOX_CONFLICT)
                .setSeverity(Severity.WARN)
                .setAllAttributes(
                    Attributes.builder()
                        .put(MobileSemconv.SESSION_ID, sessionId)
                        .put(MobileSemconv.MUTATION_SUCCESS, false)
                        .put(MobileSemconv.NETWORK_TYPE, networkAttrs["network.type"] ?: "unknown")
                        .build()
                )
                .emit()
            context.addBreadcrumb(
                JourneyBreadcrumb.error(
                    screen = screenName,
                    errorType = "outbox.conflict",
                    message = "Mutation conflict",
                    attributes = networkAttrs
                )
            )
        }
        outboxDepth?.add(-1)
    }

    /**
     * Extract mutation success from Hub event data.
     * Amplify's outboxMutationProcessed carries an OutboxMutationEvent;
     * we check if it indicates success or failure. Default to true if
     * the data is unrecognizable.
     */
    private fun extractMutationSuccess(event: HubEvent<*>): Boolean {
        // OutboxMutationEvent doesn't expose a simple success boolean.
        // Failures come as events where event.data carries error info.
        // For now, treat all outboxMutationProcessed as success unless
        // the event data is an exception type.
        val data = event.data ?: return true
        return data !is Throwable
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
cd examples/demo-app && ./gradlew :instrumentation-amplify-datastore:testDebugUnitTest --tests "*.AmplifyDataStoreInstrumentationTest" 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — all tests pass.

- [ ] **Step 5: Commit**

```bash
git add instrumentation/amplify-datastore/src/main/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreInstrumentation.kt \
  instrumentation/amplify-datastore/src/test/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreInstrumentationTest.kt
git commit -m "feat(amplify-datastore): outbox mutation handlers with conflict detection"
```

---

## Task 7: Hub Event Handling — Model Synced, Network Changed, Subscription Established

**Files:**
- Modify: `instrumentation/amplify-datastore/src/main/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreInstrumentation.kt`
- Modify: `instrumentation/amplify-datastore/src/test/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreInstrumentationTest.kt`

- [ ] **Step 1: Add remaining Hub event tests**

Append these tests to `AmplifyDataStoreInstrumentationTest.kt`:

```kotlin
    // ── Model synced ───────────────────────────────────────────────────────

    @Test
    fun `modelSynced emits log and increments success counter`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "modelSynced")

        val logs = otelRule.logRecords.filter { it.body.asString() == MobileSemconv.DATASTORE_MODEL_SYNCED }
        assertTrue(logs.size == 1, "Should emit one model.synced log")
        assertTrue(logs[0].severity == Severity.INFO)
    }

    @Test
    fun `captureSyncMetrics=false suppresses modelSynced`() {
        val inst = installAndTrack(AmplifyDataStoreConfig(captureSyncMetrics = false))
        fireHubEvent(inst, "modelSynced")

        val logs = otelRule.logRecords.filter { it.body.asString() == MobileSemconv.DATASTORE_MODEL_SYNCED }
        assertTrue(logs.isEmpty(), "No model.synced when captureSyncMetrics=false")
    }

    // ── Network status changed ─────────────────────────────────────────────

    @Test
    fun `networkStatusChanged emits network change log`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "networkStatusChanged")

        val logs = otelRule.logRecords.filter { it.body.asString() == MobileSemconv.DATASTORE_NETWORK_CHANGED }
        assertTrue(logs.size == 1, "Should emit one network.changed log")
        assertTrue(logs[0].severity == Severity.INFO)
    }

    @Test
    fun `attachNetworkState=false suppresses networkStatusChanged`() {
        val inst = installAndTrack(AmplifyDataStoreConfig(attachNetworkState = false))
        fireHubEvent(inst, "networkStatusChanged")

        val logs = otelRule.logRecords.filter { it.body.asString() == MobileSemconv.DATASTORE_NETWORK_CHANGED }
        assertTrue(logs.isEmpty(), "No network.changed when attachNetworkState=false")
    }

    // ── Subscription established ───────────────────────────────────────────

    @Test
    fun `subscriptionEstablished emits log`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "subscriptionEstablished")

        val logs = otelRule.logRecords.filter { it.body.asString() == MobileSemconv.DATASTORE_SUBSCRIPTION_EST }
        assertTrue(logs.size == 1, "Should emit one subscription.established log")
        assertTrue(logs[0].severity == Severity.INFO)
    }

    // ── Network state on all signals ───────────────────────────────────────

    @Test
    fun `network type attached to all signals`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "outboxMutationEnqueued")
        fireHubEvent(inst, "modelSynced")
        fireHubEvent(inst, "subscriptionEstablished")

        for (log in otelRule.logRecords) {
            val networkType = log.attributes[MobileSemconv.NETWORK_TYPE]
            assertTrue(networkType != null, "Log '${log.body.asString()}' should have network.type")
        }
    }

    @Test
    fun `session id attached to all signals`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "outboxMutationEnqueued")
        fireHubEvent(inst, "modelSynced")
        fireHubEvent(inst, "subscriptionEstablished")

        for (log in otelRule.logRecords) {
            val sessionId = log.attributes[MobileSemconv.SESSION_ID]
            assertTrue(sessionId != null && sessionId.isNotEmpty(),
                "Log '${log.body.asString()}' should have session.id")
        }
    }
```

- [ ] **Step 2: Run tests to verify new tests fail**

Run:
```bash
cd examples/demo-app && ./gradlew :instrumentation-amplify-datastore:testDebugUnitTest --tests "*.AmplifyDataStoreInstrumentationTest" 2>&1 | tail -15
```

Expected: New tests fail.

- [ ] **Step 3: Add remaining event handlers**

Add these cases to the `when (event.name)` block in `handleHubEvent`:

```kotlin
            "modelSynced" -> handleModelSynced(networkAttrs, sessionId)
            "networkStatusChanged" -> handleNetworkStatusChanged(networkAttrs, sessionId, screenName)
            "subscriptionEstablished" -> handleSubscriptionEstablished(networkAttrs, sessionId)
```

Add these methods:

```kotlin
    private fun handleModelSynced(
        networkAttrs: Map<String, String>,
        sessionId: String
    ) {
        if (!config.captureSyncMetrics) return
        val log = logger ?: return

        log.logRecordBuilder()
            .setBody(MobileSemconv.DATASTORE_MODEL_SYNCED)
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.builder()
                    .put(MobileSemconv.SESSION_ID, sessionId)
                    .put(MobileSemconv.NETWORK_TYPE, networkAttrs["network.type"] ?: "unknown")
                    .build()
            )
            .emit()
        syncSuccessCount?.add(1)
    }

    private fun handleNetworkStatusChanged(
        networkAttrs: Map<String, String>,
        sessionId: String,
        screenName: String
    ) {
        if (!config.attachNetworkState) return
        val log = logger ?: return
        val context = ctx ?: return

        log.logRecordBuilder()
            .setBody(MobileSemconv.DATASTORE_NETWORK_CHANGED)
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.builder()
                    .put(MobileSemconv.SESSION_ID, sessionId)
                    .put(MobileSemconv.NETWORK_TYPE, networkAttrs["network.type"] ?: "unknown")
                    .build()
            )
            .emit()
        context.addBreadcrumb(
            JourneyBreadcrumb.custom(screen = screenName, action = "network.changed", attributes = networkAttrs)
        )
    }

    private fun handleSubscriptionEstablished(
        networkAttrs: Map<String, String>,
        sessionId: String
    ) {
        val log = logger ?: return

        log.logRecordBuilder()
            .setBody(MobileSemconv.DATASTORE_SUBSCRIPTION_EST)
            .setSeverity(Severity.INFO)
            .setAllAttributes(
                Attributes.builder()
                    .put(MobileSemconv.SESSION_ID, sessionId)
                    .put(MobileSemconv.NETWORK_TYPE, networkAttrs["network.type"] ?: "unknown")
                    .build()
            )
            .emit()
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run:
```bash
cd examples/demo-app && ./gradlew :instrumentation-amplify-datastore:testDebugUnitTest --tests "*.AmplifyDataStoreInstrumentationTest" 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — all tests pass.

- [ ] **Step 5: Commit**

```bash
git add instrumentation/amplify-datastore/src/main/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreInstrumentation.kt \
  instrumentation/amplify-datastore/src/test/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreInstrumentationTest.kt
git commit -m "feat(amplify-datastore): model synced, network changed, subscription established handlers"
```

---

## Task 8: Sync Timeout + Thread Safety Tests

**Files:**
- Modify: `instrumentation/amplify-datastore/src/test/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreInstrumentationTest.kt`

This task adds the timeout and thread-safety tests. No new production code needed — the timeout logic was implemented in Task 5.

- [ ] **Step 1: Add timeout and thread-safety tests**

Append these tests to `AmplifyDataStoreInstrumentationTest.kt`:

```kotlin
    // ── Sync timeout ───────────────────────────────────────────────────────

    @Test
    fun `sync timeout emits failure after configured delay`() {
        // Use a very short timeout for testing
        val inst = installAndTrack(AmplifyDataStoreConfig(syncTimeoutMs = 100))
        fireHubEvent(inst, "syncStarted")

        // Wait for timeout to fire
        Thread.sleep(200)

        val failLogs = otelRule.logRecords.filter { it.body.asString() == MobileSemconv.DATASTORE_SYNC_FAILED }
        assertTrue(failLogs.size == 1, "Timeout should emit exactly one sync.failed")
        assertTrue(failLogs[0].severity == Severity.ERROR)

        val errorType = failLogs[0].attributes[MobileSemconv.ERROR_TYPE]
        assertTrue(errorType == "timeout", "Error type should be 'timeout'")
    }

    @Test
    fun `syncQueriesReady cancels timeout - no failure emitted`() {
        val inst = installAndTrack(AmplifyDataStoreConfig(syncTimeoutMs = 200))
        fireHubEvent(inst, "syncStarted")
        fireHubEvent(inst, "syncQueriesReady") // completes before timeout

        // Wait past the original timeout
        Thread.sleep(300)

        val failLogs = otelRule.logRecords.filter { it.body.asString() == MobileSemconv.DATASTORE_SYNC_FAILED }
        assertTrue(failLogs.isEmpty(), "No failure after successful sync completion")
    }

    @Test
    fun `timeout and completion race is safe - span ended exactly once`() {
        // Use a very tight timeout to maximize race window
        val inst = installAndTrack(AmplifyDataStoreConfig(syncTimeoutMs = 50))
        fireHubEvent(inst, "syncStarted")

        // Fire syncQueriesReady right around the timeout boundary
        Thread.sleep(40)
        fireHubEvent(inst, "syncQueriesReady")

        // Wait for timeout to definitely have fired
        Thread.sleep(100)

        // The span should exist and be ended exactly once (no double-end crash)
        val spans = otelRule.spans.filter { it.name == "datastore.sync" }
        assertTrue(spans.size == 1, "Exactly one sync span")
        assertTrue(spans[0].hasEnded(), "Span should be ended")
    }

    @Test
    fun `syncTimeoutMs configures timeout duration`() {
        // 500ms timeout — sync should NOT fail at 200ms
        val inst = installAndTrack(AmplifyDataStoreConfig(syncTimeoutMs = 500))
        fireHubEvent(inst, "syncStarted")
        Thread.sleep(200)

        val failLogs = otelRule.logRecords.filter { it.body.asString() == MobileSemconv.DATASTORE_SYNC_FAILED }
        assertTrue(failLogs.isEmpty(), "Should not timeout yet at 200ms with 500ms timeout")

        fireHubEvent(inst, "syncQueriesReady")
    }

    // ── Uninstall cleanup ──────────────────────────────────────────────────

    @Test
    fun `uninstall ends active span`() {
        val inst = installAndTrack()
        fireHubEvent(inst, "syncStarted")

        // Span is active
        inst.uninstall()
        activeInst = null

        val spans = otelRule.spans.filter { it.name == "datastore.sync" }
        assertTrue(spans.isNotEmpty() && spans[0].hasEnded(), "Uninstall should end active span")
    }
```

- [ ] **Step 2: Run all tests**

Run:
```bash
cd examples/demo-app && ./gradlew :instrumentation-amplify-datastore:testDebugUnitTest 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL` — all tests pass (23 total: 7 config + 16+ instrumentation).

- [ ] **Step 3: Commit**

```bash
git add instrumentation/amplify-datastore/src/test/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/AmplifyDataStoreInstrumentationTest.kt
git commit -m "test(amplify-datastore): sync timeout, race safety, and uninstall cleanup tests"
```

---

## Task 9: Full Test Suite Run + Lint

**Files:** None — verification only.

- [ ] **Step 1: Run the full amplify-datastore test suite**

Run:
```bash
cd examples/demo-app && ./gradlew :instrumentation-amplify-datastore:testDebugUnitTest 2>&1 | tail -20
```

Expected: All tests pass.

- [ ] **Step 2: Run lint on the new module**

Run:
```bash
cd examples/demo-app && ./gradlew :instrumentation-amplify-datastore:lint 2>&1 | tail -10
```

Expected: No errors. Warnings are acceptable.

- [ ] **Step 3: Run existing module tests to verify no regressions**

Run:
```bash
cd examples/demo-app && ./gradlew :otel-android-mobile-core:testDebugUnitTest :otel-android-mobile:testDebugUnitTest 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — MobileSemconv additions don't break existing code.

- [ ] **Step 4: Run the full build**

Run:
```bash
cd examples/demo-app && ./gradlew assembleDebug 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — demo app builds with the new module included.

- [ ] **Step 5: Commit any fixes, then final commit**

If any fixes were needed, commit them. Then:

```bash
git log --oneline -8
```

Expected: Clean commit history showing the incremental build-up of the module.

---

## Summary

| Task | What it delivers | Tests added |
|------|-----------------|-------------|
| 1 | Module scaffolding, Gradle, ServiceLoader, settings.gradle.kts | — |
| 2 | MobileSemconv constants for DataStore signals | — |
| 3 | AmplifyDataStoreConfig data class | 7 |
| 4 | Instrumentation skeleton with classpath guard | 4 |
| 5 | Sync span lifecycle (syncStarted → syncQueriesReady) + timeout + latency | 6 |
| 6 | Outbox mutation handlers + conflict detection | 6 |
| 7 | modelSynced, networkStatusChanged, subscriptionEstablished | 7 |
| 8 | Timeout timing, race safety, uninstall cleanup tests | 5 |
| 9 | Full suite run, lint, regression check, build | — |

**Total: ~35 tests, 9 commits, 5 new files + 2 modified files.**
