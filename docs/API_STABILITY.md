# API Stability Audit — the 1.0 freeze list

**Status:** Reviewed 2026-06-12 (VERSIONING.md gate 2). This document is the
contract: every public symbol is assigned a tier, deliberately. A symbol not
listed here that turns out to be public is a bug — file it.

Tiers (from [VERSIONING.md](VERSIONING.md)):
- **STABLE-AT-1.0** — signature frozen now; covered by SemVer from 1.0.
  Changing one of these between now and 1.0 requires updating this document
  in the same PR with the rationale.
- **INCUBATING** — shipping and supported, may change in a MINOR. Marked
  `@Incubating` (Kotlin) / "Experimental" doc note (Swift) / `@experimental`
  (TS). Stays incubating past 1.0 unless explicitly promoted here.
- **INTERNAL-LEAK** — implementation detail that is public today by accident.
  Scheduled to become `internal` in **0.4.0** (pre-1.0 minors may break).
  Until then it carries `@Incubating` as a warning shot.

---

## Android (`io.opentelemetry.android.mobile`)

### STABLE-AT-1.0

| Symbol | Notes |
|---|---|
| `OTelMobile.start(Application, MobileConfig)` / `stop(timeoutSeconds)` | the drop-in entry point |
| `OTelMobile.getLogger/getTracer/getMeter(scope)` | manual telemetry access |
| `OTelMobile.screenView(name)` | screen tracking |
| `MobileOtel.initialize(Context, MobileOtelDsl.() -> Unit)` | the DSL entry point |
| `MobileOtel.identify/clearIdentity/terminateSession` | user + session control |
| `MobileOtel.addGlobalAttribute/removeGlobalAttribute/clearGlobalAttributes` | enrichment |
| `MobileOtel.sendEvent(name, attributes, severity)` | custom events |
| `MobileOtel.reportError(throwable, context)` | manual error reporting |
| `MobileOtel.forceFlush(windowMinutes, timeoutSeconds)` / `shutdown()` | flush contract (post-#38 semantics: result completes after export AND cleanup) |
| `OpenTelemetryMobile` (`sessionId`, `getTracer/getLogger/getMeter`, `forceFlush`, `flushWindow`, `shutdown`) | DSL return type |
| `MobileConfig` core fields | `serviceName`, `serviceVersion`, `collectorEndpoint`, `protocol`, `exportMode`, `samplingConfig`, `headers`, `exportTimeoutSeconds`, `maxExportRetries`, buffer sizing (`ramBufferSize`, `ramBufferMaxTotalBytes`, `ramBufferMaxEventBytes`, `diskBufferMb`, `diskBufferTtlHours`), `extraResourceAttributes`, `sessionConfig`, `networkConfig`, `errorConfig`, `vitalsConfig`, `breadcrumbConfig`, `deviceMetricsConfig` |
| `MobileConfig.Builder` (setters for the stable fields) + `builder()` | Java-friendly construction |
| `OtlpProtocol`, `ExportMode` | core enums |
| `SamplingConfig` (+ `static`/`dynamic` factories), `SessionConfig`, `BreadcrumbConfig`, `DeviceMetricsConfig`, `VitalsConfig`, `ErrorConfig`, `NetworkConfig` | config value types |
| `UserIdentity` | identity value type |
| `MobileOtelDsl` + sub-DSLs (`service`, `export`, `buffering`, `session`, `exportCustomizers`, `instrumentations`) | DSL shape |
| `ExporterCustomizers` (+ `Builder`) | exporter wrapping |

### INCUBATING (stays past 1.0)

| Symbol | Why it stays incubating |
|---|---|
| `OTelMobile.startJourney/endJourney/captureScreenshot/captureWireframe` | capture modules still maturing (already member-annotated) |
| `OTelMobile.markCrashForNextStart/markLowMemoryForNextStart/markAnrForNextStart/getLastRecoveryType` | recovery-marker surface may be folded into config |
| `MobileOtel.getCurrentPrediction()` | predictive export is evolving |
| `MobileOtel.getErrorStatistics(): ErrorStatistics?` / `getBufferStats(): BufferStats?` | typed since 0.4.0; promotion candidate once the shapes soak |
| `MobileConfig` incubating fields | `uiTelemetryMode`, `textInputConfig`, `encryptDiskBufferAtRest`, `remoteConfigEnabled`, `configPollIntervalSeconds`, `predictionIntervalSeconds`, `screenshotConfig`, `wireframeConfig`, `offlineBudgetConfig`, `offlinePolicy`, `appManagedScreens`, `allowInsecureTransport`, `pinningConfig`, `configSigningKey`, `attachContextAttributes`, `buildChannel`, `userContextPrefsName`, `traceExportIntervalSeconds`, `metricExportIntervalSeconds` |
| `UiTelemetryMode` | tied to incubating field |
| `OfflinePolicy`, `OfflineBudgetConfig` | offline budget semantics still settling |
| `OTelMobileBuilder` / `OTelMobileHandle` / `MobileInstrumentation` / `InstrumentationContext` | the bring-your-own-OTel path; signature churn likely as upstream `AndroidInstrumentation` evolves |
| `ExportStatus` / `ExportStatusListener` / `ExportStatusManager` | debug surface |
| `MobileLoggerProvider` (incl. `setSamplingRate`/`resetSamplingToBaseline`, and since 0.4.0 `flushWindow(minutes)` + `getBufferStats()`) | direct provider access is advanced; remote-sampling API may move to the gate |
| `RemoteGate` | kill-switch wire schema is explicitly incubating |
| `SessionManager` direct access | consumers should go through `MobileOtel`; direct singleton may narrow |

### INTERNAL-LEAK — ✅ sealed (`internal`) in 0.4.0

These were implementation details with no supported consumer use:

- `MobileLogRecordProcessor` (+ `Builder`, hooks `heartbeatLogger`,
  `predictionCycleHook`, `policyMatchHook`, `spanFlushHook`)
- `RetryableExporter` — consumers who want retry composition use
  `ExporterCustomizers`; the class itself is not API
- `DiskLogBuffer` + `LogRecordEntity` + `LogDao`
- `LoggingHttpExporter`, `EnrichingLogRecordExporter`, `ErrorCoalescer`,
  `Lazy{Log,Span,Metric}Exporter` (already `internal` ✓)
- `SessionManager.getEnrichmentAttributes()` — enrichment is the SDK's job
- `MobileOtel.openTelemetryMobile` setter (public `var` — must become
  read-only or internal)

---

## iOS (`OTelMobileSDK` / `OTelMobileCore`)

### STABLE-AT-1.0

- `OTelMobile.start(config:diskBuffer:spanDiskBuffer:)`
- `OTelMobile.emit(body:severity:)` / `emit(body:severity:attributes:)`
- `OTelMobile.forceFlush()` / `flushWindow(minutes:)`
- `OTelMobile.logger/tracer/meter/resource/config/sessionProvider`
- `MobileConfig` core: `serviceName`, `serviceVersion`, `endpoint`,
  `authToken`, `exportMode`, `bufferConfig`, `samplingConfig`,
  `extraHeaders`, `extraResourceAttributes`, `logExportIntervalSeconds`
- `ExportMode`, `BufferConfig`, `SamplingConfig` (+ factories,
  `SamplingStrategy`), `PrivacyConfig` (+ presets)
- `AutoCaptureOptions` (OptionSet shape + `default`/`all`/`none`)
- `DiskLogBuffer` actor public surface (consumers construct it for `start`)

### INCUBATING (Experimental doc note)

- Journey + capture: `startJourney/endJourney/captureScreenshot/captureWireframe`
- `ScreenshotConfig` / `WireframeConfig` + `CaptureConsentGate` /
  `CaptureContext` / `CaptureTrigger` / `CaptureKind` (consent surface may
  be refined by feedback)
- `ScreenshotInstrumentation` / `WireframeInstrumentation` direct use
- `policyEvaluator`, `fleetAlertHandler`, `remoteGate`,
  `predictiveExportPolicy`, `sdkStateGauges`, `deviceStats`,
  `contextSnapshotProvider` public properties — exposed live internals; may
  narrow. (`enablePolicyPolling`, `enablePredictiveExport`,
  `predictiveExportIntervalSeconds`, `deviceStatsIntervalSeconds`,
  `pollingIntervalSeconds`, `offlineBudgetConfig`, `offlinePolicy`,
  `allowInsecureTransport`, `pinning`, `configSigningKey` config fields ride
  with their features.)
- `buildReplayHeaders` / `recoverSpanRequests` statics (ops/recovery tooling)

---

## React Native (`@barrysolomon/mobile-react-native`)

### STABLE-AT-1.0

- `Dash0Mobile.start(config)` / `log` / `startSpan` / `span` /
  `recordMetric` / `flushWindow` / `shutdown`
- `SpanHandle`, `StartConfig` core (`serviceName`, `serviceVersion`,
  `endpoint`, `authToken`, `dataset`, `bufferConfig`, `sampling`,
  `autoCapture` boolean fields, `extraResourceAttributes`)
- `Attributes`, `SeverityNumber`, `SpanKind`, `SpanStatus`, `SamplingConfig`
- `installReactNavigationInstrumentation`, `withTapTelemetry`,
  `installFetchInstrumentation` (+ `FetchInstrumentationConfig`),
  `installErrorInstrumentation`

### INCUBATING (`@experimental` JSDoc)

- `Dash0Mobile.startJourney/endJourney/captureScreenshot/captureWireframe`
- `ScreenshotAutoCapture` / `WireframeAutoCapture` per-trigger objects
  (documented as not yet fully wired through the bridge)
- `otel` compat shim (+ `Compat*` types) — a deliberate subset; shape follows
  what third-party libraries demand
- Bridge payload types (`LogPayload`, `SpanStartPayload`, `SpanEndPayload`,
  `MetricPayload`, `NativeDash0MobileModule`) — the JS↔native wire contract,
  versioned with the bridge, not with consumers

---

## Scheduled breaking changes for 0.4.0 — EXECUTED in 0.4.1-alpha

1. ✅ INTERNAL-LEAK list sealed (`internal`); `MobileLoggerProvider` gained
   first-class `flushWindow(minutes)` + `getBufferStats()`; `BufferStats`
   promoted to a top-level public type; consumers (RN bridge, demo)
   migrated.
2. ✅ `getErrorStatistics(): ErrorStatistics?` / `getBufferStats():
   BufferStats?` — typed.
3. ✅ `setSessionEnabled` REMOVED (was an unimplemented no-op); use
   `SessionConfig.enabled` at start.
4. ✅ `session.id` dual-emitted beside `mobile.session.id` on Android;
   alias drops at 1.0.
5. ⏸ iOS live-internals narrowing — deferred until external feedback exists
   (no integrator has reported needing or tripping on these).

## Review discipline

- A PR that changes a STABLE-AT-1.0 signature must edit this file in the
  same PR and say why.
- A PR that adds a new public symbol must add it to one of the tiers here.
- At 1.0: STABLE-AT-1.0 entries drop `@Incubating` everywhere it remains,
  CHANGELOG gets an "API frozen" section, and this document becomes the
  SemVer reference surface.
