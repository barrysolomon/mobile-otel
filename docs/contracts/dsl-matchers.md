# DSL matchers

The 21 matcher types defined in DSL v2. The producer (control-plane-ui graphToDSLv2) compiles a UI graph into a JSON DSL document. SDKs and the collector processor consume that JSON and run matchers against incoming telemetry.

A matcher fires when its attribute conditions match an event. Most matchers reduce to one or more attribute-condition pairs (e.g., `event.name = "app.crash"`) so the same matcher engine handles all of them.

## Matcher catalogue

Listed by JSON type name. Each row gives the file:line where the type is recognised on each platform and the canonical `event.name` (or attribute) it keys on.

| JSON `type` | Keys on | Android parse | iOS parse | Go parse | Producer |
|---|---|---|---|---|---|
| `crash` | `event.name = app.crash` | [PolicyEvaluator.kt:622](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L622) | [PolicyParser.swift:92](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift#L92) | attribute eval | graphToDSLv2.ts |
| `ui_freeze` | `event.name = ui.freeze` + `duration_ms` | [PolicyEvaluator.kt:633](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L633) | [PolicyParser.swift:94](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift#L94) | attribute eval | graphToDSLv2.ts |
| `event_match` | `event.name` (caller-supplied) | [PolicyEvaluator.kt:637](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L637) | [PolicyParser.swift:98](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift#L98) | attribute eval | graphToDSLv2.ts |
| `log_severity` | `severity_number` + optional `body` | [PolicyEvaluator.kt:642](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L642) | [PolicyParser.swift:103](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift#L103) | attribute eval | graphToDSLv2.ts |
| `http_match` | `event.name = http.error` + `http.status_code` | [PolicyEvaluator.kt:646](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L646) | [PolicyParser.swift:117](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift#L117) | attribute eval | graphToDSLv2.ts |
| `exception_pattern` | `event.name = app.crash` + `exception.type` + `exception.message` | [PolicyEvaluator.kt:654](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L654) | [PolicyParser.swift:123](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift#L123) | attribute eval | graphToDSLv2.ts |
| `metric_threshold` | metric-name `event.name` + `value` op | [PolicyEvaluator.kt:659](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L659) | [PolicyParser.swift:133](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift#L133) | attribute eval | graphToDSLv2.ts |
| `slow_operation` | operation `event.name` + `duration_ms` | [PolicyEvaluator.kt:664](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L664) | [PolicyParser.swift:148](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift#L148) | attribute eval | graphToDSLv2.ts |
| `frame_drop` | `event.name = ui.jank` + `dropped_frames` | [PolicyEvaluator.kt:669](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L669) | [PolicyParser.swift:157](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift#L157) | attribute eval | graphToDSLv2.ts |
| `network_loss` | `event.name = network.loss` | [PolicyEvaluator.kt:673](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L673) | [PolicyParser.swift:163](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift#L163) | attribute eval | graphToDSLv2.ts |
| `network_restored` | `event.name = network.restored` | [PolicyEvaluator.kt:677](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L677) | [PolicyParser.swift:165](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift#L165) | attribute eval | graphToDSLv2.ts |
| `slow_request` | `event.name = http.request` + `duration_ms` | [PolicyEvaluator.kt:680](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L680) | [PolicyParser.swift:167](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift#L167) | attribute eval | graphToDSLv2.ts |
| `low_memory` | `event.name = device.low_memory` + `available_mb` | [PolicyEvaluator.kt:686](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L686) | [PolicyParser.swift:173](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift#L173) | attribute eval | graphToDSLv2.ts |
| `battery_drain` | `event.name = device.battery_drain` + `drain_rate` | [PolicyEvaluator.kt:690](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L690) | [PolicyParser.swift:179](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift#L179) | attribute eval | graphToDSLv2.ts |
| `thermal_throttle` | `event.name = device.thermal_throttle` | [PolicyEvaluator.kt:694](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L694) | [PolicyParser.swift:185](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift#L185) | attribute eval | graphToDSLv2.ts |
| `storage_low` | `event.name = device.storage_low` + `available_mb` | [PolicyEvaluator.kt:698](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L698) | [PolicyParser.swift:187](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift#L187) | attribute eval | graphToDSLv2.ts |
| `predictive_risk` | risk-score conditions | [PolicyEvaluator.kt:702](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L702) | [PolicyParser.swift:191](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift#L191) | attribute eval | graphToDSLv2.ts |
| `anr` | `event.name = app.anr` | [PolicyEvaluator.kt:706](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L706) | [PolicyParser.swift:195](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift#L195) | attribute eval | graphToDSLv2.ts |
| `app_lifecycle` | `event.name = app.foreground` / `app.background` | [PolicyEvaluator.kt:709](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L709) | [PolicyParser.swift:197](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift#L197) | attribute eval | graphToDSLv2.ts |
| `resource_snapshot` | `event.name = resource.snapshot` | [PolicyEvaluator.kt:712](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L712) | [PolicyParser.swift:201](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift#L201) | attribute eval | graphToDSLv2.ts |
| `timeout` | state-timeout (not a positive flush trigger) | [PolicyEvaluator.kt:716](../../otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyEvaluator.kt#L716) | [PolicyParser.swift:205](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyParser.swift#L205) | attribute eval | graphToDSLv2.ts |

## Geo/device context — current parity gap

Android's evaluator additionally supports geo and device matchers as a `Match.geo` and `Match.device` field. iOS and the Go processor do **not** — iOS's `Match` model is attribute-only ([InternalPolicyModels.swift:42-49](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/InternalPolicyModels.swift#L42-L49)) and the engine explicitly drops the `contextSnapshot` parameter ([PolicyEvaluator.swift:77-92](../../otel-ios-mobile/Sources/OTelMobileSDK/Policy/PolicyEvaluator.swift#L77-L92)).

A policy that uses geo or device constraints in the control-plane UI will match on Android and silently no-match on iOS / in the collector. This is a known gap and the highest-priority cross-platform parity item in the architecture-hardening epic.

## How to add a new matcher

1. Add a case to Android's parser switch and an attribute-condition translation.
2. Add the matching case to iOS's `PolicyParser.swift:matcherToMatch(_:)`.
3. Add a case to the Go processor's evaluator if the matcher needs server-side evaluation.
4. Add an entry to this table.
5. Add a fixture under `golden/dsl/` (see `docs/contracts/README.md` once the golden harness lands).

Skipping any of those steps causes silent platform drift — the matcher fires on the platforms that parsed it and silently no-matches on the platforms that didn't. This is the same failure mode as the geo/device gap above.
