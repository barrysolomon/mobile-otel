---
title: OTLP exporter failures are invisible to SpanExporter decorators (iOS)
date: 2026-04-23
type: bug-pattern
platforms: [ios]
keywords: [otlp, opentelemetry-swift, span-exporter, http-client, persist-on-failure, gate-4]
related-code:
  - otel-ios-mobile/Sources/OTelMobileSDK/Export/PersistingTraceHTTPClient.swift
  - otel-ios-mobile/Sources/OTelMobileSDK/Export/OTLPExporterFactory.swift
upstream-bug: opentelemetry-swift OtlpHttpTraceExporter returns .success synchronously
---

# OTLP exporter failures are invisible to SpanExporter decorators (iOS)

## TL;DR

If you want to detect or react to OTLP/HTTP trace export failures on iOS,
**intercept at the `HTTPClient` layer, not via a `SpanExporter` decorator.**
A decorator that watches for `.failure` from `OtlpHttpTraceExporter` will
never trigger — upstream returns `.success` before the HTTP call completes.

## The trap

The obvious design for a "persist-on-failure" feature is:

```swift
// ❌ Does not work
class PersistingSpanExporter: SpanExporter {
    let upstream: OtlpHttpTraceExporter
    func export(spans: [SpanData]) -> SpanExporterResultCode {
        let result = upstream.export(spans: spans)
        if result == .failure { persistToDisk(spans) }   // ← dead code
        return result
    }
}
```

This compiles, looks reasonable, passes unit tests with a mocked exporter,
and is **completely silent in production**: the persist-on-failure branch
is never taken even when every HTTP POST is failing.

## Root cause

Upstream `OtlpHttpTraceExporter.export()` in `opentelemetry-swift` returns
`.success` synchronously, *before the HTTP request completes*. The actual
HTTP result lives inside an un-awaited `httpClient.send(request:) { result in ... }`
closure that's invisible above the exporter.

Any caller above the exporter — including a `SpanExporter` decorator —
sees only `.success`, regardless of whether the collector accepted the
batch, returned 5xx, or was unreachable.

This is an **upstream design**, not a bug we can fix in our SDK. Our
options are: (a) work around it by intercepting one layer below, or (b)
reimplement the OTLP exporter from scratch. We chose (a).

## The fix

Inject a custom `HTTPClient` via `OtlpHttpTraceExporter`'s `httpClient:`
init parameter. This is the first place failures are actually observable.

See [`PersistingTraceHTTPClient.swift`](../../otel-ios-mobile/Sources/OTelMobileSDK/Export/PersistingTraceHTTPClient.swift)
for the implementation, and [`OTLPExporterFactory.swift`](../../otel-ios-mobile/Sources/OTelMobileSDK/Export/OTLPExporterFactory.swift)
for the wiring.

The trade-off: at the `HTTPClient` layer you work with serialized bytes
(possibly gzipped protobuf) instead of decoded `SpanData`. This is
actually fine — you're going to POST those same bytes again on recovery,
so byte-identical replay is exactly what you want.

## What to persist on (retry policy)

| Outcome | Persist? | Reason |
|---|---|---|
| `Result.failure(_)` (URLSession error) | ✅ | Network blip — retry later |
| HTTP 5xx | ✅ | Collector unhealthy — retry later |
| HTTP 429 | ✅ | Explicit backpressure |
| HTTP 4xx (non-429) | ❌ | Body wrong or unauthorized — replay won't help |
| HTTP 2xx | ❌ | Already accepted |

The 4xx non-429 distinction matters: without it, the disk buffer fills up
with permanently-bad payloads and never drains.

## Where this trap does / doesn't apply

| Surface | Affected? | Notes |
|---|---|---|
| iOS — OTLP/HTTP **traces** | ✅ Yes | The original bite. `PersistingTraceHTTPClient` is the fix. |
| iOS — OTLP/HTTP **metrics** | ✅ Yes (latent) | Same upstream code path. If we add fail-persist for metrics, use the same `HTTPClient` injection — do not attempt a `MetricExporter` decorator. |
| iOS — **logs** | ❌ No | `MobileLogRecordProcessor` uses a RAM→disk model and observes failures via its own `RetryableExporter` wrapper. Structurally different. |
| Android — OTLP/HTTP traces | ❌ No | `OtlpHttpSpanExporter` in `opentelemetry-java` blocks on the HTTP result and returns the real code. A decorator works fine on Android. |
| Android — OTLP/gRPC | ❌ No | Same — Java SDK is synchronous w.r.t. result code. |

## How to apply this lesson

1. **Before designing a `SpanExporter` decorator on iOS**, check whether
   you actually need failure information. If you do, skip the decorator
   and design at the `HTTPClient` layer from the start.
2. **When porting a feature from Android to iOS**, do not assume the
   exporter contracts are equivalent. Java blocks; Swift returns early.
3. **If a "persist on failure" or "alert on failure" iOS feature is
   silently doing nothing in production**, this is the first thing to
   check — verify a real HTTP failure actually surfaces at your
   instrumentation point before assuming the wiring is correct.

## How this surfaced

Discovered 2026-04-23 during Gate 4 (RN iOS replay) validation. We had
shipped a `PersistingSpanExporter` decorator per the original plan; the
on-device test showed **0 rows persisted despite every export failing**.
Confirmed by reading
`opentelemetry-swift/Sources/Exporters/OpenTelemetryProtocolHttp/trace/OtlpHttpTraceExporter.swift`
and seeing `return .success` after an un-awaited `httpClient.send(...)`
closure. Redesigned around `PersistingTraceHTTPClient`; Gate 4 closed
shortly after.

## Cost of *not* knowing this

- ~1 day of "why is the persist buffer empty" debugging
- A green test suite that didn't catch the regression (mocked exporter
  returned the configured result, hiding the upstream's
  return-success-anyway behavior)
- A "fix" PR that compiled and merged but did nothing in production
