# Amplify DataStore Auto-Instrumentation — Design Spec

> **Date:** 2026-04-13
> **Status:** Approved
> **Deal driver:** Innovapptive — sync failures in field conditions (mines, factories)
> **Phase:** 13.6 (SYNC-001 through SYNC-005)

---

## Problem

Innovapptive uses AWS Amplify DataStore for offline-first data sync in their Kotlin Android app. Their #1 pain is sync failures between Start and End events during network drops. No observability vendor (Datadog, Splunk, Sentry) auto-instruments Amplify DataStore sync lifecycle. They currently have 70% data accuracy with MixPanel and can't explain why syncs fail mid-session.

## Goal

An auto-instrumentation module that captures the full Amplify DataStore sync lifecycle as OTel signals — spans, logs, metrics — with zero code changes beyond adding a Gradle dependency. When a sync fails, the existing policy engine triggers a selective flush of the surrounding context to Dash0.

## Architecture Decision

**Auto-attach via Amplify Hub** (not opt-in wiring). `Amplify.Hub` is a global event bus that fires for all DataStore operations. The module subscribes during `install()` without needing the developer to pass DataStore instances. This delivers the "zero code changes" promise for the beta.

**Single-class Hub listener** (not multi-handler). The Hub event set is finite (~8 types). A single `AmplifyDataStoreInstrumentation` class with a `when` block is the right abstraction. Extract handlers later if scope grows.

---

## Module Structure

```
instrumentation/amplify-datastore/
├── build.gradle.kts
├── src/main/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/
│   ├── AmplifyDataStoreInstrumentation.kt
│   └── AmplifyDataStoreConfig.kt
├── src/main/resources/META-INF/services/
│   └── io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation
└── src/test/java/io/opentelemetry/android/mobile/instrumentation/amplifydatastore/
    ├── AmplifyDataStoreInstrumentationTest.kt
    └── AmplifyDataStoreConfigTest.kt
```

### Gradle Dependencies

```kotlin
plugins { id("com.android.library") }

dependencies {
    api(project(":otel-android-mobile-core"))
    
    // compileOnly — module compiles against Amplify but doesn't pull it in.
    // At runtime, if Amplify isn't on classpath, install() silently no-ops.
    compileOnly("com.amplifyframework:core-kotlin:2.25.2")
    
    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing:1.58.0")
}
```

Key: `compileOnly` for Amplify. The module must work when Amplify is absent — `install()` checks via `Class.forName()` and returns silently if not found.

---

## Configuration

```kotlin
@Incubating
data class AmplifyDataStoreConfig(
    val enabled: Boolean = true,
    val captureOutboxEvents: Boolean = true,
    val captureSyncSpans: Boolean = true,
    val captureSyncMetrics: Boolean = true,
    val captureConflicts: Boolean = true,
    val attachNetworkState: Boolean = true,
    val syncTimeoutMs: Long = 60_000L
)
```

Follows the project's config pattern (ErrorConfig, NetworkConfig, FreezeConfig). All signals enabled by default — toggle off for noise reduction.

---

## OTel Signals

### Spans (via Tracer)

| Span Name | Trigger Start | Trigger End | Attributes |
|-----------|--------------|-------------|------------|
| `datastore.sync` | Hub: `syncStarted` | Hub: `syncQueriesReady` | `sync.model_count` (int), `sync.direction` (string: "download"), `network.type`, `mobile.session.id` |

The sync span covers the full initial sync cycle. Duration feeds the `datastore.sync.latency` histogram for p95/p90 reporting.

### Log Events (via Logger)

| Body | Severity | Hub Event | Attributes |
|------|----------|-----------|------------|
| `datastore.outbox.enqueued` | INFO | `outboxMutationEnqueued` | `mutation.model` (string), `mutation.type` (create/update/delete) |
| `datastore.outbox.processed` | INFO | `outboxMutationProcessed` (success) | `mutation.model`, `mutation.type`, `mutation.success` (bool: true) |
| `datastore.outbox.conflict` | WARN | `outboxMutationProcessed` (failure) | `mutation.model`, `mutation.type`, `mutation.success` (bool: false), `conflict.strategy` |
| `datastore.model.synced` | INFO | `modelSynced` | `sync.model`, `sync.added` (int), `sync.updated` (int), `sync.deleted` (int) |
| `datastore.sync.failed` | ERROR | Subscription error / network failure / timeout | `error.type`, `error.message`, `network.type`, `network.subtype` |
| `datastore.network.changed` | INFO | `networkStatusChanged` | `network.type`, `network.subtype`, `network.previous_type` |
| `datastore.subscription.established` | INFO | `subscriptionEstablished` | `network.type` |

`datastore.sync.failed` at ERROR severity is the policy trigger — matches the existing crash/freeze/error pattern.

**Conflict detection:** Amplify DataStore does not fire a dedicated `conflict` Hub event. Instead, conflicts surface via `outboxMutationProcessed` with a failure payload — the `OutboxMutationEvent.element.model` and the applied conflict resolution strategy are extracted from the event data. When `mutation.success = false`, the module emits `datastore.outbox.conflict` at WARN severity instead of the normal `datastore.outbox.processed` INFO event.

**Network status changes:** Amplify Hub fires `networkStatusChanged` on the DataStore channel when connectivity changes during sync operations. This is captured as `datastore.network.changed` with the current and previous network types — giving exact timestamps of network transitions relative to sync lifecycle events. Critical for Innovapptive's field-condition debugging.

### Metrics (via Meter)

| Metric Name | Type | Description |
|-------------|------|-------------|
| `datastore.outbox.depth` | UpDownCounter | +1 on enqueue, -1 on processed. Shows "how much data is stuck on device" |
| `datastore.sync.success.count` | Counter | +1 per successful `modelSynced` |
| `datastore.sync.failure.count` | Counter | +1 per sync failure |
| `datastore.sync.latency` | Histogram | Duration (ms) of `datastore.sync` span. Feeds p95/p90 views in Dash0 |

---

## Network Correlation

Every emitted signal carries current network state as attributes:

- `network.type`: wifi / cellular / none / ethernet / other
- `network.subtype`: lte / 3g / 2g / etc. (cellular only)

Read from `ConnectivityManager.getActiveNetwork()` + `NetworkCapabilities` at event time. Same pattern as existing `OTelNetworkInterceptor`. When `network.type = "none"`, this directly explains why a sync failed — the exact context Innovapptive needs.

---

## install() Flow

**Thread safety:** `activeSyncSpan` is accessed from Hub callbacks (Amplify's background thread) and from the timeout scheduler. It is stored as an `AtomicReference<Span?>` to avoid data races. The timeout `ScheduledFuture` is cancelled on normal sync completion and the executor is shut down in `uninstall()`.

```kotlin
// Thread-safe state
private val activeSyncSpan = AtomicReference<Span?>(null)
private var syncStartTimeMs: Long = 0L
private var hubSubscriptionToken: SubscriptionToken? = null
private var syncTimeoutFuture: ScheduledFuture<*>? = null
private val executor = Executors.newSingleThreadScheduledExecutor()

override fun install(application: Application, context: InstrumentationContext) {
    if (!config.enabled) return

    // Guard: Amplify must be on the classpath
    try {
        Class.forName("com.amplifyframework.hub.HubChannel")
    } catch (e: ClassNotFoundException) {
        Log.i(TAG, "Amplify not found on classpath — skipping DataStore instrumentation")
        return
    }

    val tracer = context.tracer(instrumentationName)
    val logger = context.logger(instrumentationName)
    val meter = context.meter(instrumentationName)
    
    // Initialize metrics
    outboxDepth = meter.upDownCounterBuilder("datastore.outbox.depth").build()
    syncSuccessCount = meter.counterBuilder("datastore.sync.success.count").build()
    syncFailureCount = meter.counterBuilder("datastore.sync.failure.count").build()
    syncLatency = meter.histogramBuilder("datastore.sync.latency")
        .setUnit("ms").build()

    // Subscribe to DataStore Hub events — capture token for uninstall()
    hubSubscriptionToken = Amplify.Hub.subscribe(HubChannel.DATASTORE) { event ->
        try {
            handleHubEvent(event, tracer, logger, context)
        } catch (e: Exception) {
            Log.w(TAG, "Error handling Hub event: ${event.name}", e)
        }
    }
}
```

### Hub Event Handler

```kotlin
private fun handleHubEvent(
    event: HubEvent<*>,
    tracer: Tracer,
    logger: Logger,
    context: InstrumentationContext
) {
    val networkAttrs = if (config.attachNetworkState) getNetworkAttributes(context.application) else emptyMap()
    val sessionId = context.sessionProvider.getSessionId()

    when (event.name) {
        "syncStarted" -> {
            if (!config.captureSyncSpans) return
            val span = tracer.spanBuilder("datastore.sync")
                .setAttribute("network.type", networkAttrs["network.type"] ?: "unknown")
                .setAttribute("mobile.session.id", sessionId)
                .startSpan()
            activeSyncSpan.set(span)
            syncStartTimeMs = SystemClock.elapsedRealtime()
            
            // Schedule timeout — cancelled if sync completes normally
            syncTimeoutFuture?.cancel(false)
            syncTimeoutFuture = executor.schedule({
                activeSyncSpan.getAndSet(null)?.let { staleSpan ->
                    staleSpan.setStatus(StatusCode.ERROR, "Sync timeout")
                    staleSpan.end()
                    logger.logRecordBuilder()
                        .setBody("datastore.sync.failed")
                        .setSeverity(Severity.ERROR)
                        .setAllAttributes(buildFailureAttributes(
                            "timeout",
                            "Sync did not complete within ${config.syncTimeoutMs}ms",
                            networkAttrs, sessionId
                        ))
                        .emit()
                    syncFailureCount.add(1)
                }
            }, config.syncTimeoutMs, TimeUnit.MILLISECONDS)
            
            context.addBreadcrumb(JourneyBreadcrumb.network("sync", "sync.started", networkAttrs))
        }
        
        "syncQueriesReady" -> {
            if (!config.captureSyncSpans) return
            // Cancel the timeout — sync completed normally
            syncTimeoutFuture?.cancel(false)
            syncTimeoutFuture = null
            activeSyncSpan.getAndSet(null)?.end()
            if (config.captureSyncMetrics && syncStartTimeMs > 0) {
                val duration = SystemClock.elapsedRealtime() - syncStartTimeMs
                syncLatency.record(duration.toDouble())
            }
        }
        
        "outboxMutationEnqueued" -> {
            if (!config.captureOutboxEvents) return
            logger.logRecordBuilder()
                .setBody("datastore.outbox.enqueued")
                .setSeverity(Severity.INFO)
                .setAllAttributes(buildOutboxAttributes(event, networkAttrs, sessionId))
                .emit()
            outboxDepth.add(1)
            context.addBreadcrumb(JourneyBreadcrumb.network("sync", "outbox.enqueued", networkAttrs))
        }
        
        "outboxMutationProcessed" -> {
            if (!config.captureOutboxEvents) return
            val success = extractMutationSuccess(event)
            if (success) {
                logger.logRecordBuilder()
                    .setBody("datastore.outbox.processed")
                    .setSeverity(Severity.INFO)
                    .setAllAttributes(buildOutboxAttributes(event, networkAttrs, sessionId))
                    .emit()
            } else if (config.captureConflicts) {
                // Conflict: outboxMutationProcessed with failure payload
                logger.logRecordBuilder()
                    .setBody("datastore.outbox.conflict")
                    .setSeverity(Severity.WARN)
                    .setAllAttributes(buildConflictAttributes(event, networkAttrs, sessionId))
                    .emit()
                context.addBreadcrumb(JourneyBreadcrumb.error("sync", "outbox.conflict", "Mutation conflict", networkAttrs))
            }
            outboxDepth.add(-1)
        }
        
        "modelSynced" -> {
            if (!config.captureSyncMetrics) return
            val data = event.data  // ModelSyncedEvent
            logger.logRecordBuilder()
                .setBody("datastore.model.synced")
                .setSeverity(Severity.INFO)
                .setAllAttributes(buildModelSyncedAttributes(data, networkAttrs, sessionId))
                .emit()
            syncSuccessCount.add(1)
        }
        
        "networkStatusChanged" -> {
            if (!config.attachNetworkState) return
            logger.logRecordBuilder()
                .setBody("datastore.network.changed")
                .setSeverity(Severity.INFO)
                .setAllAttributes(buildNetworkChangedAttributes(event, networkAttrs, sessionId))
                .emit()
            context.addBreadcrumb(JourneyBreadcrumb.network("sync", "network.changed", networkAttrs))
        }
        
        "subscriptionEstablished" -> {
            logger.logRecordBuilder()
                .setBody("datastore.subscription.established")
                .setSeverity(Severity.INFO)
                .setAllAttributes(buildBaseAttributes(networkAttrs, sessionId))
                .emit()
        }
    }
}
```

### Sync Failure Detection

Amplify DataStore subscription failures surface as Hub events with error payloads or as DataStore exceptions. The module detects failures via:

1. **Hub error events** — `event.data` is a `DataStoreException`
2. **Sync span timeout** — if `syncStarted` fires but `syncQueriesReady` never arrives within a configurable timeout (default 60s), the scheduled task in the `syncStarted` handler fires, emits `datastore.sync.failed`, and ends the span with ERROR status. If `syncQueriesReady` arrives first, the `ScheduledFuture` is cancelled.

The timeout scheduling is shown inline in the `syncStarted` handler above. The `AtomicReference.getAndSet(null)` pattern ensures that at most one thread (either the `syncQueriesReady` callback or the timeout task) acts on the span — the loser gets `null` and no-ops.

---

## Selective Flush Integration

The module emits `datastore.sync.failed` as an ERROR log event. The existing policy engine matches on body + severity. A default policy in the bundled config triggers selective flush:

```json
{
    "id": "amplify-sync-failure",
    "name": "Amplify Sync Failure Handler",
    "enabled": true,
    "trigger": {
        "all": [{
            "event": "datastore.sync.failed",
            "where": []
        }]
    },
    "actions": [{
        "type": "flush_window",
        "minutes": 2,
        "scope": "session"
    }]
}
```

No policy engine changes required. The demo moment: sync fails → 2 minutes of context (taps, screen views, network state, outbox mutations) automatically exported to Dash0.

---

## Breadcrumb Integration

Sync events are added to the journey breadcrumb buffer via `InstrumentationContext.addBreadcrumb()`:

- `JourneyBreadcrumb.network("sync", "sync.started", attrs)` — sync lifecycle begins
- `JourneyBreadcrumb.network("sync", "outbox.enqueued", attrs)` — local mutation queued
- `JourneyBreadcrumb.error("sync", "outbox.conflict", message, attrs)` — mutation conflict detected
- `JourneyBreadcrumb.network("sync", "network.changed", attrs)` — connectivity changed during sync
- `JourneyBreadcrumb.error("sync", "sync.failed", message, attrs)` — sync failure (timeout or error)

These breadcrumbs attach to crash/error reports, giving full sync context when something goes wrong.

---

## uninstall()

```kotlin
override fun uninstall() {
    hubSubscriptionToken?.let { Amplify.Hub.unsubscribe(it) }
    hubSubscriptionToken = null
    activeSyncSpan.getAndSet(null)?.end()
    syncTimeoutFuture?.cancel(false)
    syncTimeoutFuture = null
    executor.shutdownNow()
}
```

---

## Testing Strategy

All tests use fabricated `HubEvent` objects — no Amplify SDK at test time (it's `compileOnly`). Create a test helper that builds `HubEvent<*>` with the correct name and data payload.

| Test | What it validates |
|------|-------------------|
| `syncStarted creates span` | Tracer.spanBuilder called, span is active via AtomicReference |
| `syncQueriesReady ends span and records latency` | Span ended, histogram recorded, timeout future cancelled |
| `syncQueriesReady cancels timeout` | ScheduledFuture is cancelled, timeout task is no-op |
| `outboxMutationEnqueued emits log and increments depth` | Logger emits, counter = +1 |
| `outboxMutationProcessed success emits log and decrements depth` | Logger emits, counter back to 0 |
| `outboxMutationProcessed failure emits conflict at WARN` | Logger emits datastore.outbox.conflict, severity WARN |
| `modelSynced emits log and increments success` | Logger emits with model attrs, counter +1 |
| `networkStatusChanged emits network change log` | Logger emits datastore.network.changed with type attrs |
| `sync timeout emits failure` | After timeout, ERROR log emitted, span ended with ERROR |
| `timeout and completion race is safe` | getAndSet(null) ensures only one path acts on the span |
| `network state attached to all signals` | Every emit includes network.type |
| `session.id attached to all signals` | Every emit includes mobile.session.id |
| `config.enabled=false is no-op` | No Hub subscription, no signals |
| `config.captureOutboxEvents=false suppresses outbox signals` | Outbox events ignored |
| `config.captureSyncSpans=false suppresses spans` | No span created |
| `config.captureSyncMetrics=false suppresses metrics` | No counter/histogram |
| `config.captureConflicts=false suppresses conflict events` | Failed mutations emit processed, not conflict |
| `config.syncTimeoutMs configures timeout duration` | Custom timeout value used in scheduled task |
| `missing Amplify classpath is silent no-op` | install() returns without error |
| `breadcrumbs added for sync events` | BreadcrumbManager.add() called for start, enqueue, conflict, network change |
| `multiple syncs create separate spans` | Second syncStarted after syncQueriesReady starts new span |
| `enqueue/process cycle tracks depth correctly` | 3 enqueues + 2 processes = depth 1 |
| `uninstall shuts down executor` | executor.shutdownNow() called, no leaked threads |

---

## Demo App Integration

Add to `examples/demo-app/settings.gradle.kts`:
```kotlin
include(":instrumentation-amplify-datastore")
project(":instrumentation-amplify-datastore").projectDir = file("../../instrumentation/amplify-datastore")
```

Add to `examples/demo-app/android/build.gradle.kts`:
```kotlin
implementation(project(":instrumentation-amplify-datastore"))
```

For the demo app (which doesn't use Amplify), the module silently no-ops. For Innovapptive's beta, they add the same Gradle line and get auto-instrumentation.

---

## Out of Scope (for this spec)

- MongoDB Realm instrumentation (separate module, separate spec)
- React Native bridge (Phase 19)
- Amplify Auth/API/Storage instrumentation (DataStore only for now)
- Custom conflict handler wrapping (we capture conflicts visible via Hub; custom handler interception requires bytecode manipulation)
