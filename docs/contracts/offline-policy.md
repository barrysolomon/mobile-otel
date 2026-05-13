# Offline policy

Controls what telemetry the SDK buffers while the device is offline. The default `bufferAll` preserves every event; tighter policies drop low-severity records to cap the disk-buffer growth a long offline streak would otherwise produce.

## Policy catalogue

| Mode | Android | iOS | Severity threshold | Dropped entirely? |
|---|---|---|---|---|
| `BUFFER_ALL` / `bufferAll` | [OfflinePolicy.kt:20](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/OfflinePolicy.kt#L20) | [OfflinePolicy.swift:9](../../otel-ios-mobile/Sources/OTelMobileSDK/Config/OfflinePolicy.swift#L9) | none | no |
| `ERROR_ONLY` / `errorOnly` | [OfflinePolicy.kt:25](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/OfflinePolicy.kt#L25) | [OfflinePolicy.swift:10](../../otel-ios-mobile/Sources/OTelMobileSDK/Config/OfflinePolicy.swift#L10) | ERROR | no |
| `WARN_AND_ABOVE` / `warnAndAbove` | [OfflinePolicy.kt:32](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/OfflinePolicy.kt#L32) | [OfflinePolicy.swift:11](../../otel-ios-mobile/Sources/OTelMobileSDK/Config/OfflinePolicy.swift#L11) | WARN | no |
| `DROP_ALL` / `dropAll` | [OfflinePolicy.kt:39](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/OfflinePolicy.kt#L39) | [OfflinePolicy.swift:12](../../otel-ios-mobile/Sources/OTelMobileSDK/Config/OfflinePolicy.swift#L12) | n/a | yes |

## Cross-platform invariants

- **Offline check runs at `onEmit`-entry, before coalescing.** The processor calls `isDeviceOffline()` first; if true, applies the policy. If `dropsAll`, the record is discarded. If `minSeverity` is non-nil, records below it are dropped.
- **Online events are never filtered by this policy.** The check is `if Self.isDeviceOffline() { ... }` — when the path monitor returns satisfied, every event passes through regardless of the policy mode.
- **The policy is set at SDK init.** Today there's no runtime swap; if a future remote-config feature wants to change the policy mid-session, both platforms must agree on whether to apply the change retroactively (no) or only to subsequent emits (yes).

## isDeviceOffline implementation

| Platform | File:line | Mechanism |
|---|---|---|
| Android | [MobileLogRecordProcessor.kt:149](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/MobileLogRecordProcessor.kt#L149) | Queries NetworkAvailabilityWatcher state |
| iOS | [MobileLogRecordProcessor.swift:472-478](../../otel-ios-mobile/Sources/OTelMobileSDK/Buffering/MobileLogRecordProcessor.swift#L472-L478) | NWPathMonitor snapshot, with a static `_offlineOverride` test seam |

iOS's static-override pattern is a known awkward shape (memory: `feedback_ios_nwpathmonitor_test_seam`) but cleanly bounded — only test code touches it.
