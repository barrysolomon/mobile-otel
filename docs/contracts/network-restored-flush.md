# Network-restored flush

When the device transitions from LOST → AVAILABLE, the processor immediately drains any buffered telemetry so a reconnection produces a fast catch-up rather than waiting for a periodic timer or the next policy match.

## Implementation

| Platform | Listener | Triggers method | Drains |
|---|---|---|---|
| Android | [NetworkAvailabilityCallbackAdapter.kt:24](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/network/NetworkAvailabilityCallbackAdapter.kt#L24) (`onAvailable`) | [MobileLogRecordProcessor.kt:503](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/MobileLogRecordProcessor.kt#L503) `flushWindow(minutes)` | RAM, last N minutes |
| iOS | [NetworkAvailabilityWatcher.swift:58-68](../../otel-ios-mobile/Sources/OTelMobileSDK/Network/NetworkAvailabilityWatcher.swift#L58-L68) (`.restored` transition) | [MobileLogRecordProcessor.swift:380](../../otel-ios-mobile/Sources/OTelMobileSDK/Buffering/MobileLogRecordProcessor.swift#L380) `forceFlushBuffered()` | RAM + disk |

## Deliberate platform divergence

The two platforms call different drain methods on purpose:

- **Android** uses `flushWindow(minutes)` because its offline persistence keeps everything in the disk buffer; a time-windowed slice gives a clean cut at "events since the offline streak started."
- **iOS** uses `forceFlushBuffered()` because its offline failure-persistence contract drains RAM into disk on each failed flush — the right recovery path is "drain RAM + disk," not a time-windowed slice. The `minutes` parameter is accepted on iOS for API parity with Android but is reserved for future use (long offline streaks where a window cut matters).

This divergence is the right one — it reflects different buffering strategies, not drift. The `attachNetworkWatcher(_:minutes:)` signature is shared so a future change can converge both platforms on the same drain method without a breaking call-site change.

## Cross-platform invariants

- **A LOST→AVAILABLE transition fires exactly once per transition.** Repeated `available` notifications without an intervening `lost` do not refire the flush.
- **The flush runs on a detached task / coroutine.** The watcher's notify path must not block on the export; both platforms hop onto a background task before calling the processor.
- **A failed flush re-persists to disk.** If the OTLP POST fails after a network-restored flush, the RAM events are written back to disk so the next flush attempt sees them. This is the "offline survives reconnect" promise.

## Go and producer

Not implemented. The Go collector processor is stateless and the producer DSL has a `network_restored` matcher that fires on the corresponding log event but does not itself manage transitions.
