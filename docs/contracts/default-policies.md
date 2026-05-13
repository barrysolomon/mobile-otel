# Default seeded policies

When the SDK starts without a remote-config connection (or before one lands), it falls back to a hard-coded policy set. These defaults make HYBRID and CONDITIONAL modes usable out-of-the-box for the canonical signals (`app.crash`, `http.error`, `ui.freeze`).

## Default catalogue

| Policy ID | Trigger | Flush window | Android | iOS |
|---|---|---|---|---|
| `crash-recovery` | `event.name = app.crash` | 5 min | [PolicyEvaluator.kt:98+](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L98) | [OTelMobile.swift:269-277](../../otel-ios-mobile/Sources/OTelMobileSDK/OTelMobile.swift#L269-L277) |
| `http-error-detector` | `event.name = http.error` | 2 min | [PolicyEvaluator.kt:98+](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L98) | [OTelMobile.swift:278-286](../../otel-ios-mobile/Sources/OTelMobileSDK/OTelMobile.swift#L278-L286) |
| `ui-freeze-detector` | `event.name = ui.freeze` + `duration_ms > 1000` | 2 min | [PolicyEvaluator.kt:98+](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L98) | **not seeded on iOS** |

## Parity gap: `ui-freeze-detector` missing on iOS

Android hard-codes three defaults; iOS only seeds two. A `ui.freeze` log emitted on iOS in HYBRID mode will not trigger a flush unless a remote config has loaded a matching policy. This is silent and parallel to the geo/device gap — the kind of drift these contract docs exist to surface.

## Why the SDK seeds defaults at all

Per the iOS comment ([OTelMobile.swift:261-267](../../otel-ios-mobile/Sources/OTelMobileSDK/OTelMobile.swift#L261-L267)): without seeded defaults, CONDITIONAL and HYBRID modes can't flush on the canonical signals until a remote config arrives — but most demo setups never get a remote config. Customers using their own gateway can replace these wholesale via `policyEvaluator.updatePolicies(...)`.

The defaults are not negotiable for the demo experience and the OOTB customer who never plugs into the gateway. Whenever a new canonical signal joins the catalogue (next likely: `app.anr`), it should be added to both platforms' seed lists in the same commit and a row added here.

## Go and producer

The Go processor has no seeded defaults — it expects config from the gateway. The producer (graphToDSLv2) does not seed; the UI starts empty and the user composes policies. Both are intentional.
