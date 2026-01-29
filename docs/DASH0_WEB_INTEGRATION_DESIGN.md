# Dash0 Web SDK → Mobile OTel Integration Design

## 1. Architecture Sketch

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         APPLICATION LAYER                                │
├─────────────────────────────────────────────────────────────────────────┤
│  Activity/Fragment/Compose UI                                           │
│         │                                                                │
│         ├──► MobileOtel Facade ◄──────────────────────────────────────┐│
│         │    ├─ identify()                                             ││
│         │    ├─ sendEvent()                                            ││
│         │    ├─ reportError()                                          ││
│         │    ├─ addGlobalAttribute()                                   ││
│         │    └─ setModuleEnabled()                                     ││
│         │                                                               ││
│         ▼                                                               ││
├─────────────────────────────────────────────────────────────────────────┤
│                    INSTRUMENTATION MODULES (opt-in)                      │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                 │
│  │ MobileVitals │  │MobileNavigation│ │MobileNetwork│                  │
│  ├──────────────┤  ├──────────────┤  ├──────────────┤                 │
│  │•Cold/Warm    │  │•Activity      │  │•OkHttp       │                 │
│  │ Start Time   │  │•Fragment      │  │•Interceptor  │                 │
│  │•TTID         │  │•Compose Nav   │  │•Propagation  │                 │
│  │•Jank/Dropped │  │•Deep Links    │  │•Timing       │                 │
│  │ Frames       │  │•Breadcrumbs   │  │•Headers      │                 │
│  │•Input Latency│  │               │  │              │                 │
│  │•ANR Signals  │  │               │  │              │                 │
│  │•Memory Press │  │               │  │              │                 │
│  │•Thermal      │  │               │  │              │                 │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘                 │
│         │                  │                  │                          │
│  ┌──────────────┐  ┌──────────────┐                                    │
│  │ MobileErrors │  │ MobileEvents │                                     │
│  ├──────────────┤  ├──────────────┤                                     │
│  │•Uncaught Exc │  │•sendEvent()  │                                     │
│  │•Coroutine    │  │•Severity     │                                     │
│  │•RxJava       │  │•Namespace    │                                     │
│  │•Dedupe       │  │ Protection   │                                     │
│  │•Rate Limit   │  │              │                                     │
│  └──────┬───────┘  └──────┬───────┘                                     │
│         │                  │                                             │
│         └──────────┬───────┴─────────────────────┐                     │
│                    ▼                               │                     │
├─────────────────────────────────────────────────────────────────────────┤
│                   CORE EVENT PIPELINE                                    │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌───────────────────────────────────────────────────────────────┐     │
│  │  SessionManager                                                │     │
│  │  ├─ session.id (UUID, persisted)                             │     │
│  │  ├─ user.id (identity, optional)                             │     │
│  │  ├─ global attributes (Map<String, Any>)                     │     │
│  │  ├─ foreground/background state                              │     │
│  │  └─ inactivity timer (15 min default)                        │     │
│  └───────────────────┬───────────────────────────────────────────┘     │
│                      │ (enrich all events)                              │
│                      ▼                                                   │
│  ┌───────────────────────────────────────────────────────────────┐     │
│  │  JourneyBreadcrumbBuffer (Circular, N=50)                     │     │
│  │  ├─ timestamp, screen, action, elementId, attributes          │     │
│  │  └─ attached to: crash, error cascade, ui freeze, risk spike  │     │
│  └───────────────────┬───────────────────────────────────────────┘     │
│                      │                                                   │
│                      ▼                                                   │
│  ┌──────────────────────────────────────────────────────────────┐      │
│  │  MobileLoggerProvider (existing)                              │      │
│  │  └─► MobileLogRecordProcessor                                 │      │
│  │       ├─ RAM Buffer (5000 events)                            │      │
│  │       ├─ Disk Buffer (50 MB, Room)                           │      │
│  │       ├─ PolicyEvaluator hook (workflow matching)            │      │
│  │       └─ Export Mode (CONDITIONAL/CONTINUOUS/HYBRID)         │      │
│  └───────────────────┬──────────────────────────────────────────┘      │
│                      │                                                   │
│         ┌────────────┴────────────┐                                     │
│         ▼                          ▼                                     │
│  ┌─────────────┐          ┌──────────────────┐                         │
│  │ Predictive  │          │ Device Health    │                         │
│  │ Telemetry   │◄─────────┤ Monitor          │                         │
│  │ Policy      │          │ (memory, battery,│                         │
│  └──────┬──────┘          │  network, etc.)  │                         │
│         │                 └──────────────────┘                          │
│         │ (triggers vitals snapshot on risk spike)                     │
│         │                                                                │
│         ▼                                                                │
│  ┌─────────────────────────────────────────────────────────────┐       │
│  │  RetryableExporter (exponential backoff)                     │       │
│  └───────────────────────┬─────────────────────────────────────┘       │
│                          │                                              │
│                          ▼                                              │
├─────────────────────────────────────────────────────────────────────────┤
│                     OTEL Collector (mobilepolicyprocessor)              │
│                     ├─ Server-side policy evaluation                    │
│                     ├─ Workflow actions (flush, sample, alert)          │
│                     └─ Backend routing (Jaeger/Tempo/Dash0)             │
└─────────────────────────────────────────────────────────────────────────┘
```

**Key Data Flow:**
1. Instrumentation modules capture signals → enrich with session/global attrs
2. Events flow to MobileLogRecordProcessor (existing two-tier buffer)
3. PolicyEvaluator hooks evaluate workflows (device-side)
4. Export modes control scheduling (CONDITIONAL = trigger-only, CONTINUOUS = periodic)
5. Predictive telemetry can trigger vitals snapshots pre-emptively
6. Journey breadcrumbs attached to critical events before export

---

## 2. Capability Mapping: Dash0 Web → Mobile OTel

| Dash0 Web Capability | Mobile Equivalent (Android) | Location in Repo | Signal Type | Data Model (Attributes/Events) | Sampling/Overhead | Privacy/PII Risks | Policy/Export Mode Leverage |
|---------------------|----------------------------|------------------|-------------|-------------------------------|-------------------|-------------------|----------------------------|
| **Web Vitals (CLS, INP, LCP)** | Cold/Warm Start Time, TTID, Jank (dropped frames), Input Latency, ANR signals, Long main-thread tasks, Memory Pressure, Thermal Throttling | `mobile-vitals/` → `VitalsCollector.kt` | Metrics + Log Events + Span Events | `mobile.vitals.cold_start_ms`, `mobile.vitals.jank_p95`, `mobile.vitals.input_latency_p99`, `mobile.vitals.anr_risk` | Sample on screen transitions; rate-limit to 1/min per metric | None (aggregated stats) | CONDITIONAL: flush on jank spike; Predictive: trigger on thermal/memory pressure |
| **Navigation Instrumentation** | Activity/Fragment lifecycle, Compose Navigation, Deep Links | `mobile-navigation/` → `NavigationInstrumentation.kt` | Log Events (breadcrumbs) + Spans | `screen.name`, `screen.class`, `navigation.route`, `deep_link.uri` (scrubbed), `navigation.type` (push/pop/replace) | Always-on (low cost) | Deep link URLs (scrub query params by default) | Use for window flush context (flush last 2 min on error) |
| **Fetch Instrumentation** | OkHttp Interceptor, HttpURLConnection wrapper | `mobile-network/` → `NetworkInstrumentation.kt` | Spans + Log Events | `http.method`, `http.url` (scrubbed), `http.status_code`, `http.request.body.size_bucket`, `http.response.body.size_bucket`, `network.type` (wifi/cell), `http.timing.*` | Propagate on allowlist hosts; sample 100% by default | URLs (scrub query params, redact auth tokens) | CONDITIONAL: flush on 5xx cascade; Policy: match on `http.status_code >= 500` |
| **Error Capture (auto + manual)** | Uncaught Exception Handler, Coroutine Exception Handler, RxJava hooks, `reportError()` | `mobile-errors/` → `ErrorInstrumentation.kt` | Log Events + Exception Spans | `exception.type`, `exception.message` (scrubbed), `exception.stacktrace` (ProGuard-mapped), `error.fingerprint` (dedupe), `error.rate_limit_key` | Dedupe by fingerprint (5 min window); rate-limit 10/min | Stack traces (scrub file paths with user-specific dirs) | CONDITIONAL: immediate flush on crash; Policy: match on `exception.type` |
| **sendEvent API** | `sendEvent(name, options)` with severity, timestamp, structured data | `mobile-events/` → `EventsApi.kt` | Log Events | `event.name`, `event.severity`, custom attributes (validated), reserved namespace `mobile.*` protected | User-controlled; recommend sampling for high-volume events | Custom attributes (user must scrub PII) | Policy: match on `event.name` or custom attributes |
| **Session Model** | Foreground/background transitions, inactivity timeout (15 min), terminate on logout | `core/` → `SessionManager.kt` | Resource Attributes | `session.id`, `session.start_time`, `session.duration`, `session.state` (active/background/terminated) | Always-on (negligible cost) | None (anonymous UUID) | Session boundaries trigger flush in HYBRID mode |
| **Identity Model** | `identify(user)`, `clearIdentity()` | `core/` → `SessionManager.kt` | Resource Attributes | `user.id`, `user.email` (hashed by default), `user.name` (opt-in), custom user attrs | Always-on | PII (user email/name) - hash by default, require opt-in for plaintext | Used for user cohort targeting in policies |
| **Global Attributes** | `addGlobalAttribute()`, `removeGlobalAttribute()` | `core/` → `SessionManager.kt` | Resource Attributes (attached to all signals) | Custom key-value pairs, persisted to SharedPreferences (encrypted) | Always-on | User-defined (must validate/scrub) | Used for policy matching (e.g., `app.feature_flag = "new_checkout"`) |
| **Early Init Strategy** | Initialize `SessionManager` before `MobileLoggerProvider`, queue early events in memory | `core/` → `EarlyEventQueue.kt` | Internal buffer | N/A | Queue max 100 events pre-init | None | Ensures first events include session/identity even if init delayed |
| **Offline Queueing** | Already handled by `DiskLogBuffer` | Existing: `buffering/DiskLogBuffer.kt` | N/A | N/A | Already implemented | N/A | Already leveraged by CONDITIONAL mode |
| **Filtering/Scrubbing** | PII scrubbers for URLs, deep links, exception messages, stack traces | `core/` → `PiiScrubber.kt` | N/A | Regex-based redaction + allowlist | Always-on (minimal cost) | Prevents leaking PII | Applied before buffering |
| **Propagation Controls** | Trace context propagation with host allowlist/regex | `mobile-network/` → `PropagationConfig.kt` | Span Context | `traceparent`, `tracestate` headers | Always-on for allowlisted hosts | None | Used for distributed tracing across services |

---

## 3. Module Specifications

### A) `mobile-vitals` Module

**Package**: `io.opentelemetry.android.mobile.vitals`

#### What We Measure

| Metric | Definition | Collection Method | Unit | Target P95 |
|--------|-----------|-------------------|------|------------|
| **Cold Start Time** | App process start → first frame rendered | `Application.onCreate()` to `Activity.onResume()` + first `Choreographer.postFrameCallback()` | milliseconds | <2000ms |
| **Warm Start Time** | Activity destroyed → Activity resumed | `Activity.onDestroy()` timestamp to `onResume()` | milliseconds | <500ms |
| **Time to Initial Display (TTID)** | Activity start → first meaningful content visible | `reportFullyDrawn()` or custom marker | milliseconds | <1500ms |
| **Jank (Dropped Frames)** | Frames taking >16.67ms (60fps) or >11.11ms (90fps) | `FrameMetricsAggregator` API 24+ | count per screen | <5% dropped |
| **Input Latency** | Touch event → UI response (frame rendered) | `Choreographer.postFrameCallback()` tracking | milliseconds | <100ms |
| **Long Main Thread Tasks** | Tasks blocking main thread >50ms | `Looper.setMessageLogging()` or Choreographer monitoring | milliseconds | <3 per minute |
| **ANR Risk Signal** | Main thread blocked >3s (ANR threshold is 5s) | Watchdog thread checks main thread heartbeat | boolean | 0 occurrences |
| **Memory Pressure** | Available memory < threshold (low memory state) | `ActivityManager.MemoryInfo.lowMemory` | boolean | Monitor trend |
| **Thermal Throttling** | Device thermal state >= THERMAL_STATUS_MODERATE | `PowerManager.getCurrentThermalStatus()` API 29+ | enum | Monitor occurrences |

#### Collection Architecture

```kotlin
class VitalsCollector(
    private val config: VitalsConfig,
    private val meter: Meter,
    private val logger: Logger
) {
    private val frameMetrics = FrameMetricsAggregator()
    private val choreographer = Choreographer.getInstance()
    private val mainThreadWatchdog = MainThreadWatchdog()

    // Rollup dimensions
    private val screenName: String
    private val deviceModel: String = Build.MODEL
    private val appVersion: String

    fun startCollecting() {
        // Cold/warm start timing
        lifecycleObserver.onActivityResumed { activity ->
            recordStartupMetric(activity)
        }

        // Frame metrics per screen
        lifecycleObserver.onActivityStarted { activity ->
            frameMetrics.add(activity)
            startJankMonitoring(activity)
        }

        // Input latency
        inputEventMonitor.onTouch { event ->
            measureInputLatency(event)
        }

        // Main thread watchdog
        mainThreadWatchdog.start()

        // Memory/thermal polling (low frequency)
        schedulePeriodicCheck(intervalMs = 60_000)
    }

    // Export as OTEL metrics
    fun exportMetrics() {
        meter.gaugeBuilder("mobile.vitals.cold_start_ms").buildWithCallback { result ->
            result.record(coldStartTimeMs,
                Attributes.of("screen.name", screenName))
        }

        meter.gaugeBuilder("mobile.vitals.jank_p95").buildWithCallback { result ->
            result.record(calculateJankP95(),
                Attributes.of("screen.name", screenName, "device.model", deviceModel))
        }

        // ... other metrics
    }

    // Trigger hooks for policy integration
    fun onJankSpike(p95: Double) {
        if (p95 > config.jankThreshold) {
            logger.logEvent("mobile.vitals.jank_spike", mapOf(
                "jank_p95" to p95,
                "screen.name" to screenName,
                "trigger" to "threshold_exceeded"
            ))
            // This event can match policy → flush last 2 minutes
        }
    }
}
```

#### Trigger Hooks

1. **On Crash**: Export final vitals snapshot with last 30s aggregates
2. **On UI Freeze**: Export vitals for current screen + last 2 screens
3. **On Network Error Cascade**: Export network-related vitals (connectivity state)
4. **On Predictive Risk Spike**: Export full vitals snapshot + historical trend (last 5 min)

#### Export Policy Integration

```yaml
# Example workflow: Flush on jank spike
policies:
  - id: jank-spike-detector
    match:
      attributes:
        event.name: {equals: "mobile.vitals.jank_spike"}
        jank_p95: {gt: 50}  # >50ms p95 frame time
    actions:
      - type: flush_window
        parameters: {window_minutes: 2}
      - type: capture_device_metrics
        parameters: {reason: "jank_spike"}
```

#### Rollups

- **Per Screen**: `screen.name` attribute (Activity/Fragment/Compose route)
- **Per Session**: Aggregated over `session.id`
- **Per Device Model**: `device.model` attribute
- **Per App Version**: `service.version` attribute

#### Configuration

```kotlin
data class VitalsConfig(
    val enabled: Boolean = true,
    val collectColdStart: Boolean = true,
    val collectWarmStart: Boolean = true,
    val collectJank: Boolean = true,
    val collectInputLatency: Boolean = true,
    val collectMainThreadTasks: Boolean = true,
    val collectAnrRisk: Boolean = true,
    val collectMemoryPressure: Boolean = true,
    val collectThermalState: Boolean = true,

    // Thresholds for alerts/policies
    val jankThreshold: Double = 50.0,  // ms
    val inputLatencyThreshold: Double = 100.0,  // ms
    val mainThreadTaskThreshold: Long = 50,  // ms

    // Sampling
    val sampleRate: Double = 1.0,  // 100% by default
    val reportingInterval: Long = 60_000,  // 1 minute
)
```

---

### B) `mobile-navigation` Module

**Package**: `io.opentelemetry.android.mobile.navigation`

#### Instrumentation Points

1. **Activity Lifecycle**: `registerActivityLifecycleCallbacks()`
2. **Fragment Lifecycle**: `FragmentManager.registerFragmentLifecycleCallbacks()`
3. **Compose Navigation**: Intercept `NavHostController` navigation events
4. **Deep Links**: Intercept Intent data in `Activity.onCreate()`

#### Breadcrumb Data Model

```kotlin
data class NavigationBreadcrumb(
    val timestamp: Long,
    val screenName: String,  // "MainActivity", "ProductDetailScreen"
    val screenClass: String,  // FQCN
    val navigationType: NavigationType,  // PUSH, POP, REPLACE
    val route: String?,  // Compose route (scrubbed)
    val deepLink: String?,  // Deep link URI (query params scrubbed)
    val arguments: Map<String, String>  // Safe args only (no PII)
)

enum class NavigationType {
    PUSH,     // New screen pushed
    POP,      // Back navigation
    REPLACE,  // Replace current screen
    DEEP_LINK // Deep link navigation
}
```

#### Privacy-Safe Scrubbing

```kotlin
class NavigationScrubber(private val config: NavigationConfig) {
    fun scrubRoute(route: String): String {
        // Remove dynamic path params: /user/{id}/profile → /user/{id}/profile
        return route.replace(Regex("/[0-9a-f-]{36}"), "/{uuid}")
                   .replace(Regex("/\\d+"), "/{id}")
    }

    fun scrubDeepLink(uri: Uri): String {
        // Remove query params by default
        return if (config.allowDeepLinkQueryParams) {
            uri.toString()
        } else {
            uri.buildUpon().clearQuery().build().toString()
        }
    }

    fun scrubArguments(args: Bundle): Map<String, String> {
        // Allowlist approach: only include safe keys
        return args.keySet()
            .filter { it in config.safeArgumentKeys }
            .associate { it to args.getString(it, "").scrubbed() }
    }
}
```

#### Event Names (Standardized)

```kotlin
// Log events for breadcrumbs
"mobile.navigation.screen_enter"  // Screen became visible
"mobile.navigation.screen_exit"   // Screen no longer visible
"mobile.navigation.deep_link"     // Deep link opened
"mobile.navigation.back_pressed"  // Back button pressed
```

#### Span Creation (Optional)

```kotlin
// Create span for screen session
fun onScreenEnter(screen: String) {
    val span = tracer.spanBuilder("screen.session")
        .setAttribute("screen.name", screen)
        .setAttribute("screen.class", screen.javaClass.name)
        .startSpan()

    // Store span in scope
    screenSpans[screen] = span
}

fun onScreenExit(screen: String) {
    screenSpans[screen]?.end()
    screenSpans.remove(screen)
}
```

#### Configuration

```kotlin
data class NavigationConfig(
    val enabled: Boolean = true,
    val captureActivities: Boolean = true,
    val captureFragments: Boolean = true,
    val captureCompose: Boolean = true,
    val captureDeepLinks: Boolean = true,

    // Privacy
    val scrubRoutes: Boolean = true,
    val allowDeepLinkQueryParams: Boolean = false,
    val safeArgumentKeys: Set<String> = setOf("screen_id", "category", "source"),

    // Breadcrumb buffer
    val breadcrumbBufferSize: Int = 50,

    // Span creation
    val createSpans: Boolean = true,
)
```

---

### C) `mobile-network` Module

**Package**: `io.opentelemetry.android.mobile.network`

#### OkHttp Interceptor Architecture

```kotlin
class OtelNetworkInterceptor(
    private val config: NetworkConfig,
    private val tracer: Tracer,
    private val propagator: TextMapPropagator
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        // Check if propagation allowed for this host
        val shouldPropagate = config.shouldPropagateTrace(url)

        // Create span
        val span = tracer.spanBuilder("http.request")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("http.method", request.method)
            .setAttribute("http.url", config.scrubUrl(url))
            .setAttribute("http.request.body.size_bucket", getSizeBucket(request.body?.contentLength()))
            .setAttribute("network.type", getNetworkType())
            .startSpan()

        // Inject trace context if allowed
        val requestBuilder = request.newBuilder()
        if (shouldPropagate) {
            propagator.inject(Context.current().with(span), requestBuilder) { carrier, key, value ->
                carrier.header(key, value)
            }
        }

        // Capture allowed headers
        config.allowedRequestHeaders.forEach { header ->
            request.header(header)?.let { value ->
                span.setAttribute("http.request.header.$header", value)
            }
        }

        // Execute request with timing
        val startTime = System.nanoTime()
        return try {
            val response = chain.proceed(requestBuilder.build())
            val duration = (System.nanoTime() - startTime) / 1_000_000  // ms

            // Record timing
            span.setAttribute("http.status_code", response.code)
            span.setAttribute("http.response.body.size_bucket", getSizeBucket(response.body?.contentLength()))
            span.setAttribute("http.timing.total_ms", duration)
            span.setAttribute("http.timing.dns_ms", /* extract from OkHttp metrics */)
            span.setAttribute("http.timing.connect_ms", /* ... */)
            span.setAttribute("http.timing.tls_ms", /* ... */)
            span.setAttribute("http.timing.ttfb_ms", /* ... */)

            // Capture allowed response headers
            config.allowedResponseHeaders.forEach { header ->
                response.header(header)?.let { value ->
                    span.setAttribute("http.response.header.$header", value)
                }
            }

            span.setStatus(if (response.isSuccessful) StatusCode.OK else StatusCode.ERROR)
            span.end()

            response
        } catch (e: Exception) {
            span.recordException(e)
            span.setStatus(StatusCode.ERROR, e.message ?: "Network error")
            span.end()
            throw e
        }
    }

    private fun getNetworkType(): String {
        // Use ConnectivityManager
        return when (connectivityManager.activeNetworkInfo?.type) {
            ConnectivityManager.TYPE_WIFI -> "wifi"
            ConnectivityManager.TYPE_MOBILE -> "cellular"
            else -> "unknown"
        }
    }

    private fun getSizeBucket(size: Long?): String {
        return when {
            size == null -> "unknown"
            size < 1024 -> "<1KB"
            size < 10240 -> "1KB-10KB"
            size < 102400 -> "10KB-100KB"
            size < 1048576 -> "100KB-1MB"
            else -> ">1MB"
        }
    }
}
```

#### Propagation Configuration

```kotlin
data class PropagationConfig(
    val enabled: Boolean = true,
    val allowedHosts: Set<String> = emptySet(),  // Exact match: "api.example.com"
    val allowedHostPatterns: Set<Regex> = emptySet(),  // Regex: ".*\\.example\\.com"
    val deniedHosts: Set<String> = emptySet(),  // Blocklist
) {
    fun shouldPropagateTrace(url: String): Boolean {
        val host = Uri.parse(url).host ?: return false

        // Check blocklist first
        if (host in deniedHosts) return false

        // Check allowlist
        return host in allowedHosts || allowedHostPatterns.any { it.matches(host) }
    }
}
```

#### URL Scrubbing

```kotlin
class UrlScrubber(private val config: NetworkConfig) {
    fun scrubUrl(url: String): String {
        val uri = Uri.parse(url)

        return uri.buildUpon().apply {
            // Remove query params by default
            if (!config.allowQueryParams) {
                clearQuery()
            } else {
                // Redact sensitive params
                config.redactQueryParams.forEach { param ->
                    query(param)?.let { query(param, "[REDACTED]") }
                }
            }

            // Scrub path segments (e.g., UUIDs, IDs)
            if (config.scrubPathSegments) {
                val scrubbedPath = uri.path
                    ?.replace(Regex("/[0-9a-f-]{36}"), "/{uuid}")
                    ?.replace(Regex("/\\d+"), "/{id}")
                path(scrubbedPath)
            }
        }.build().toString()
    }
}
```

#### Configuration

```kotlin
data class NetworkConfig(
    val enabled: Boolean = true,
    val propagation: PropagationConfig = PropagationConfig(),

    // Header capture (allowlist approach)
    val allowedRequestHeaders: Set<String> = setOf("user-agent", "content-type"),
    val allowedResponseHeaders: Set<String> = setOf("content-type", "cache-control"),

    // URL scrubbing
    val scrubPathSegments: Boolean = true,
    val allowQueryParams: Boolean = false,
    val redactQueryParams: Set<String> = setOf("token", "api_key", "session_id"),

    // Timing collection
    val captureDetailedTiming: Boolean = true,
)
```

---

### D) `mobile-errors` Module

**Package**: `io.opentelemetry.android.mobile.errors`

#### Auto-Capture Hooks

```kotlin
class ErrorInstrumentation(
    private val config: ErrorConfig,
    private val logger: Logger,
    private val tracer: Tracer
) {

    fun install() {
        // 1. Uncaught exception handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            captureException(throwable, "uncaught_exception", thread = thread)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // 2. Coroutine exception handler
        val coroutineHandler = CoroutineExceptionHandler { context, throwable ->
            captureException(throwable, "coroutine_exception", context = context)
        }
        // Install as global handler (depends on app setup)

        // 3. RxJava hooks (if RxJava detected)
        if (hasRxJava()) {
            RxJavaPlugins.setErrorHandler { throwable ->
                captureException(throwable, "rxjava_exception")
            }
        }
    }

    private fun captureException(
        throwable: Throwable,
        source: String,
        thread: Thread? = null,
        context: CoroutineContext? = null
    ) {
        // Filter
        if (shouldIgnore(throwable)) return

        // Dedupe
        val fingerprint = generateFingerprint(throwable)
        if (isDuplicate(fingerprint)) return

        // Rate limit
        if (isRateLimited(fingerprint)) return

        // Create error event
        val span = tracer.spanBuilder("exception")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("exception.type", throwable.javaClass.name)
            .setAttribute("exception.message", config.scrubMessage(throwable.message))
            .setAttribute("exception.stacktrace", formatStackTrace(throwable))
            .setAttribute("exception.source", source)
            .setAttribute("error.fingerprint", fingerprint)
            .apply {
                thread?.let { setAttribute("thread.name", it.name) }
                context?.let { setAttribute("coroutine.context", it.toString()) }
            }
            .startSpan()

        span.recordException(throwable)
        span.end()

        // Log error event
        logger.logEvent("mobile.error.exception", mapOf(
            "exception.type" to throwable.javaClass.name,
            "exception.message" to config.scrubMessage(throwable.message),
            "error.fingerprint" to fingerprint,
            "exception.source" to source
        ))

        // Trigger immediate flush if critical
        if (isCritical(throwable)) {
            MobileOtel.forceFlush()
        }
    }

    // Deduplication: 5-minute window
    private val seenFingerprints = mutableMapOf<String, Long>()
    private fun isDuplicate(fingerprint: String): Boolean {
        val now = System.currentTimeMillis()
        val lastSeen = seenFingerprints[fingerprint]

        return if (lastSeen != null && now - lastSeen < 300_000) {
            true
        } else {
            seenFingerprints[fingerprint] = now
            false
        }
    }

    // Rate limiting: max 10 errors/min
    private val rateLimitCounter = mutableMapOf<String, Int>()
    private var lastResetTime = System.currentTimeMillis()

    private fun isRateLimited(fingerprint: String): Boolean {
        val now = System.currentTimeMillis()

        // Reset every minute
        if (now - lastResetTime > 60_000) {
            rateLimitCounter.clear()
            lastResetTime = now
        }

        val count = rateLimitCounter.getOrDefault(fingerprint, 0)
        return if (count >= config.maxErrorsPerMinute) {
            true
        } else {
            rateLimitCounter[fingerprint] = count + 1
            false
        }
    }

    // Generate fingerprint for dedupe
    private fun generateFingerprint(throwable: Throwable): String {
        val key = "${throwable.javaClass.name}:${throwable.message}:${throwable.stackTrace.take(3).joinToString()}"
        return key.hashCode().toString()
    }
}
```

#### Manual Error Reporting

```kotlin
// Public API
fun MobileOtel.reportError(
    throwable: Throwable,
    context: Map<String, Any> = emptyMap(),
    severity: ErrorSeverity = ErrorSeverity.ERROR
) {
    errorInstrumentation.captureException(throwable, "manual", customContext = context)
}

enum class ErrorSeverity {
    DEBUG,
    INFO,
    WARNING,
    ERROR,
    FATAL
}
```

#### ProGuard/R8 Mapping

```kotlin
// Symbolication strategy (future enhancement)
class SymbolicationHandler(private val mappingFile: File?) {
    fun deobfuscateStackTrace(stackTrace: Array<StackTraceElement>): Array<StackTraceElement> {
        // Use ProGuard mapping file to reverse obfuscation
        // Similar to web sourcemaps, but Android-specific
        // Implementation: parse mapping.txt, match obfuscated names
        return stackTrace  // TODO: Implement ProGuard deobfuscation
    }
}
```

#### Configuration

```kotlin
data class ErrorConfig(
    val enabled: Boolean = true,
    val captureUncaught: Boolean = true,
    val captureCoroutine: Boolean = true,
    val captureRxJava: Boolean = true,

    // Filtering
    val ignoreExceptions: Set<String> = emptySet(),  // Class names to ignore
    val ignoreMessages: Set<Regex> = emptySet(),  // Message patterns to ignore

    // Deduplication & rate limiting
    val dedupeWindowMs: Long = 300_000,  // 5 minutes
    val maxErrorsPerMinute: Int = 10,

    // Privacy
    val scrubMessages: Boolean = true,
    val scrubStackTraces: Boolean = true,
    val maxStackTraceDepth: Int = 50,

    // Symbolication
    val proguardMappingFile: File? = null,
)
```

---

### E) `mobile-events` Module

**Package**: `io.opentelemetry.android.mobile.events`

#### Public API

```kotlin
object MobileEvents {

    fun sendEvent(name: String, options: EventOptions = EventOptions()) {
        // Validate event name
        require(isValidEventName(name)) {
            "Invalid event name: $name. Must not start with 'mobile.' (reserved namespace)"
        }

        // Create log event
        val attributes = mutableMapOf<String, Any>()
        attributes["event.name"] = name
        attributes["event.severity"] = options.severity.name

        // Add custom attributes (validated)
        options.attributes.forEach { (key, value) ->
            require(isValidAttributeKey(key)) {
                "Invalid attribute key: $key"
            }
            attributes[key] = value
        }

        // Add timestamp if provided
        options.timestamp?.let {
            attributes["event.timestamp"] = it
        }

        // Emit log event
        logger.logEvent(name, attributes)
    }

    private fun isValidEventName(name: String): Boolean {
        // Reserved namespace: mobile.*
        if (name.startsWith("mobile.")) {
            return false  // Internal use only
        }

        // Must be alphanumeric + underscores/dots
        return name.matches(Regex("^[a-z][a-z0-9._]*$"))
    }

    private fun isValidAttributeKey(key: String): Boolean {
        // Must be alphanumeric + underscores/dots
        return key.matches(Regex("^[a-z][a-z0-9._]*$"))
    }
}

data class EventOptions(
    val severity: EventSeverity = EventSeverity.INFO,
    val timestamp: Long? = null,  // Optional custom timestamp
    val attributes: Map<String, Any> = emptyMap()
)

enum class EventSeverity {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL
}
```

#### Reserved Namespace Protection

```kotlin
// Internal events use "mobile.*" namespace
// User events CANNOT use this namespace (enforced at API level)

// Examples of reserved internal events:
"mobile.vitals.jank_spike"
"mobile.vitals.anr_risk"
"mobile.navigation.screen_enter"
"mobile.network.request_failed"
"mobile.error.exception"
```

#### Example Usage

```kotlin
// User code
MobileEvents.sendEvent("user.checkout.completed", EventOptions(
    severity = EventSeverity.INFO,
    attributes = mapOf(
        "order_id" to "12345",
        "total_amount" to 99.99,
        "payment_method" to "credit_card"
    )
))

// This would throw an exception (reserved namespace):
MobileEvents.sendEvent("mobile.custom.event")  // ❌ Not allowed
```

---

## 4. Public API Proposal (Kotlin)

### Core Facade: `MobileOtel`

```kotlin
object MobileOtel {

    // ─────────────────────────────────────────────────────────────
    // Initialization (existing, enhanced)
    // ─────────────────────────────────────────────────────────────

    fun initialize(context: Context, config: MobileConfig): MobileLoggerProvider {
        // Initialize SessionManager FIRST (early init strategy)
        SessionManager.initialize(context, config.sessionConfig)

        // Initialize instrumentation modules
        config.vitalsConfig?.let { VitalsCollector.initialize(context, it) }
        config.navigationConfig?.let { NavigationInstrumentation.initialize(context, it) }
        config.networkConfig?.let { NetworkInstrumentation.initialize(context, it) }
        config.errorConfig?.let { ErrorInstrumentation.initialize(context, it) }

        // Initialize core MobileLoggerProvider (existing)
        return MobileLoggerProvider.initialize(context, config)
    }

    // ─────────────────────────────────────────────────────────────
    // Session Management
    // ─────────────────────────────────────────────────────────────

    /**
     * Identify the current user. User ID is attached to all telemetry.
     * @param user User identity (ID + optional metadata)
     */
    fun identify(user: UserIdentity) {
        SessionManager.identify(user)
    }

    /**
     * Clear user identity. Future telemetry will be anonymous.
     */
    fun clearIdentity() {
        SessionManager.clearIdentity()
    }

    /**
     * Terminate the current session. A new session will start on next app use.
     * @param reason Reason for termination (e.g., "logout", "account_switch")
     */
    fun terminateSession(reason: String = "manual") {
        SessionManager.terminateSession(reason)
    }

    /**
     * Enable/disable session tracking.
     */
    fun setSessionEnabled(enabled: Boolean) {
        SessionManager.setEnabled(enabled)
    }

    // ─────────────────────────────────────────────────────────────
    // Global Attributes
    // ─────────────────────────────────────────────────────────────

    /**
     * Add a global attribute that will be attached to all telemetry.
     * @param key Attribute key (must be alphanumeric + underscores/dots)
     * @param value Attribute value (String, Number, Boolean)
     */
    fun addGlobalAttribute(key: String, value: Any) {
        SessionManager.addGlobalAttribute(key, value)
    }

    /**
     * Remove a global attribute.
     */
    fun removeGlobalAttribute(key: String) {
        SessionManager.removeGlobalAttribute(key)
    }

    /**
     * Clear all global attributes.
     */
    fun clearGlobalAttributes() {
        SessionManager.clearGlobalAttributes()
    }

    // ─────────────────────────────────────────────────────────────
    // Custom Events
    // ─────────────────────────────────────────────────────────────

    /**
     * Send a custom event with structured data.
     * @param name Event name (must not start with "mobile." - reserved namespace)
     * @param options Event options (severity, attributes, timestamp)
     */
    fun sendEvent(name: String, options: EventOptions = EventOptions()) {
        MobileEvents.sendEvent(name, options)
    }

    // ─────────────────────────────────────────────────────────────
    // Error Reporting
    // ─────────────────────────────────────────────────────────────

    /**
     * Manually report an error/exception.
     * @param throwable Exception to report
     * @param context Additional context (key-value pairs)
     * @param severity Error severity
     */
    fun reportError(
        throwable: Throwable,
        context: Map<String, Any> = emptyMap(),
        severity: ErrorSeverity = ErrorSeverity.ERROR
    ) {
        ErrorInstrumentation.captureException(throwable, "manual", customContext = context)
    }

    // ─────────────────────────────────────────────────────────────
    // Module Control
    // ─────────────────────────────────────────────────────────────

    /**
     * Enable/disable instrumentation module at runtime.
     * @param module Module to control
     * @param enabled Enable or disable
     */
    fun setModuleEnabled(module: InstrumentationModule, enabled: Boolean) {
        when (module) {
            InstrumentationModule.VITALS -> VitalsCollector.setEnabled(enabled)
            InstrumentationModule.NAVIGATION -> NavigationInstrumentation.setEnabled(enabled)
            InstrumentationModule.NETWORK -> NetworkInstrumentation.setEnabled(enabled)
            InstrumentationModule.ERRORS -> ErrorInstrumentation.setEnabled(enabled)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Flush Control (existing, enhanced)
    // ─────────────────────────────────────────────────────────────

    /**
     * Force flush all buffered telemetry to collector.
     * @param windowMinutes Optional: flush only last N minutes (null = flush all)
     * @param timeoutSeconds Timeout for flush operation
     */
    fun forceFlush(windowMinutes: Int? = null, timeoutSeconds: Long = 30): CompletableResultCode {
        return MobileLoggerProvider.getInstance().forceFlush(windowMinutes, timeoutSeconds)
    }
}

// ─────────────────────────────────────────────────────────────
// Data Classes
// ─────────────────────────────────────────────────────────────

data class UserIdentity(
    val userId: String,
    val email: String? = null,  // Hashed by default
    val name: String? = null,   // Opt-in only
    val customAttributes: Map<String, Any> = emptyMap(),
    val hashEmail: Boolean = true  // Hash by default for privacy
)

enum class InstrumentationModule {
    VITALS,
    NAVIGATION,
    NETWORK,
    ERRORS
}
```

---

## 5. Session + Identity Model

### Session Lifecycle

```kotlin
class SessionManager private constructor(
    private val context: Context,
    private val config: SessionConfig
) {
    private val prefs = context.getSharedPreferences("otel_session", Context.MODE_PRIVATE)
    private val encryptedPrefs = EncryptedSharedPreferences.create(/* ... */)

    private var currentSessionId: String = loadOrCreateSessionId()
    private var currentUserId: String? = loadUserId()
    private var sessionStartTime: Long = System.currentTimeMillis()
    private var lastActivityTime: Long = sessionStartTime
    private val globalAttributes = mutableMapOf<String, Any>()

    private var isInForeground = false
    private val inactivityTimer = Timer()

    // ─────────────────────────────────────────────────────────────
    // Lifecycle Management
    // ─────────────────────────────────────────────────────────────

    fun initialize(context: Context, config: SessionConfig) {
        // Register lifecycle callbacks
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    onForeground()
                }

                override fun onActivityPaused(activity: Activity) {
                    onBackground()
                }

                // ... other callbacks
            }
        )

        // Load persisted global attributes
        loadGlobalAttributes()
    }

    private fun onForeground() {
        isInForeground = true
        lastActivityTime = System.currentTimeMillis()

        // Check if session expired during background
        val backgroundDuration = System.currentTimeMillis() - lastActivityTime
        if (backgroundDuration > config.inactivityTimeoutMs) {
            // Session expired - start new session
            terminateSession("inactivity_timeout")
            currentSessionId = generateSessionId()
            sessionStartTime = System.currentTimeMillis()
            saveSessionId()
        }

        // Cancel inactivity timer
        inactivityTimer.cancel()
    }

    private fun onBackground() {
        isInForeground = false

        // Start inactivity timer
        inactivityTimer.schedule(config.inactivityTimeoutMs) {
            terminateSession("inactivity_timeout")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Session Control
    // ─────────────────────────────────────────────────────────────

    fun terminateSession(reason: String) {
        // Log session end event
        logger.logEvent("mobile.session.terminated", mapOf(
            "session.id" to currentSessionId,
            "session.duration_ms" to (System.currentTimeMillis() - sessionStartTime),
            "session.termination_reason" to reason
        ))

        // Trigger flush (HYBRID mode)
        if (config.flushOnTermination) {
            MobileOtel.forceFlush()
        }

        // Clear session state
        currentSessionId = generateSessionId()
        sessionStartTime = System.currentTimeMillis()
        saveSessionId()
    }

    // ─────────────────────────────────────────────────────────────
    // Identity Management
    // ─────────────────────────────────────────────────────────────

    fun identify(user: UserIdentity) {
        currentUserId = user.userId

        // Store user attributes (encrypted if sensitive)
        encryptedPrefs.edit().apply {
            putString("user.id", user.userId)

            if (user.email != null) {
                val email = if (user.hashEmail) {
                    hashEmail(user.email)
                } else {
                    user.email
                }
                putString("user.email", email)
            }

            user.name?.let { putString("user.name", it) }

            apply()
        }

        // Log identify event
        logger.logEvent("mobile.user.identified", mapOf(
            "user.id" to user.userId
        ))
    }

    fun clearIdentity() {
        currentUserId = null
        encryptedPrefs.edit().clear().apply()

        logger.logEvent("mobile.user.cleared", emptyMap())
    }

    // ─────────────────────────────────────────────────────────────
    // Global Attributes
    // ─────────────────────────────────────────────────────────────

    fun addGlobalAttribute(key: String, value: Any) {
        globalAttributes[key] = value
        saveGlobalAttributes()
    }

    fun removeGlobalAttribute(key: String) {
        globalAttributes.remove(key)
        saveGlobalAttributes()
    }

    // ─────────────────────────────────────────────────────────────
    // Enrichment (called for every event)
    // ─────────────────────────────────────────────────────────────

    fun enrichEvent(attributes: MutableMap<String, Any>) {
        // Session attributes
        attributes["session.id"] = currentSessionId
        attributes["session.start_time"] = sessionStartTime
        attributes["session.duration_ms"] = System.currentTimeMillis() - sessionStartTime
        attributes["session.state"] = if (isInForeground) "active" else "background"

        // User attributes (if identified)
        currentUserId?.let { attributes["user.id"] = it }

        // Global attributes
        attributes.putAll(globalAttributes)
    }

    // ─────────────────────────────────────────────────────────────
    // Persistence
    // ─────────────────────────────────────────────────────────────

    private fun loadOrCreateSessionId(): String {
        return prefs.getString("session.id", null) ?: generateSessionId().also { saveSessionId() }
    }

    private fun saveSessionId() {
        prefs.edit().putString("session.id", currentSessionId).apply()
    }

    private fun loadGlobalAttributes() {
        val json = prefs.getString("global_attributes", "{}")
        globalAttributes.putAll(Json.decodeFromString(json!!))
    }

    private fun saveGlobalAttributes() {
        val json = Json.encodeToString(globalAttributes)
        prefs.edit().putString("global_attributes", json).apply()
    }

    private fun generateSessionId(): String = UUID.randomUUID().toString()

    private fun hashEmail(email: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(email.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

// ─────────────────────────────────────────────────────────────
// Configuration
// ─────────────────────────────────────────────────────────────

data class SessionConfig(
    val enabled: Boolean = true,
    val inactivityTimeoutMs: Long = 15 * 60 * 1000,  // 15 minutes
    val flushOnTermination: Boolean = true,  // Flush on logout/termination (HYBRID mode)
    val persistSession: Boolean = true,  // Persist session across app restarts
)
```

### Session Boundaries & Policy Integration

**Flush Triggers on Session Events**:

1. **Session Termination (logout/account switch)**: Flush all buffered telemetry for current session
2. **Inactivity Timeout**: In HYBRID mode, flush when session expires
3. **Foreground → Background**: Optionally flush (configurable, default: no)

**Buffer Partitioning**: Future enhancement could partition disk buffer by session ID for selective export.

**Policy Matching**: Policies can match on `session.id` or `session.state` attributes.

---

## 6. Breadcrumb Specification

### Data Model

```kotlin
data class JourneyBreadcrumb(
    val timestamp: Long,                    // Event timestamp
    val type: BreadcrumbType,               // Type of breadcrumb
    val screen: String,                     // Screen name (Activity/Fragment/Compose route)
    val action: String,                     // Action description (click, swipe, navigate, etc.)
    val elementId: String?,                 // UI element ID (privacy-safe)
    val attributes: Map<String, Any>        // Additional context (scrubbed)
)

enum class BreadcrumbType {
    NAVIGATION,    // Screen transition
    USER_INPUT,    // Button click, text input, gesture
    NETWORK,       // HTTP request
    ERROR,         // Error occurred
    LIFECYCLE,     // App foreground/background
    CUSTOM         // User-defined
}
```

### Circular Buffer Implementation

```kotlin
class JourneyBreadcrumbBuffer(
    private val maxSize: Int = 50
) {
    private val buffer = ArrayDeque<JourneyBreadcrumb>(maxSize)
    private val lock = ReentrantReadWriteLock()

    fun add(breadcrumb: JourneyBreadcrumb) {
        lock.write {
            if (buffer.size >= maxSize) {
                buffer.removeFirst()  // FIFO eviction
            }
            buffer.addLast(breadcrumb)
        }
    }

    fun toList(): List<JourneyBreadcrumb> {
        return lock.read {
            buffer.toList()
        }
    }

    fun toJson(): String {
        return lock.read {
            Json.encodeToString(buffer.toList())
        }
    }

    fun clear() {
        lock.write {
            buffer.clear()
        }
    }
}
```

### Capture Points (Auto-Instrumentation)

#### 1. Navigation Breadcrumbs

```kotlin
// Activity lifecycle
lifecycleCallbacks.onActivityResumed { activity ->
    breadcrumbBuffer.add(JourneyBreadcrumb(
        timestamp = System.currentTimeMillis(),
        type = BreadcrumbType.NAVIGATION,
        screen = activity.javaClass.simpleName,
        action = "screen_enter",
        elementId = null,
        attributes = mapOf(
            "screen.class" to activity.javaClass.name
        )
    ))
}

// Compose navigation
navController.addOnDestinationChangedListener { _, destination, args ->
    breadcrumbBuffer.add(JourneyBreadcrumb(
        timestamp = System.currentTimeMillis(),
        type = BreadcrumbType.NAVIGATION,
        screen = destination.route ?: "unknown",
        action = "navigate",
        elementId = null,
        attributes = scrubArguments(args)
    ))
}
```

#### 2. User Input Breadcrumbs

```kotlin
// View clicks (Activity/Fragment)
view.setOnClickListener {
    breadcrumbBuffer.add(JourneyBreadcrumb(
        timestamp = System.currentTimeMillis(),
        type = BreadcrumbType.USER_INPUT,
        screen = currentScreen,
        action = "click",
        elementId = view.resourceName(),  // e.g., "button_checkout"
        attributes = emptyMap()
    ))
}

// Compose clicks
@Composable
fun TrackableButton(text: String, onClick: () -> Unit) {
    Button(onClick = {
        breadcrumbBuffer.add(JourneyBreadcrumb(
            timestamp = System.currentTimeMillis(),
            type = BreadcrumbType.USER_INPUT,
            screen = currentScreen,
            action = "click",
            elementId = text,  // Button text as ID
            attributes = emptyMap()
        ))
        onClick()
    }) {
        Text(text)
    }
}
```

#### 3. Network Breadcrumbs

```kotlin
// In OkHttp interceptor
override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()

    breadcrumbBuffer.add(JourneyBreadcrumb(
        timestamp = System.currentTimeMillis(),
        type = BreadcrumbType.NETWORK,
        screen = currentScreen,
        action = "http_request",
        elementId = null,
        attributes = mapOf(
            "http.method" to request.method,
            "http.url" to scrubUrl(request.url.toString())
        )
    ))

    return chain.proceed(request)
}
```

#### 4. Error Breadcrumbs

```kotlin
// In error capture
private fun captureException(throwable: Throwable) {
    breadcrumbBuffer.add(JourneyBreadcrumb(
        timestamp = System.currentTimeMillis(),
        type = BreadcrumbType.ERROR,
        screen = currentScreen,
        action = "exception",
        elementId = null,
        attributes = mapOf(
            "exception.type" to throwable.javaClass.name,
            "exception.message" to scrubMessage(throwable.message)
        )
    ))
}
```

### Attach to Critical Events

```kotlin
// Example: Attach breadcrumbs to crash recovery event
fun onCrashRecovery() {
    val breadcrumbs = breadcrumbBuffer.toList()

    logger.logEvent("app.crash_recovery", mapOf(
        "recovery_type" to "crash",
        "user.journey" to breadcrumbs.toJson(),
        "user.journey.length" to breadcrumbs.size,
        "user.journey.duration_sec" to (breadcrumbs.last().timestamp - breadcrumbs.first().timestamp) / 1000
    ))

    // Attach as span events
    val span = tracer.spanBuilder("crash_recovery").startSpan()
    breadcrumbs.forEach { breadcrumb ->
        span.addEvent("journey_step", Attributes.of(
            "timestamp", breadcrumb.timestamp,
            "screen", breadcrumb.screen,
            "action", breadcrumb.action
        ))
    }
    span.end()
}
```

### Privacy Controls

```kotlin
data class BreadcrumbConfig(
    val enabled: Boolean = true,
    val maxSize: Int = 50,

    // Capture filters
    val captureNavigation: Boolean = true,
    val captureUserInput: Boolean = true,
    val captureNetwork: Boolean = true,
    val captureErrors: Boolean = true,

    // Privacy
    val scrubElementIds: Boolean = true,  // Remove IDs with PII patterns
    val scrubNetworkUrls: Boolean = true,
    val allowedScreens: Set<String> = emptySet(),  // If set, only capture these screens
)
```

---

## 7. Implementation Plan (PR-Sized Increments)

### Phase 1: Core Infrastructure (3-4 days)

#### PR #1: SessionManager + Global Attributes
- [ ] Create `core/SessionManager.kt`
- [ ] Implement session lifecycle (foreground/background, inactivity)
- [ ] Implement `identify()`, `clearIdentity()`, `terminateSession()`
- [ ] Implement global attributes (add/remove/persist)
- [ ] Add encryption for sensitive data (EncryptedSharedPreferences)
- [ ] Unit tests: `SessionManagerTest.kt` (20 tests)
  - Session creation/termination
  - Identity management
  - Global attributes persistence
  - Inactivity timeout
- [ ] Update `MobileConfig.kt` with `SessionConfig`
- [ ] Update `assets/otel-config.json` with session config

#### PR #2: MobileOtel Facade + Early Init
- [ ] Create `MobileOtel.kt` facade object
- [ ] Implement public API methods (identify, addGlobalAttribute, sendEvent, reportError, etc.)
- [ ] Create `EarlyEventQueue.kt` for pre-init buffering
- [ ] Update `MobileLoggerProvider.initialize()` to integrate SessionManager
- [ ] Add enrichment hook in `MobileLogRecordProcessor` to attach session/global attrs
- [ ] Unit tests: `MobileOtelTest.kt` (15 tests)
- [ ] Update demo app to use `MobileOtel` facade

#### PR #3: PII Scrubber + Privacy Controls
- [ ] Create `core/PiiScrubber.kt`
- [ ] Implement URL scrubbing (query params, path segments)
- [ ] Implement deep link scrubbing
- [ ] Implement message/stacktrace scrubbing
- [ ] Unit tests: `PiiScrubberTest.kt` (25 tests - various PII patterns)
- [ ] Update `MobileConfig.kt` with scrubbing rules

---

### Phase 2: Journey Breadcrumbs (2-3 days)

#### PR #4: Breadcrumb Buffer + Data Model
- [ ] Create `core/JourneyBreadcrumbBuffer.kt`
- [ ] Implement circular buffer (ArrayDeque + ReentrantReadWriteLock)
- [ ] Create `JourneyBreadcrumb` data class
- [ ] Implement `toJson()` serialization
- [ ] Unit tests: `JourneyBreadcrumbBufferTest.kt` (15 tests)
  - Buffer overflow (FIFO eviction)
  - Thread safety
  - JSON serialization
- [ ] Update `MobileConfig.kt` with `BreadcrumbConfig`

#### PR #5: Breadcrumb Instrumentation (Navigation)
- [ ] Create `mobile-navigation/` package
- [ ] Implement `NavigationInstrumentation.kt`
- [ ] Activity lifecycle callbacks → breadcrumbs
- [ ] Fragment lifecycle callbacks → breadcrumbs
- [ ] Compose NavController listener → breadcrumbs
- [ ] Deep link capture → breadcrumbs
- [ ] Unit tests: `NavigationInstrumentationTest.kt` (20 tests)
- [ ] Update demo app with navigation breadcrumbs

#### PR #6: Attach Breadcrumbs to Critical Events
- [ ] Update crash recovery to attach breadcrumbs
- [ ] Update error capture to attach breadcrumbs
- [ ] Update UI freeze event to attach breadcrumbs
- [ ] Update predictive risk spike to attach breadcrumbs
- [ ] Demo app: Show breadcrumbs in crash recovery scenario

---

### Phase 3: Mobile Vitals (4-5 days)

#### PR #7: Vitals Data Collection
- [ ] Create `mobile-vitals/` package
- [ ] Implement `VitalsCollector.kt`
- [ ] Cold/Warm start timing (Application.onCreate → Activity.onResume)
- [ ] TTID measurement (`reportFullyDrawn()` or custom marker)
- [ ] Jank detection (FrameMetricsAggregator API 24+)
- [ ] Input latency tracking (Choreographer)
- [ ] Main thread watchdog (Looper.setMessageLogging or custom)
- [ ] Memory pressure monitoring (ActivityManager.MemoryInfo)
- [ ] Thermal state monitoring (PowerManager API 29+)
- [ ] Unit tests: `VitalsCollectorTest.kt` (30 tests)
- [ ] Update `MobileConfig.kt` with `VitalsConfig`

#### PR #8: Vitals Metrics Export
- [ ] Implement OTEL metrics export for vitals
- [ ] Gauge builders for each metric
- [ ] Attributes: screen.name, device.model, service.version
- [ ] Trigger hooks for policy integration (jank spike, ANR risk)
- [ ] Unit tests: Verify metrics format and attributes
- [ ] Update demo app with vitals scenario (trigger jank spike)

#### PR #9: Vitals Policy Integration
- [ ] Add example workflows to `assets/otel-config.json`:
  - Jank spike detector (flush last 2 min on jank_p95 > 50ms)
  - ANR risk detector (immediate flush on ANR risk)
  - Memory pressure detector (flush on low memory)
- [ ] Update `PolicyEvaluator.kt` to match vitals events
- [ ] Integration test: Trigger vitals event → policy match → flush
- [ ] Update demo app to demonstrate policy-triggered flush

---

### Phase 4: Network Instrumentation (3-4 days)

#### PR #10: OkHttp Interceptor
- [ ] Create `mobile-network/` package
- [ ] Implement `OtelNetworkInterceptor.kt`
- [ ] HTTP span creation with timing attributes
- [ ] Trace context propagation (allowlist/regex)
- [ ] Header capture (allowlist)
- [ ] URL scrubbing (query params, path segments)
- [ ] Network type detection (wifi/cell)
- [ ] Size bucket attributes
- [ ] Unit tests: `OtelNetworkInterceptorTest.kt` (25 tests)
- [ ] Update `MobileConfig.kt` with `NetworkConfig`

#### PR #11: Network Breadcrumbs + Policy Integration
- [ ] Add network breadcrumbs (http_request, http_response)
- [ ] Update demo app to show network instrumentation (Scenario C)
- [ ] Add example workflow: HTTP error cascade detector
- [ ] Integration test: 5xx cascade → policy match → flush
- [ ] Documentation: Network instrumentation guide

---

### Phase 5: Error Instrumentation (3-4 days)

#### PR #12: Auto-Capture Hooks
- [ ] Create `mobile-errors/` package
- [ ] Implement `ErrorInstrumentation.kt`
- [ ] Uncaught exception handler
- [ ] Coroutine exception handler
- [ ] RxJava error hook (optional, detect RxJava at runtime)
- [ ] Deduplication logic (5-minute window)
- [ ] Rate limiting (10 errors/minute)
- [ ] Unit tests: `ErrorInstrumentationTest.kt` (25 tests)
- [ ] Update `MobileConfig.kt` with `ErrorConfig`

#### PR #13: Manual Error Reporting + ProGuard Mapping
- [ ] Implement `MobileOtel.reportError()` API
- [ ] Add `SymbolicationHandler.kt` (ProGuard mapping support)
- [ ] Error fingerprinting for dedupe
- [ ] Filter by exception class/message patterns
- [ ] Unit tests: Error reporting, filtering, dedupe
- [ ] Update demo app with manual error reporting

#### PR #14: Error Breadcrumbs + Policy Integration
- [ ] Add error breadcrumbs
- [ ] Attach breadcrumbs to exception spans
- [ ] Add example workflow: Critical error detector (immediate flush)
- [ ] Integration test: Exception → policy match → flush with breadcrumbs
- [ ] Documentation: Error handling guide

---

### Phase 6: Events Module (1-2 days)

#### PR #15: Custom Events API
- [ ] Create `mobile-events/` package
- [ ] Implement `MobileEvents.kt` with `sendEvent()` API
- [ ] Reserved namespace validation ("mobile.*" protected)
- [ ] Event name validation (alphanumeric + underscores/dots)
- [ ] Attribute validation
- [ ] Unit tests: `MobileEventsTest.kt` (15 tests)
  - Event name validation
  - Namespace protection
  - Attribute validation
- [ ] Update demo app with custom event examples

---

### Phase 7: Demo App Enhancements (2-3 days)

#### PR #16: Demo App Scenarios
- [ ] **Scenario E: Vitals Spike**
  - Trigger jank spike (intentional frame drops)
  - Show vitals metrics in logs
  - Demonstrate policy-triggered flush
- [ ] **Scenario F: Journey Reconstruction**
  - Multi-screen navigation flow (3 screens)
  - User interaction breadcrumbs
  - Trigger error at end → show full journey in crash recovery
- [ ] Update MainActivity UI (add scenarios E & F)
- [ ] Add settings for vitals/navigation/network/errors modules
- [ ] Documentation: Demo app guide with new scenarios

#### PR #17: Configuration Examples
- [ ] Update `assets/otel-config.json` with all new modules
- [ ] Add example workflows for each module:
  - Vitals: jank spike, ANR risk
  - Navigation: screen error context
  - Network: HTTP cascade detector
  - Errors: critical exception handler
- [ ] Add environment-specific configs (dev, staging, prod)
- [ ] Documentation: Configuration guide with examples

---

### Phase 8: Testing & Documentation (3-4 days)

#### PR #18: Integration Tests
- [ ] Create `integration-tests/` module
- [ ] Test session lifecycle + global attributes
- [ ] Test breadcrumbs attached to crash recovery
- [ ] Test vitals policy integration (jank spike → flush)
- [ ] Test network instrumentation + propagation
- [ ] Test error capture + dedupe/rate-limit
- [ ] Test export modes (CONDITIONAL/CONTINUOUS/HYBRID) with new modules
- [ ] (Estimated: 30 integration tests)

#### PR #19: Documentation
- [ ] Update `docs/architecture/` with new modules
- [ ] Create `docs/MOBILE_VITALS.md` (vitals guide)
- [ ] Create `docs/SESSION_MANAGEMENT.md` (session + identity guide)
- [ ] Create `docs/JOURNEY_BREADCRUMBS.md` (breadcrumb guide)
- [ ] Update `README.md` with new features
- [ ] Update `.claude/ai_notes.md` with implementation details

#### PR #20: Performance Benchmarks
- [ ] Benchmark vitals collection overhead (<5ms per screen)
- [ ] Benchmark breadcrumb buffer memory (<100KB for 50 breadcrumbs)
- [ ] Benchmark network interceptor overhead (<1ms per request)
- [ ] Benchmark error capture overhead (<10ms per exception)
- [ ] Document performance characteristics
- [ ] Add to `docs/PERFORMANCE.md`

---

### Estimated Timeline

| Phase | Duration | PRs |
|-------|----------|-----|
| **Phase 1: Core Infrastructure** | 3-4 days | PR #1-3 |
| **Phase 2: Journey Breadcrumbs** | 2-3 days | PR #4-6 |
| **Phase 3: Mobile Vitals** | 4-5 days | PR #7-9 |
| **Phase 4: Network Instrumentation** | 3-4 days | PR #10-11 |
| **Phase 5: Error Instrumentation** | 3-4 days | PR #12-14 |
| **Phase 6: Events Module** | 1-2 days | PR #15 |
| **Phase 7: Demo App Enhancements** | 2-3 days | PR #16-17 |
| **Phase 8: Testing & Documentation** | 3-4 days | PR #18-20 |
| **Total** | **21-29 days** | **20 PRs** |

---

## 8. Guardrails & Constraints

### OTEL-Native Approach (Hard Constraint)
- ✅ Use official OTEL SDK interfaces (`LoggerProvider`, `TracerProvider`, `MeterProvider`)
- ✅ Export via OTLP/gRPC (no custom protocols)
- ✅ Follow OTEL semantic conventions for attributes
- ✅ Propose OTEPs for mobile-specific patterns (upstream contribution)
- ❌ No forking of OTEL SDK
- ❌ No proprietary backends (Dash0/Jaeger/Tempo-agnostic)

### Performance & Battery (Hard Constraint)
- **Cold Start Overhead**: <50ms added to app startup
- **Vitals Collection**: <5ms per screen transition
- **Breadcrumb Buffer**: <100KB memory (50 breadcrumbs)
- **Network Interceptor**: <1ms per request
- **Error Capture**: <10ms per exception
- **Battery Impact**: <1% additional drain (CONDITIONAL mode)

**Sampling Controls**:
- Vitals: Sample on screen transitions (rate-limit 1/min per metric)
- Network: 100% by default (user can configure sampling)
- Errors: Dedupe + rate-limit (10/min)
- Breadcrumbs: Always-on (low cost)

### Privacy & PII (Hard Constraint)
- **No PII by Default**: Email hashed, no GPS, no device ID
- **Scrubbing**: URLs, deep links, exception messages, stack traces
- **Allowlist Approach**: Only capture explicitly allowed headers/query params
- **User Consent**: Respect opt-out preferences
- **Encryption**: Sensitive data in SharedPreferences encrypted

**PII Risks & Mitigation**:
| Risk | Mitigation |
|------|------------|
| User email in identity | Hash by default (SHA-256) |
| Deep link URLs with tokens | Scrub query params by default |
| Exception messages with PII | Regex-based scrubbing (emails, phone numbers, credit cards) |
| Stack traces with file paths | Scrub user-specific paths (e.g., `/data/user/0/`) |
| Custom event attributes | User responsibility + validation helpers |

### Android API Level Constraints
| Feature | Min API Level | Fallback |
|---------|---------------|----------|
| FrameMetricsAggregator (jank) | API 24 | Use Choreographer polling |
| PowerManager.getCurrentThermalStatus() | API 29 | Not available (optional metric) |
| EncryptedSharedPreferences | API 23 | Use plain SharedPreferences (warn user) |
| Room Database | API 16 | Already supported |

---

## Bonus: Where Our Project Already Beats Dash0 Web

### Strengths We Already Have

1. **Two-Tier Buffering (RAM → Disk)**
   - Dash0 Web: In-memory queue with IndexedDB fallback (limited by browser storage quotas)
   - **Our Mobile**: Room database with 50 MB default capacity, survives app kills/crashes
   - **Advantage**: We can buffer 24+ hours of telemetry offline, Dash0 Web limited to ~50 MB by browser

2. **Export Modes (CONDITIONAL/CONTINUOUS/HYBRID)**
   - Dash0 Web: Always-on export (no conditional triggers)
   - **Our Mobile**: CONDITIONAL mode only exports on triggers (errors, low memory, policy match) - battery-friendly
   - **Advantage**: Production apps can run <1% battery overhead, Dash0 Web has ~3-5% overhead

3. **Predictive Telemetry Module**
   - Dash0 Web: Reactive only (capture after problem occurs)
   - **Our Mobile**: Predictive telemetry can pre-emptively flush before crashes/network loss
   - **Advantage**: Capture telemetry BEFORE the device dies or loses connectivity (unique to mobile)

4. **Policy Engine (Device-Side + Collector-Side)**
   - Dash0 Web: No policy engine (all export decisions client-side)
   - **Our Mobile**: PolicyEvaluator on device + mobilepolicyprocessor on collector
   - **Advantage**: Centralized policy management, workflow-based triggers, fleet-wide updates

5. **Control Plane UI with Workflow Builder**
   - Dash0 Web: Configuration via code only
   - **Our Mobile**: Visual workflow builder (25 node types) + fleet management UI
   - **Advantage**: Non-technical users can create workflows, A/B test policies, target device cohorts

6. **Bundled Configuration System**
   - Dash0 Web: Requires backend for config (doesn't work offline)
   - **Our Mobile**: Ships with `assets/otel-config.json`, works offline from day 1
   - **Advantage**: Apps work in airplane mode, no backend dependency for basic setup

7. **Retry Logic with Exponential Backoff**
   - Dash0 Web: Limited browser retry (depends on fetch API)
   - **Our Mobile**: Custom `RetryableExporter` with 3 attempts, exponential backoff, keeps buffered on failure
   - **Advantage**: Better resilience to network issues (tunnels, poor connectivity)

8. **Crash Recovery with Historical Context**
   - Dash0 Web: Page reload clears in-memory state
   - **Our Mobile**: Disk buffer survives crashes, auto-detects crash on restart, flushes last 5 minutes
   - **Advantage**: Never lose telemetry context around crashes

### How New Modules Leverage These Strengths

1. **Vitals + Predictive Telemetry**
   - Predictive module can trigger vitals snapshot BEFORE crash (based on memory pressure, thermal throttling)
   - Example: Device overheating → predictive risk spike → capture full vitals → flush pre-emptively
   - **Result**: Capture performance degradation timeline leading to crash

2. **Breadcrumbs + Policy Engine**
   - Policies can trigger selective flush with breadcrumbs (e.g., "flush last 2 minutes on UI freeze")
   - Example: UI freeze detected → flush window (logs + breadcrumbs) → minimal bandwidth
   - **Result**: Contextual debugging without full session export

3. **Network Instrumentation + Export Modes**
   - CONDITIONAL mode: Only export network traces on 5xx cascades
   - Example: 3 consecutive 500 errors → policy match → flush network traces + device metrics
   - **Result**: Zero bandwidth cost for successful requests, full context on failures

4. **Error Capture + Disk Buffer**
   - Errors queued to disk buffer if collector unavailable
   - Example: App crashes in subway tunnel → errors buffered → uploaded when back online
   - **Result**: Never lose error telemetry, even offline

5. **Session Management + Global Attributes + Bundled Config**
   - Apps ship with pre-configured session settings (timeout, flush rules)
   - Example: Logout triggers session termination → flush all telemetry → clear identity
   - **Result**: Works offline, no backend dependency for session lifecycle

### Summary

**Our mobile project is superior in:**
- Offline resilience (disk buffer, retry logic)
- Battery efficiency (export modes, predictive telemetry)
- Centralized management (policy engine, workflow builder)
- Crash recovery (historical context preservation)

**Dash0 Web SDK teaches us:**
- Modular instrumentation design (opt-in modules)
- Standardized "vitals" metrics (CLS/INP/LCP → mobile equivalents)
- Clean API surface (sendEvent, identify, global attributes)
- Privacy-first defaults (scrubbing, filtering)

**Combining both**: We get a mobile observability system that's **more powerful than Dash0 Web** (offline + battery-efficient + predictive) while adopting their **API ergonomics** (modular + privacy-safe + easy to use).

---

## Configuration Schema Patch

### Updated `MobileConfig.kt`

```kotlin
data class MobileConfig(
    // ─── Existing fields ───
    val serviceName: String,
    val serviceVersion: String,
    val collectorEndpoint: String,
    val ramBufferSize: Int = 5000,
    val diskBufferMb: Int = 50,
    val diskBufferTtlHours: Int = 24,
    val traceExportIntervalSeconds: Long = 30,
    val metricExportIntervalSeconds: Long = 60,
    val exportTimeoutSeconds: Long = 30,
    val maxExportRetries: Int = 3,
    val exportMode: ExportMode = ExportMode.CONDITIONAL,

    // ─── NEW: Session & Identity ───
    val sessionConfig: SessionConfig = SessionConfig(),

    // ─── NEW: Instrumentation Modules ───
    val vitalsConfig: VitalsConfig? = VitalsConfig(),
    val navigationConfig: NavigationConfig? = NavigationConfig(),
    val networkConfig: NetworkConfig? = NetworkConfig(),
    val errorConfig: ErrorConfig? = ErrorConfig(),
    val breadcrumbConfig: BreadcrumbConfig? = BreadcrumbConfig(),

    // ─── Existing: Device Metrics, Workflows, etc. ───
    val deviceMetricsConfig: DeviceMetricsConfig = DeviceMetricsConfig(),
    val workflows: List<Workflow> = emptyList(),
    val samplingConfig: SamplingConfig = SamplingConfig(),
)
```

### Updated `assets/otel-config.json`

```json
{
  "serviceName": "otel-mobile-demo",
  "serviceVersion": "1.0.0",
  "collectorEndpoint": "http://10.0.2.2:4317",
  "exportMode": "CONDITIONAL",

  "sessionConfig": {
    "enabled": true,
    "inactivityTimeoutMs": 900000,
    "flushOnTermination": true,
    "persistSession": true
  },

  "vitalsConfig": {
    "enabled": true,
    "collectColdStart": true,
    "collectWarmStart": true,
    "collectJank": true,
    "collectInputLatency": true,
    "collectMainThreadTasks": true,
    "collectAnrRisk": true,
    "collectMemoryPressure": true,
    "collectThermalState": true,
    "jankThreshold": 50.0,
    "inputLatencyThreshold": 100.0,
    "mainThreadTaskThreshold": 50,
    "sampleRate": 1.0,
    "reportingInterval": 60000
  },

  "navigationConfig": {
    "enabled": true,
    "captureActivities": true,
    "captureFragments": true,
    "captureCompose": true,
    "captureDeepLinks": true,
    "scrubRoutes": true,
    "allowDeepLinkQueryParams": false,
    "safeArgumentKeys": ["screen_id", "category", "source"],
    "breadcrumbBufferSize": 50,
    "createSpans": true
  },

  "networkConfig": {
    "enabled": true,
    "propagation": {
      "enabled": true,
      "allowedHosts": ["api.example.com"],
      "allowedHostPatterns": [".*\\.example\\.com"],
      "deniedHosts": []
    },
    "allowedRequestHeaders": ["user-agent", "content-type"],
    "allowedResponseHeaders": ["content-type", "cache-control"],
    "scrubPathSegments": true,
    "allowQueryParams": false,
    "redactQueryParams": ["token", "api_key", "session_id"],
    "captureDetailedTiming": true
  },

  "errorConfig": {
    "enabled": true,
    "captureUncaught": true,
    "captureCoroutine": true,
    "captureRxJava": true,
    "ignoreExceptions": [],
    "ignoreMessages": [],
    "dedupeWindowMs": 300000,
    "maxErrorsPerMinute": 10,
    "scrubMessages": true,
    "scrubStackTraces": true,
    "maxStackTraceDepth": 50,
    "proguardMappingFile": null
  },

  "breadcrumbConfig": {
    "enabled": true,
    "maxSize": 50,
    "captureNavigation": true,
    "captureUserInput": true,
    "captureNetwork": true,
    "captureErrors": true,
    "scrubElementIds": true,
    "scrubNetworkUrls": true,
    "allowedScreens": []
  },

  "workflows": [
    {
      "id": "jank-spike-detector",
      "name": "Jank Spike Detection",
      "enabled": true,
      "trigger": {
        "all": [
          {
            "event": "mobile.vitals.jank_spike",
            "where": [
              {"attr": "jank_p95", "op": ">", "value": 50}
            ]
          }
        ]
      },
      "actions": [
        {"type": "flush_window", "minutes": 2, "scope": "session"},
        {"type": "capture_device_metrics", "reason": "jank_spike"}
      ]
    },
    {
      "id": "http-error-cascade",
      "name": "HTTP Error Cascade Detector",
      "enabled": true,
      "trigger": {
        "all": [
          {
            "event": "http.error",
            "where": [
              {"attr": "http.status_code", "op": ">=", "value": 500}
            ],
            "count": 3,
            "within_minutes": 1
          }
        ]
      },
      "actions": [
        {"type": "flush_window", "minutes": 5, "scope": "session"},
        {"type": "capture_device_metrics", "reason": "http_cascade"}
      ]
    },
    {
      "id": "critical-exception-handler",
      "name": "Critical Exception Handler",
      "enabled": true,
      "trigger": {
        "any": [
          {
            "event": "mobile.error.exception",
            "where": [
              {"attr": "exception.type", "op": "equals", "value": "OutOfMemoryError"}
            ]
          },
          {
            "event": "mobile.error.exception",
            "where": [
              {"attr": "exception.source", "op": "equals", "value": "uncaught_exception"}
            ]
          }
        ]
      },
      "actions": [
        {"type": "flush_all"},
        {"type": "capture_device_metrics", "reason": "critical_error"}
      ]
    }
  ]
}
```

---

**End of Design Document**

This design provides a complete, actionable plan to port Dash0 Web SDK's best features into our OTEL-native mobile observability project, while leveraging our existing strengths (buffering, export modes, predictive telemetry, policy engine). The 20-PR implementation plan is sized for incremental delivery with full test coverage and demo app integration.
