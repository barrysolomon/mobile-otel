# Production Readiness Review — Dash0 Mobile OpenTelemetry SDK

**Date:** 2026-06-09
**Scope:** All 4 platform targets — Android (Kotlin native), iPhone (Swift native), Android (React Native), iPhone (React Native)
**Review type:** Adversarial security audit + host-app stability / fault-isolation audit
**Method:** Per-platform deep code review (≈60K LOC Kotlin, 11K Swift, 1.4K shared TS, ~1.9K RN bridge) plus a cross-cutting supply-chain / resilience / test-posture sweep. Every finding is grounded in `file:line` evidence.

---

## How to read this report

This document serves two audiences:

- **Decision-makers / customers** — read the **Executive Summary** and the **Readiness Scorecard**. The plain-language verdict on "will this break my app or leak my users' data?" is there.
- **SecOps / SRE / engineering** — read **Part 1 (Stability)**, **Part 2 (Security)**, the **Consolidated Must-Fix Backlog**, and the **SecOps Adoption Checklist**. Findings cite exact files and lines.

Severity is the **combined** product of likelihood × blast radius on the *host application* (not just the SDK).

---

## Executive Summary

**Verdict: A strong, well-architected pre-1.0 SDK that is *not yet* production-hardened across all four platforms.** The foundation is genuinely good — better than most commercial mobile telemetry SDKs at the design level. But there are a small number of concrete, fixable defects where SDK code can crash the host app, break its networking, leak user data, or fail to compile for integrators. None are deep architectural problems; all are addressable.

**Did the team do a good job on stability and "do no harm"?** Largely yes. The intended safety model is explicit and credible: there is a written `SDK_SAFETY.md` with hard non-negotiables, an async-signal-safe crash handler, bounded RAM/disk buffers, retry with exponential backoff + jitter, export-after-confirm durability, and graceful offline degradation. The export and buffering layers swallow their own exceptions everywhere and degrade silently. **The gaps are not in the design — they are in a handful of glue paths that run on the host's own threads and were not wrapped in fault isolation.** Fix those (a few `try/catch` blocks, one thread hop, one compile fix, one buffer cap) and the "will not break anything" claim becomes defensible.

**Can a customer feel secure today?** Not for unconditional fleet-wide rollout. With the Critical items fixed and screenshot/wireframe capture turned off-by-default (or behind explicit consent), yes — Android in particular is close. As-is, an early-adopter / opt-in pilot is appropriate; a "set it and forget it, it can't hurt you" guarantee is premature.

---

## Readiness Scorecard

| Platform | Security | Host-App Stability | Functionality | Overall |
|---|---|---|---|---|
| **Android / Kotlin (native)** | ⚠️ Needs work | ⚠️ Minor risks | ✅ Solid | **⚠️ Almost — fix Critical/High** |
| **iPhone / Swift (native)** | ⚠️ Needs work (most exposed) | ⚠️ Minor risks | ✅ Solid | **⚠️ Needs work** |
| **Android / React Native** | 🟡 Conditional | ⚠️ Risky (network glue) | ✅ Works | **🟡 Conditional** |
| **iPhone / React Native** | 🟡 Conditional (cleanest bridge) | 🔴 Build-breaking defect | 🔴 Production sink won't compile | **🔴 Blocked until sink fixed** |

Legend: ✅ ready · 🟡 conditional/opt-in · ⚠️ needs work · 🔴 blocking

---

## Part 1 — Stability & "Will it break my app?"

For a telemetry SDK, the prime directive is **fault isolation**: the host app must behave identically whether the SDK succeeds, fails, or is offline. We scored each platform against the four ways a mobile SDK breaks its host: (1) an uncaught exception crashes the app, (2) main-thread work causes ANR/freeze, (3) unbounded buffers exhaust memory/disk, (4) retry storms drain battery/data.

### What's genuinely strong (credit where due)

- **Documented safety contract.** `docs/SDK_SAFETY.md` codifies non-negotiables: no `fatalError`/`try!`/`abort()`, async-signal-safe crash handler, non-blocking `onEmit()`, export failures never block emission.
- **Crash handler is textbook.** iOS signal handler (`ErrorsInstrumentation.swift:283`) does only a fixed 3-byte `write(2)`, restores and re-raises the previous handler, so Sentry/Crashlytics/PLCrashReporter still fire. No allocation, no locks, no Foundation in signal context.
- **Durable, bounded buffering.** Two-tier RAM→disk (Room on Android, SQLite on iOS); events removed only after confirmed export; `fallbackToDestructiveMigration` so a schema mismatch recreates rather than crashes; disk write failures are caught and logged, never fatal.
- **Disciplined retry/backoff.** Bounded retries (3) + exponential backoff + full jitter + 60s cap on both native platforms — no thundering herd, no tight-loop retry.
- **Swizzling done right (iOS).** Idempotent, calls through to the original IMP, double-guarded against recursion.
- **Error-handler chaining (RN).** `errors.ts` always chains to the previous global handler inside its own try/catch — never hides a crash from RN redbox or other reporters.
- **Crash-safety primitives are clean (iOS/RN bridge).** Zero `try!`/`as!`/force-unwraps in the RN iOS bridge handlers; only 3 force-unwraps in the entire iOS native source, all provably guarded.
- **Deep test suite on Android.** ~130 test files / ~980 test functions including `BufferCrashPathTest`, `TtlEvictionStressTest`, `OfflineReconnectionTest`, and 28 shell scenario scripts (RAM overflow, disk TTL, crash flush, network loss).

### Stability defects (host-impacting)

| Sev | Platform | Defect | Evidence | How it breaks the host |
|---|---|---|---|---|
| 🔴 Critical | iPhone / RN | **Production sink doesn't conform to protocol → host build fails.** `OTelMobileCallSink.startSpan` is missing the `parentSpanId` param the `BridgeCallSink` protocol declares. The file is excluded from `Package.swift`, the podspec, and all tests, so this package's CI is green while every integrator who wires real telemetry per the docs gets a **compile error**. | `OTelMobileCallSink.swift:77` vs `BridgeCallSink.swift:12` | Customer follows the documented integration → their app won't build. |
| 🔴 Critical | Android / RN | **Network interceptors are not fault-isolated.** Span setup (`startSpan`, BigInt math, `bridge.emit`) runs *before* the host's `fetch`/`XHR.send` call and is not wrapped in try/catch. A throw means the host's HTTP request never executes. | `xhr.ts:62`, `fetch.ts:53` | One regression in telemetry code → **all networking in the host app breaks.** Highest blast radius in the SDK. |
| 🔴 Critical | Android native | **Touch-dispatch chain is unguarded.** Host `dispatchTouchEvent` → `HubDispatcher` → `listeners.forEach { onTouchEvent }` has no try/catch. A listener throwing (teardown race, deep view tree `StackOverflowError`, custom listener bug) propagates into the host's input dispatch. | `WindowEventHub.kt:51`, `WindowEventHubInstaller.kt:73` | **App crashes on a tap.** A telemetry SDK must never crash on routine user input. |
| 🔴 Critical | iPhone native | **Off-main-thread UIKit access in capture.** Screenshot/wireframe capture on the error and policy-match paths runs `UIWindow.layer.render`, recursive view-tree walks, and `UIApplication.connectedScenes` from a background `Task.detached` — no main-thread hop. (The `screen_view`/`tap` paths correctly hop to main; the error/policy paths don't.) | `OTelMobile.swift:678-693`, `ScreenshotInstrumentation.swift:90-118` | UIKit off-main is undefined behavior → **intermittent host crash/corruption, exactly when the app is already erroring.** |
| 🟠 High | Android native | **SDK init is not fault-isolated.** `MobileOtel.initialize` and the per-module `inst.install()` loop run unwrapped inside `Application.onCreate`. One module's `install()` throwing aborts the rest and propagates. | `MobileOtel.kt:99`, `InstrumentationRegistry.kt:55` | **App fails to launch at all** — worst-possible moment. |
| 🟠 High | iPhone / RN | **Unbounded `liveSpans` memory leak.** Entries inserted on `spanStart`, removed only on `spanEnd`/`shutdown`. Any orphaned start (JS crash mid-span, batch trim dropping the matching end, navigation away) leaks a live OTel `Span` + attributes forever. No cap/TTL/LRU. | `OTelMobileCallSink.swift:16,92-105` | Slow monotonic host memory growth over a long session. |
| 🟠 High | Android (cross-platform) | **RAM buffer has no byte cap** — only a 5,000-event count cap, violating the SDK's own `SDK_SAFETY.md` non-negotiable #3. iOS correctly enforces 10 MB total + 256 KB/event. | `MobileLogRecordProcessor.kt` (count-only) vs `RAMEventBuffer.swift:34` | 5,000 large wireframe/screenshot events can balloon RAM unboundedly on Android. |
| 🟡 Med | iPhone native | **Main-thread render hitch on error bursts.** Even after the off-main fix, screenshot render + scale + redaction + base64 runs synchronously on main on every error/policy match. Rate-limited (1/min default) but a launch-time error burst risks a visible hitch / `0x8badf00d` watchdog. | `ScreenshotInstrumentation.swift:92` | UI jank / watchdog kill under error storms. |
| 🟡 Med | Android / RN | **Whole-batch drop on one malformed payload.** `emitBatch` rejects the entire batch if one payload has a wrong-typed numeric field (`getInt`/`getDouble` throw); JS then retries the same poisoned batch 5×, so it never drains. | `Dash0MobileModule.kt:60-97` | One bad event discards up to a full 50ms window of good telemetry (data loss, not a crash). |
| 🟡 Med | Android / RN | **Gauge observable callback never closed.** `recordMetric(..., 'gauge')` registers a new async gauge every call, never closed; closure retained forever. | `OTelMobileCallSink.kt:122` | Monotonic instrument/memory leak if a host records gauges in a loop. |
| 🟡 Med | Android / RN | **No double-install guard on global patchers.** Fast Refresh / double `start()` stacks wrappers; `uninstall` then can't restore the real original. | `xhr.ts:114`, `fetch.ts:86` | Permanent extra wrapper layer + double-emitted spans. |
| 🟢 Low | iPhone native | **`precondition` in config init can crash host.** `precondition(maxOfflineDiskBytes > 0)` traps the process on a bad developer value instead of clamping (as `SamplingConfig` already does). | `OfflineBudgetConfig.swift:24` | Programmer-error config value crashes the app rather than degrading. |

---

## Part 2 — Security & Data Protection (summary)

Full detail in the security pass; the headline items that gate production:

| Sev | Platform | Issue | Evidence |
|---|---|---|---|
| 🔴 Critical | Android native | **Ingest auth token logged to Logcat in plaintext** at init, no debug guard. | `MobileLoggerProvider.kt:118` |
| 🔴 Critical | iPhone native | **SwiftUI `SecureField` passwords render in plaintext into screenshots** — redaction only covers UIKit `UITextField` and never checks `isSecureTextEntry`. | `ScreenshotInstrumentation.swift:176` |
| 🟠 High | iPhone + cross | **Screenshot + wireframe capture DEFAULT-ON** (`autoCaptureOptions = .all`), captured on every error & policy match — **contradicts** `docs/design/screenshot-wireframe-privacy.md`, which claims default-off plus a `shouldCapture` consent gate that **does not exist in code**. Android correctly defaults these OFF. | `MobileConfig.swift:92`, `AutoCaptureOptions.swift:21` |
| 🟠 High | iPhone native | **No file protection (NSFileProtection) on any at-rest store** — screenshots/wireframes/URLs/crash data persist cleartext; recoverable via backup/jailbreak/forensics. | `DiskSpanBuffer.swift:244`, `DiskLogBuffer.swift` |
| 🟠 High | Android native | **Crash breadcrumbs persist unscrubbed** — raw `throwable.message` and unscrubbed journey JSON attached to every crash. | `JourneyBreadcrumb.kt:121`, `ErrorInstrumentation.kt:210` |
| 🟠 High | RN (both) | **`url.full` with full query string captured by default, zero TS-side redaction** — `?token=`/`?api_key=`/OAuth codes ship to the backend. | `src/xhr.ts:73`, `src/fetch.ts:57` |
| 🟡 Med | iPhone + RN | **No endpoint scheme validation / TLS enforcement** — `http://` accepted; on RN any JS context (or OTA bundle) can call `start()` to redirect the telemetry stream + Bearer token to an attacker host. | `OTLPExporterFactory.swift:154`, `Dash0MobileBridgeDispatcher.swift:22` |
| 🟡 Med | Operational | **Live Dash0 ingest token in `examples/demo-app/.env`** (+ a stray `.bak`). Gitignored and **not** in git history, but an active credential in plaintext on disk — **rotate it**. | `examples/demo-app/.env:2` |

**Security positives:** clean supply chain (zero RN runtime deps, no install scripts, SHA-pinned Swift deps, current OTel/OkHttp); no secrets committed to git; sensitive-header denylist enforced even against user allowlists; default query-string stripping in native scrubbers; RN bridge has a fixed typed dispatch surface with no selector injection.

---

## Cross-Platform Resilience & Operability Gaps (SecOps focus)

| Gap | State | Why SecOps cares |
|---|---|---|
| **No remote kill switch** | None on any platform. Config polling only tunes flush policies (`flush_window`/`flush_buffer`) — there is no `sdk_enabled`/`global_sample_rate` over the wire. iOS poller is **off by default**. | If the SDK misbehaves in production, the only lever is an app redeploy. Likely adoption blocker. |
| **iOS dropped from CI** | The macOS CI job was removed (cost); iOS is also the thinnest-tested platform (~139 test fns vs Android's ~980). | The least-tested platform is also untested in CI — regression risk. |
| **No SDK overhead self-telemetry** | Android emits `buffer.ram.*`/`buffer.disk.*` gauges; iOS/RN do not. No platform emits SDK CPU%, memory footprint, or export latency. | Customers can't directly answer "is the SDK costing me?" from telemetry. |
| **API is `@Incubating` / alpha** | Public entry points marked `@Incubating`; versions `0.1.0-alpha`/`0.1.1-alpha` with inconsistent version metadata across the repo. | No SemVer stability contract yet — APIs may change without notice. |
| **Cross-platform divergence** | Screenshot default-on (iOS) vs off (Android); error rate-limiter Android-only; RAM byte cap iOS-only; RN feature gaps. | A customer gets *different reliability and privacy behavior per OS* — itself a support/trust risk. |

---

## Consolidated Must-Fix Backlog (priority order)

### P0 — Blocks production (Critical)
1. **iOS native:** Fix `SecureField`/secure-text redaction in screenshots (or redact all text by default). `ScreenshotInstrumentation.swift:176`
2. **Android native:** Remove the auth-token Logcat leak. `MobileLoggerProvider.kt:118` (one-line)
3. **RN iOS:** Add `parentSpanId` to `OTelMobileCallSink.startSpan` so it compiles for integrators; bring the file under CI. `OTelMobileCallSink.swift:77`
4. **RN (both):** Wrap network-interceptor telemetry in try/catch so the host call always executes. `xhr.ts:62`, `fetch.ts:53`
5. **Android native:** Isolate the touch-dispatch chain (try/catch per listener) so a tap can never crash the host. `WindowEventHub.kt:51`
6. **iOS native:** Hop to main thread before any UIKit access in `capture(trigger:)` on the error/policy paths. `OTelMobile.swift:678-693`

### P1 — Required before fleet-wide GA (High)
7. **iOS:** Default screenshot + wireframe to OFF and implement the `shouldCapture` consent gate the privacy doc promises; reconcile doc ↔ code.
8. **iOS:** Apply `NSFileProtection` + backup-exclusion to all at-rest stores; scrub data before it hits disk.
9. **Android:** Make SDK init fault-isolated (wrap each `inst.install()` + the `initialize` body) so a telemetry failure never blocks `Application.onCreate`.
10. **Android:** Scrub breadcrumb/journey data before attaching to crashes; scrub captured network bodies.
11. **RN:** Strip query strings from `url.full` + add a redaction/truncation hook for error messages/stacktraces.
12. **RN iOS:** Bound `liveSpans` (LRU + age-out) to stop the unbounded memory leak.
13. **Android:** Add RAM byte + per-event caps to match iOS and satisfy `SDK_SAFETY.md` #3.
14. **Operational:** Rotate the leaked dev ingest token and delete the `.env.bak`.
15. **Platform:** Add a real remote kill switch (`sdk_enabled` / sampling override) honored by all platforms; enable iOS config polling by default.
16. **Process:** Restore iOS CI (nightly/gated macOS job) and grow iOS buffer/crash/concurrency tests toward Android parity.

### P2 — Hardening (Medium/Low)
17. Enforce HTTPS endpoints + freeze config after `start()` (anti-redirect). 18. Per-payload fault isolation in Android `emitBatch`. 19. Double-install guard on RN global patchers. 20. Close the Android gauge-callback leak. 21. Compose-tree screenshot redaction (Android). 22. Skip password-field char-count/is-set (Android). 23. Move iOS screenshot post-processing off the main thread. 24. Clamp instead of `precondition` in `OfflineBudgetConfig`. 25. Emit buffer self-telemetry on iOS/RN. 26. Reconcile stale docs + version metadata.

---

## SecOps / SRE Adoption Checklist

Before approving fleet-wide deployment, require:

- [ ] All P0 items resolved and independently verified.
- [ ] Screenshot/wireframe **default-off** with documented, auditable consent gating; written data-capture matrix per platform.
- [ ] A **remote kill switch** demonstrated end-to-end (push config → SDK throttles/disables without app release).
- [ ] iOS back in CI with a passing reliability suite; published test-coverage numbers per platform.
- [ ] `NSFileProtection` on all iOS at-rest data; documented retention (TTL) and on-device encryption posture.
- [ ] HTTPS-only export enforced; documented TLS/cert-pinning options.
- [ ] SDK overhead self-telemetry (buffer pressure, export latency, drop counts) available on all platforms for production monitoring.
- [ ] A documented SemVer stability contract; `@Incubating` surfaces clearly labeled or stabilized.
- [ ] A rollback/runbook for SDK-induced incidents (how to disable in-flight, expected blast radius).

---

## Overall Conclusion

The team did a **good job on the hard parts** — the safety philosophy, durable buffering, crash-signal safety, retry discipline, and (on Android) deep testing are genuinely above the bar for mobile telemetry SDKs. The remaining work is concentrated and tractable: a handful of fault-isolation wraps, one compile fix, one thread hop, one buffer cap, the redaction/consent defaults, and the operational levers (kill switch, iOS CI). This is **a strong pre-1.0 SDK with a sound blueprint, not yet a hardened, sign-offable product across all four platforms.** Close the P0/P1 backlog and the "a customer can trust this won't break or leak anything" claim becomes well-founded — Android first, then iOS, with the React Native iOS sink unblocked by the compile fix.
