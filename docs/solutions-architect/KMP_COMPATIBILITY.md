# Dash0 Mobile Observability — Kotlin Multiplatform (KMP) Compatibility

## TL;DR for Sales

Dash0 Mobile Observability is fully compatible with KMP apps. Our SDKs instrument at the native platform layer, which sits below KMP's shared code. From our perspective, a KMP app is a normal native app.

**One qualifying question to ask the customer:**
> What HTTP client and error handling strategy does your KMP shared layer use?

The answer determines drop-in vs. minor configuration (see Edge Cases below).

---

## Why KMP Doesn't Change Anything

KMP is a code-sharing strategy for app developers. It lets them write business logic once in Kotlin and compile it to both Android (JVM) and iOS (Kotlin/Native). But the resulting app is still:

- A standard Android app on Android (APK, Activity lifecycle, OkHttp, etc.)
- A standard iOS app on iOS (.app bundle, UIScene lifecycle, URLSession, etc.)

Our SDKs hook into the **native platform layer** — lifecycle observers, crash handlers, network interceptors — which sits below the KMP shared module. The shared Kotlin code runs on top of the native platform, and our instrumentation runs beneath it.

```
┌─────────────────────────────────┐
│  KMP Shared Code (commonMain)   │  ← Customer's business logic
├─────────────────────────────────┤
│  Platform Layer (Android / iOS) │  ← Dash0 SDK instruments here
├─────────────────────────────────┤
│  OS / Runtime                   │
└─────────────────────────────────┘
```

## Collector & Policy Engine

The Dash0 collector processes standard OpenTelemetry Protocol (OTLP) data. It operates at the wire protocol level — it does not know or care what language, framework, or build tool produced the telemetry. KMP apps emit the same OTLP spans, logs, and metrics as any other app.

All mobile-specific features work unchanged:
- Export policy evaluation (conditional, hybrid, always-on)
- Offline buffering and selective flush
- Crash recovery and replay
- Device-level policy targeting

## Integration Path

Drop in the native SDKs as you would for any app:

| Target | SDK | Integration |
|--------|-----|-------------|
| Android | `dash0-android-mobile` | Gradle dependency, initialize in `Application.onCreate()` |
| iOS | `Dash0MobileOTel` | SwiftPM/CocoaPods, initialize in `AppDelegate` or `@main` |

The KMP shared module requires no changes. Telemetry from shared code is captured through the native platform layer automatically.

## Edge Cases to Qualify

### 1. KMP Networking Libraries (Ktor, etc.)

**Risk:** If the customer's KMP shared layer uses a networking library that bypasses the platform's default HTTP engine, our automatic network interceptors may not capture those requests.

| KMP HTTP Client | Android Engine | iOS Engine | Auto-Instrumented? |
|----------------|---------------|------------|-------------------|
| Ktor + OkHttp engine | OkHttp | — | ✅ Yes |
| Ktor + Darwin engine | — | URLSession | ✅ Yes |
| Ktor + CIO engine | Custom (Coroutines I/O) | Custom | ⚠️ No — bypasses native stack |
| Custom KMP HTTP | Depends on impl | Depends on impl | ⚠️ Verify |

**Resolution:** If they use a non-native engine, they can either:
- Switch to the platform-native Ktor engine (recommended, zero perf difference)
- Manually add OTel HTTP instrumentation to their KMP networking layer

**Question to ask:** *"Does your Ktor (or HTTP) configuration use the OkHttp engine on Android and the Darwin engine on iOS, or a custom/CIO engine?"*

### 2. KMP-Level Exception Handling

**Risk:** If the customer installs a KMP-level exception handler (e.g., a `commonMain` try/catch or a coroutine exception handler) that catches and swallows Kotlin exceptions before they propagate to the native crash handler, our crash instrumentation may not see them.

**Resolution:** This is standard practice for any error-handling middleware — not unique to KMP. If they swallow exceptions at any layer, they should:
- Re-throw after logging, or
- Manually emit an error span/log via the OTel API

**Question to ask:** *"Does your shared module have a global exception handler or coroutine supervisor that catches exceptions centrally?"*

### 3. KMP Coroutine Context

**Minor:** Kotlin coroutines in KMP shared code execute on dispatchers that may not carry OTel context automatically. If the customer needs distributed tracing through their KMP coroutine chains, they may need `opentelemetry-extension-kotlin` to propagate span context across coroutine boundaries. This is a standard OTel concern, not Dash0-specific.

## Competitive Note

Some mobile observability vendors require a specific SDK per framework (separate Flutter SDK, separate KMP SDK, etc.). Dash0's architecture is **framework-agnostic by design** — we instrument at the platform layer and ingest standard OTLP. This means:

- No waiting for "KMP SDK support"
- No framework-specific limitations
- Full feature parity regardless of how the app is built

## Summary

| Aspect | Compatible? | Notes |
|--------|-------------|-------|
| Collector / policy engine | ✅ 100% | Protocol-level, SDK-agnostic |
| Lifecycle instrumentation | ✅ Yes | Hooks native layer below KMP |
| Crash reporting | ✅ Yes | Native signal/exception handlers |
| Network auto-instrumentation | ✅ Usually | Verify HTTP engine choice |
| Coroutine context propagation | ⚠️ Verify | Standard OTel concern, one-line fix |
| KMP-swallowed exceptions | ⚠️ Verify | Same as any error middleware |
