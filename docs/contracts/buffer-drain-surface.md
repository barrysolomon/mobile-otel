# Buffer drain surface

Every public method on `MobileLogRecordProcessor` that drains the buffer. Each row documents what it drains, its return type, and the call sites that depend on it. Memory entry `feedback_ios_forceflush_two_methods` is the cautionary tale: getting this distinction wrong silently breaks offline contracts.

## Method catalogue

| Platform / Method | Visibility | Drains | Return | Used by |
|---|---|---|---|---|
| Android `forceFlush()` | public | RAM + disk | `CompletableResultCode` | shutdown; explicit caller flush |
| Android `flushWindow(windowMinutes)` | public | RAM, last N min | `CompletableResultCode` | policy-triggered selective flush; network-restored hook |
| iOS `forceFlush(explicitTimeout?)` | public (OTel protocol) | RAM + disk, synchronous | `ExportResult` | shutdown; explicit caller flush |
| iOS `forceFlushBuffered()` | **internal** | RAM + disk, synchronous | `BufferExportResult` (richer) | OTel protocol adapter; CONTINUOUS timer; lifecycle autoFlushQueue — **libdispatch threads only** |
| iOS `forceFlushBufferedAsync()` | **internal** | RAM + disk, `async` | `BufferExportResult` (richer) | network-restored hook; `OTelMobile.forceFlushAsync()`; any Swift-concurrency caller |
| iOS `flushWindow(minutes:)` | public | RAM + disk, last N min | `BufferExportResult` | policy-triggered selective flush |

Source line refs: Android [MobileLogRecordProcessor.kt:503](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/MobileLogRecordProcessor.kt#L503), [:818](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/MobileLogRecordProcessor.kt#L818); iOS [MobileLogRecordProcessor.swift:299](../../otel-ios-mobile/Sources/OTelMobileSDK/Buffering/MobileLogRecordProcessor.swift#L299), [:355](../../otel-ios-mobile/Sources/OTelMobileSDK/Buffering/MobileLogRecordProcessor.swift#L355), [:382](../../otel-ios-mobile/Sources/OTelMobileSDK/Buffering/MobileLogRecordProcessor.swift#L382), [:414](../../otel-ios-mobile/Sources/OTelMobileSDK/Buffering/MobileLogRecordProcessor.swift#L414).

## Why iOS has a sync bridge over an async core (issue #66)

`forceFlushBufferedAsync()` is the single implementation of the dual-tier drain. `forceFlushBuffered()` is a thin sync bridge over it — a `DispatchSemaphore` wait on a detached Task — kept only for callers that cannot await: the OTel `LogRecordProcessor.forceFlush` protocol surface, the CONTINUOUS-mode timer, and the lifecycle `autoFlushQueue`, all of which run on libdispatch threads.

**The bridge must never be called from Swift-concurrency (async) code.** The detached Task it waits on needs a slot on the same width-limited cooperative executor the async caller occupies; enough concurrent async callers park every executor thread and the whole pool deadlocks. This froze 8 test suites at once on 3-core CI runners (issue #66) while passing on many-core dev machines. Async callers use `forceFlushBufferedAsync()` (or the `OTelMobile.forceFlushAsync()` facade) directly. `ios-ci.yml`'s executor-starvation guard (strict cooperative pool) enforces this invariant per push.

`forceFlush(explicitTimeout:)` implements the upstream OTel `LogRecordProcessor` protocol — its return type is the binary `ExportResult` upstream defines. It's the only public drain method on the processor and it returns the richer result's binary projection.

Both sync variants drain RAM + disk; they differ only in return type. Pre-Track-5, `forceFlush` drained RAM only and `forceFlushBuffered` was public — customers picking the wrong method silently lost disk-buffered events on shutdown. The architecture-hardening epic made the protocol method drain both tiers via a thin adapter over the internal `forceFlushBuffered`.

## Cross-platform invariants

- **`forceFlush` always drains both tiers.** RAM-only drain is not a public concept; if you need RAM-only for a test, use the test-support helper (`bufferProcessor.injectEvent(_:)` on iOS).
- **`flushWindow` is the policy-fire path.** Policy matches always call `flushWindow(minutes:)`, never `forceFlush*`. The minutes argument comes from the matched policy's `flushWindowMinutes` action.
- **A failed flush must re-persist to disk.** All variants on both platforms re-write RAM events to disk on export failure (with seqId dedup). Without this, the "offline survives reconnect" promise is broken.
- **Drain methods must not block the calling thread indefinitely.** Android uses `CompletableResultCode` (async); iOS uses `DispatchSemaphore` with timeouts derived from the request's `timeoutInterval + 2s`.
- **Sync drain bridges must never run on a cooperative-executor thread** (iOS). Blocking an executor thread while waiting on executor-scheduled work is a forward-progress violation that deadlocks under load (issue #66). Async contexts call the `...Async` variants; only libdispatch threads may call the sync bridges.

## How to add a new drain method

Don't, unless the architecture-hardening epic explicitly leaves a gap. The current four methods (Android) / three methods (iOS, soon to be two after the epic) cover every use case observed across the SDKs. Additional drain methods accelerate the dual-method footgun this contract document exists to bound.
