# React Native SDK Epic (Phase 19a — CP-050)

> **Goal:** Ship a production-grade React Native SDK (`@dash0/mobile-react-native`) that bridges JS/TS callers into the existing native Android + iOS SDKs, with an RN-flavored AstronomyShop demo validated end-to-end against Dash0.
> **Parent epic:** [COMPETITIVE_PARITY_EPIC.md — Phase 19 CP-050](COMPETITIVE_PARITY_EPIC.md#phase-19--cross-platform-framework-support)
> **Created:** 2026-04-20
> **Status:** JS + docs + **Android + iOS all GREEN** 2026-04-20. RN 0.85 demo produces a real 140 MB `app-debug.apk` (Android, via mavenLocal) and a 239 MB `AstronomyShopRN.app` (iOS, via hybrid CocoaPods + local SwiftPM). 83 Jest tests green (70 package + 13 demo).
> **Business driver:** [Innovapptive deal](../INNOVAPPTIVE_SUCCESS_CRITERIA.md) — active prospect with React Native + Realm stack; RN support is a gate to closing.

---

## Architectural Decision: Native-First Bridge

JS layer is thin. All buffering, policy evaluation, disk spill, OTLP export, and crash capture happens in the existing Kotlin/Swift SDKs. The RN module marshals calls across the RN native-module bridge.

**Why native-first (vs. JS-first like Sentry):**
- The hard work — dual-tier buffer, policy DSL, OTLP/gRPC, session management, crash recovery — is already built and tested twice (Android + iOS). Reusing it is ~10× less work than reimplementing in JS.
- Native modules own the process lifecycle. JS engines (Hermes/JSC) can be torn down independently; crashes in the JS thread shouldn't lose buffered events held in native memory.
- OTel semantic conventions for mobile are native-centric (`device.*`, `os.*`, `app.*`). Emitting from native means these are populated correctly without RN re-synthesizing them.

**Tradeoff accepted:** every JS → native call crosses a bridge (serialization cost). We mitigate by batching (`emitBatch(events[])`) and by keeping the JS API surface small.

### Layering

```
┌──────────────────────────────────────────────────────────────┐
│ App JS/TS code                                                │
│   import { Dash0Mobile } from '@dash0/mobile-react-native'   │
│   Dash0Mobile.start({...}); Dash0Mobile.log('cart.add', {...})│
└──────────────────────────────────────────────────────────────┘
                          │ JS calls
                          ▼
┌──────────────────────────────────────────────────────────────┐
│ @dash0/mobile-react-native (TypeScript)                       │
│   • Public API (start, log, span, metric, flushWindow)        │
│   • OTel API shim (sets globalThis Tracer/Logger/Meter)       │
│   • RN-specific auto-instrumentation:                         │
│       - fetch / XMLHttpRequest wrappers                       │
│       - RN ErrorUtils + unhandled promise rejections          │
│       - AppState (foreground/background) events               │
│       - React Navigation screen tracking (opt-in)             │
│       - Touchable press events (opt-in)                       │
│   • Batched bridge calls (debounced 50ms)                     │
└──────────────────────────────────────────────────────────────┘
                          │ NativeModules.Dash0Mobile.*
                          ▼
┌──────────────────────────────────────────┬───────────────────┐
│ Android: Dash0MobileModule (Kotlin)      │ iOS: RCTDash0Mobile│
│   extends ReactContextBaseJavaModule     │    (ObjC/Swift)    │
│                                          │                    │
│   Forwards into existing:                │   Forwards into:   │
│   io.opentelemetry.android.mobile        │   OTelMobileSDK   │
│     .OTelMobile                          │     .OTelMobile   │
└──────────────────────────────────────────┴───────────────────┘
```

---

## Scope — In vs Out

### In scope
- NPM package `@dash0/mobile-react-native`
- Android native module (Kotlin) forwarding to existing SDK
- iOS native module (Swift, bridged via ObjC header) forwarding to existing SDK
- JS API: `start`, `log(name, attrs, severity?)`, `span(name, fn)`, `startSpan`/`endSpan`, `recordMetric`, `flushWindow(minutes)`, `shutdown`
- Auto-instrumentation (opt-out via config):
  - `fetch` + `XMLHttpRequest` → HTTP spans with OTel semconv
  - `ErrorUtils.setGlobalHandler` → error logs
  - `unhandledrejection` → error logs
  - `AppState` → `app.foreground` / `app.background` logs
- Opt-in instrumentations:
  - React Navigation v6 listener → `ui.screen_view` logs + `page.<name>` spans
  - Touchable press wrappers → `ui.tap` events
- RN-flavored AstronomyShop demo (new repo dir: `examples/upstream-demo-app-rn/`) validated against Dash0
- CI: Jest + iOS sim + Android emulator builds + E2E validation script
- Docs: `docs/REACT_NATIVE_SDK_GUIDE.md`, `docs/HOW_TO_DEMO_RN.md`

### Out of scope (follow-up epics)
- **Realm DataStore instrumentation** (Innovapptive-specific) — separate epic, depends on this one
- **Amplify DataStore for RN** — already scoped in Phase 13.6 for native Android; RN-adjacent work deferred
- **Brownfield integration sample** (RN screens embedded in native shell) — Phase 19a.2
- **Expo config plugin** — Phase 19a.3, unblocks Expo users without eject
- **Kotlin Multiplatform / Compose Multiplatform** — CP-052, separate path
- **Flutter plugin** — CP-051, separate path
- **Screenshot / Wireframe** RN ViewModifier equivalents — needs same privacy design as iOS (deferred there too)

---

## TDD Discipline (non-negotiable)

Per user mandate: **TDD and real-app testing are critical.** Every deliverable below has three layers of verification, and no task is "done" until all three pass.

### Layer 1 — Unit (Jest, fast)
- Bridge contract tests: mock `NativeModules.Dash0Mobile`, assert JS layer sends correct payload shapes, attribute keys, severity enum values, batch boundaries.
- OTel API shim tests: `tracer.startSpan()` produces the same payload as direct `Dash0Mobile.startSpan()`.
- Auto-instrumentation tests: monkey-patched `fetch` produces an HTTP span with `http.request.method`, `url.full`, `http.response.status_code` matching semconv.
- Error handler tests: thrown error reaches bridge with `exception.type`, `exception.message`, `exception.stacktrace` attributes.

### Layer 2 — Native module (XCTest / JUnit, fast)
- Android: `Dash0MobileModuleTest` — feeds ReadableMap fixtures, asserts forwarded calls hit a mock `OTelMobile` with correct args.
- iOS: `RCTDash0MobileTests` — same shape, using Swift Testing + mock `OTelMobile`.

### Layer 3 — Real-app E2E (simulator + emulator + Dash0)
- `scripts/test/validate-rn-end-to-end.sh` — boots RN AstronomyShop on iOS sim + Android emulator, runs auto-demo loop for 75s, queries Dash0 MCP for expected logs/spans/metrics, asserts landed. Mirror of `validate-ios-end-to-end.sh`.
- Green CI requires all three layers pass on every PR touching `packages/react-native/` or `examples/upstream-demo-app-rn/`.

### Red → Green → Refactor discipline
For each deliverable below:
1. Write the failing test first (the test files are listed explicitly in each task).
2. Implement minimum code to pass.
3. Refactor only after green. Commit each step.

---

## Phases

### Phase 19a.0 — Foundations & failing test scaffolding (1 day)

| ID | Deliverable | Tests that must fail first | Done when |
|----|-------------|----------------------------|-----------|
| RN-001 | NPM package skeleton (`packages/react-native/`) with `package.json`, `tsconfig.json`, Jest config, index.ts stub | `__tests__/api.contract.test.ts` (imports `Dash0Mobile`, asserts shape) | `npm test` runs, 1 failing test |
| RN-002 | Epic doc (this file) + link from COMPETITIVE_PARITY_EPIC + BACKLOG entry | n/a | Files land, CI lint passes |
| RN-003 | RN demo app scaffold (`examples/upstream-demo-app-rn/`) — bare RN 0.76+ template, no Expo | `e2e/boot.e2e.ts` (launches app, asserts title renders) | App boots on iOS sim + Android emulator |

### Phase 19a.1 — Bridge contract (2 days)

| ID | Deliverable | Tests that must fail first | Done when |
|----|-------------|----------------------------|-----------|
| RN-010 | TS types for bridge payloads (`src/bridge/types.ts`): `StartConfig`, `LogPayload`, `SpanPayload`, `MetricPayload` | `__tests__/bridge/types.contract.test.ts` | ✅ DONE 2026-04-20 — Types compile, 5 tests green |
| RN-011 | JS → native call marshaller (`src/bridge/NativeBridge.ts`) with 50ms debounce batching | `__tests__/bridge/nativeBridge.test.ts` (asserts batching, ordering, flush, retry, backpressure) | ✅ DONE 2026-04-20 — 6 tests green (debounce 50ms, MAX_QUEUE 10000, exponential retry up to 5 attempts, drops oldest) |
| RN-012 | Android native module (`Dash0MobileModule.kt`) — start/log/span/metric/flushWindow | `Dash0MobileModuleTest.kt` (JUnit, mock OTelMobile) | 🟡 CODE COMPLETE 2026-04-20 — `android/src/main/java/com/dash0/mobile/reactnative/{BridgeCallSink,Dash0MobileModule,OTelMobileCallSink,Dash0MobilePackage}.kt` + 10-case JUnit test. Gradle build pending wire-up into host demo app (RN-003). |
| RN-013 | iOS native module (`Dash0MobileModule.swift` + `.m` bridge header) | `Dash0MobileModuleTests.swift` (Swift Testing) | 🟡 CODE COMPLETE 2026-04-20 — `ios/{BridgeCallSink,Dash0MobileBridgeDispatcher,RCTDash0MobileModule.m,RCTDash0MobileModule.swift,OTelMobileCallSink}.swift` + 10-case Swift Testing suite. Xcode build pending host demo app (RN-003). |
| RN-013b | Podspec + react-native.config.js (autolinking) | n/a | ✅ DONE 2026-04-20 — `Dash0Mobile.podspec` + `react-native.config.js` |

### Phase 19a.2 — JS OTel surface (2 days)

| ID | Deliverable | Tests that must fail first | Done when |
|----|-------------|----------------------------|-----------|
| RN-020 | Public JS API (`src/index.ts`): `Dash0Mobile.start/log/span/startSpan/recordMetric/flushWindow/shutdown` | `__tests__/api.public.test.ts` | ✅ DONE 2026-04-20 — 12 tests green; pre-start calls are no-ops; test injection via `__setNativeForTesting` |
| RN-021 | OTel API shim — expose `@opentelemetry/api` compatible `Tracer`/`Logger`/`Meter` backed by bridge | `__tests__/otel.compat.test.ts` | ✅ DONE 2026-04-20 — 5 tests green; minimal `otel` namespace (no upstream dep); maps span kind + status codes |

### Phase 19a.3 — Auto-instrumentation (3 days)

| ID | Deliverable | Tests that must fail first | Done when |
|----|-------------|----------------------------|-----------|
| RN-030 | `fetch` + `XMLHttpRequest` wrappers → HTTP spans with semconv | `__tests__/instr/fetch.test.ts`, `xhr.test.ts` | ✅ DONE 2026-04-20 — fetch (4 tests) + XHR Proxy-wrapped ctor (5 tests); shared `ignoredHosts` |
| RN-031 | `ErrorUtils.setGlobalHandler` + `unhandledrejection` → error logs | `__tests__/instr/errors.test.ts`, `unhandledRejection.test.ts` | ✅ DONE 2026-04-20 — sync handler (5 tests) + async handler (5 tests); shared dedupe keyspace |
| RN-032 | `AppState` subscriber → foreground/background logs | `__tests__/instr/appstate.test.ts` | ✅ DONE 2026-04-20 — 6 tests; emits `app.foreground`/`app.background`/`app.inactive`; suppresses consecutive dupes |
| RN-033 | React Navigation v6 listener (opt-in) → screen view + page span | `__tests__/instr/navigation.test.ts` | ✅ DONE 2026-04-20 — 5 tests; navRef-based, emits `ui.screen_view` log + `page.<name>` span, ends previous on transition |
| RN-034 | `Dash0Touchable` HOC / hook wrappers for tap events (opt-in) | `__tests__/instr/touch.test.ts` | ✅ DONE 2026-04-20 — 5 tests; `withTapTelemetry(target, handler, extraAttrs?)` emits `ui.tap`, forwards args/throws |

### Phase 19a.4 — RN AstronomyShop demo (2 days)

| ID | Deliverable | Tests that must fail first | Done when |
|----|-------------|----------------------------|-----------|
| RN-040 | Port AstronomyShop UI screens to RN (Home, ProductList, ProductDetail, Cart, Checkout) using React Navigation + Zustand | `e2e/shop.flow.e2e.ts` (Detox: browse → add → checkout) | E2E green on iOS + Android |
| RN-041 | AutoDemoDriver (JS) — mirror iOS AutoDemoDriver; 800ms cadence, 5 phases, emits the same log/span/metric palette via RN SDK | `__tests__/demo/autoDemoDriver.test.ts` | Drives real traffic in demo app |
| RN-042 | Rich trace shapes: 14-span checkout tree, 3-span product view, cart counter/histogram metrics | `__tests__/demo/traceShape.test.ts` | Span tree matches iOS shape |

### Phase 19a.5 — E2E validation & CI (1 day)

| ID | Deliverable | Tests that must fail first | Done when |
|----|-------------|----------------------------|-----------|
| RN-050 | `scripts/test/validate-rn-end-to-end.sh` — boots both simulators, runs auto-demo 75s, queries Dash0 MCP, asserts ≥1 log + ≥1 span + metrics landed with `service.name=otel-rn-astronomy-shop` | script itself is the test | Exit 0 only when real data landed |
| RN-051 | `.github/workflows/rn-tests.yml` — Jest + Android build + iOS build + safety audit | CI | Green on PR |
| RN-052 | Update `run-tests.sh` to include `--rn` flag | n/a | Full cross-platform run: Android + Go + iOS + RN |

### Phase 19a.6 — Docs & handoff (0.5 day)

| ID | Deliverable | Done when |
|----|-------------|-----------|
| RN-060 | `docs/REACT_NATIVE_SDK_GUIDE.md` — integration, config, auto-instrumentation toggles | ✅ DONE 2026-04-20 |
| RN-061 | `docs/HOW_TO_DEMO_RN.md` — demo runbook (mirror HOW_TO_DEMO_IOS.md) | ✅ DONE 2026-04-20 — Jest path ready, device runbook specced (blocks on RN-003) |
| RN-062 | `docs/RN_ANDROID_IOS_PARITY.md` — feature matrix across all three SDKs | ✅ DONE 2026-04-20 |
| RN-063 | Update `mobile-otel/CLAUDE.md` with RN commands + gotchas | ✅ DONE 2026-04-20 — added RN section with commands, native-first architecture note, auto-capture defaults |

---

## Success criteria

1. **TDD discipline held**: every commit on the feature branch has test-first diffs. Reviewer should be able to see `test fails → test passes` sequence in git history.
2. **All three test layers green**: Jest + native unit + real-app E2E. CI fails if any layer misses.
3. **Validated in Dash0**: `service.name=otel-rn-astronomy-shop` shows logs + spans + metrics after 75s auto-demo run. Same query shape as iOS validation.
4. **Parity matrix passes**: all items in `docs/RN_ANDROID_IOS_PARITY.md` that are marked "✅ RN" actually work in the demo app.
5. **Innovapptive-ready**: demo can be shown to Durga as evidence we support their RN surface. Realm is a follow-up epic but the RN bridge must land first.

## Risks & mitigations

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| RN New Architecture (Fabric / TurboModules) vs old arch compat | HIGH | Target TurboModules from day one; RN 0.76+ defaults to New Arch. Provide an old-arch shim only if a customer demands it. |
| Bridge serialization cost dominates under heavy traffic | MED | Batching (50ms debounce) + benchmark in RN-011 tests; fall back to per-call if a metric shows batching loses events. |
| Hermes JS engine quirks (especially with Proxy/globalThis OTel shim) | MED | CI runs tests under Hermes explicitly, not just Node. |
| Crash in JS thread tears down RN before native flush | LOW-MED | Native SDK already buffers to disk. Add a native-side `onJSCrash` signal forwarded into existing crash-marker path. |
| Expo users can't use this without eject | HIGH (growth blocker) | Phase 19a.3 (Expo config plugin) is deferred but tracked. Clearly document in the guide. |
| Realm not in this epic, but Innovapptive needs both | CERTAIN | Follow-up epic `REALM_INSTRUMENTATION_EPIC.md` (to be written) depends on this one. Sequencing is clear to the customer. |

## Sequencing

All phases are sequential. Each phase gates the next via its own tests. Total estimate: **~11.5 engineering days** of focused work, which is one 2-week sprint with buffer.

## Out-of-scope items tracked for follow-up

- **REALM-001..N** — MongoDB Realm DataStore instrumentation for RN (Innovapptive)
- **AMPLIFY-RN-001..N** — AWS Amplify DataStore instrumentation for RN
- **EXPO-001** — Expo config plugin for no-eject integration
- **RN-BROWNFIELD-001** — Sample brownfield app: RN screens inside existing Android/iOS shell
- **RN-SCREENSHOT-001** — RN screenshot/wireframe support (gated on privacy design, same gate as iOS)
