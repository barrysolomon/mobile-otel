# Design: Screenshot & Wireframe Instrumentation — Privacy-First

**Status:** Design review
**Owner:** iOS SDK (mobile-observability)
**Last updated:** 2026-04-17

## Goal

Offer two opt-in iOS instrumentation modules that capture what the user is seeing, so the observability consumer can correlate "what broke" with "what it looked like on screen":

- **`ScreenshotInstrumentation`** — rendered pixels, compressed, emitted as an attribute on a span or log.
- **`WireframeInstrumentation`** — structured view-hierarchy JSON (no pixels), same emission.

Android already ships both. On iOS they stay deferred until we have a privacy model that can ship to customers in regulated industries (health, finance, mobile industrial) without a legal blocker.

## Non-goals

- Exact parity with Android's on-the-wire format. JSON shape may diverge to reflect SwiftUI idioms.
- Video capture. Single-frame only.
- Automatic upload to a third-party service. Data rides the customer's existing OTLP pipeline.

## Threat model

Customers of Dash0 Mobile Observability ship apps that handle:

- **User PII** — names, emails, addresses, phone numbers, dates of birth, government IDs.
- **Payment details** — card numbers, CVVs, bank account numbers, crypto wallets.
- **Health records** — HIPAA-scoped data in US healthcare apps.
- **Corporate credentials** — passwords, tokens, session cookies displayed in admin/dev panels.
- **Confidential business data** — sales pipelines, inventory levels, customer lists.

Any of the above can end up in a screenshot or a wireframe view hierarchy (text labels, field values). The SDK must make it architecturally difficult to accidentally exfiltrate that data, and architecturally possible for a compliance team to say "we've verified the SDK never captures unredacted text".

## Design principles

1. **Opt-in, not opt-out.** Neither module is part of `AutoCaptureOptions.default`. Customers explicitly include them in their `MobileConfig`.
2. **Redact at capture time, not at export time.** The moment the pixel buffer or JSON tree is built, text is masked. Unredacted data never exists in memory long enough to be reachable via a memory-dump exploit.
3. **Redact at the leaf node deterministically.** Sensitive regions are masked from a reliable signal — the OS-level `isSecureTextEntry` flag on UIKit fields, plus explicit tagging (`Dash0.redact(_:)` / `.dash0Redacted()`) — not a class-name guess. See **Shipped API** below; this principle replaces the earlier opt-out `.dash0PrivacySafe()` proposal with an opt-IN masking model, which is the safer default for regulated customers.
4. **Attribute size is bounded.** The capture is capped to a payload size that fits in an OTel log attribute without exploding span cost. A screenshot that would exceed the cap is dropped, not truncated mid-image.
5. **Consent is a separate, additional runtime gate.** `ScreenshotConfig`/`WireframeConfig` each take an optional `shouldCapture: (@Sendable (CaptureContext) -> Bool)?` closure the customer wires to their consent platform. It is consulted synchronously on the main thread immediately before each capture and is layered on top of the default-OFF `enabled` flag — it does not replace it. See **Shipped API**.
6. **Capture is rate-limited.** Per-minute cap + cooldown after an event that triggered capture.

## Shipped API (authoritative)

> This section reconciles the doc with the code as actually shipped on
> `feat/ios-capture-consent`. Where it conflicts with the **proposed API**
> sketches further below, this section wins; those sketches are retained as
> historical design context. The two notable, deliberate divergences from the
> original proposal are called out inline.

### Consent gate — `CaptureContext`

Both `ScreenshotConfig` and `WireframeConfig` carry:

```swift
public var shouldCapture: CaptureConsentGate?   // (@Sendable (CaptureContext) -> Bool)?
public func withConsentGate(_ gate: @escaping CaptureConsentGate) -> Self
```

`CaptureContext` lives in `OTelMobileCore` and is handed to the gate:

```swift
public struct CaptureContext: Sendable, Equatable {
    public let trigger: CaptureTrigger   // .error | .policy(name:) | .screenView | .tap | .manual | .other(String)
    public let kind: CaptureKind         // .screenshot | .wireframe
    public let screenName: String?
    public var policyName: String? { ... }   // non-nil when trigger == .policy
}
```

**Contract.** The gate is consulted **synchronously on the main thread,
immediately before each capture**, after the `enabled` and rate-limit checks
and before any view-tree walk or pixel render. Returning `false` skips the
capture entirely — no render, no work, no log. When `shouldCapture == nil`,
capture follows the `enabled` flag alone (which is already default-OFF, i.e.
capture is explicit opt-in). The gate is an **additional** runtime gate, never
a replacement for `enabled`. Keep the closure cheap and non-blocking; it runs
on the main thread in the capture hot path.

> **Divergence #1 (vs. proposal principle 5):** the proposal sketched
> `shouldCapture: () -> Bool` defaulting to `{ false }` and "lean no" on a
> context object. The Open Question on a context object resolved **yes** — the
> gate takes a `CaptureContext` so customers can decide by trigger/kind/screen
> without rebuilding their consent integration. The default is `nil` (not
> `{ false }`) because the separate, already-default-OFF `enabled` flag is the
> opt-in switch; making the gate also default to "deny" would be a confusing
> double-negative. Net behaviour is unchanged: nothing captures unless the
> customer both enables the module **and** (if a gate is set) the gate allows.

### Deterministic redaction

Masking decisions are centralized in `OTelMobileCore` so the screenshot walk
and the wireframe walk share one source of truth:

```swift
public enum Dash0RedactionPolicy {
    public static func shouldRedact(_ view: UIView, redactAllText: Bool) -> Bool
}
```

A view is masked when **any** of the following hold, in priority order:

1. **Explicit tag.** The view was marked via `Dash0.redact(_ view: UIView)` /
   `view.dash0MarkSensitive()` (an associated-object flag), or — for SwiftUI —
   the `.dash0Redacted()` `ViewModifier` was applied (see below).
2. **OS-level secure entry.** `UITextField`/`UITextView` with
   `isSecureTextEntry == true`. This is the reliable replacement for the old
   "mask every text field" and the SwiftUI class-name guess.
3. **Redact-all-text mode.** When `ScreenshotConfig.redactAllText == true`,
   every `UITextField`/`UITextView`/`UILabel` is masked (maximally
   conservative). Default `false`.
4. **Conservative class-name fallback (last resort, OFF by default).**
   `Dash0.conservativeClassNameFallbackEnabled` re-enables a narrow,
   case-insensitive class-name token match (`securefield`, `securetext`). This
   is the demoted form of the old heuristic; it can over-mask and break across
   OS versions, so it is opt-in only and documented as a fallback.

`ScreenshotConfig` exposes the redaction switches as:

```swift
public var redactTextFields: Bool   // default true — run the redaction walk at all
public var redactAllText: Bool      // default false — also mask non-secure text
```

### SwiftUI redaction — why it is robust

```swift
SecureField("Password", text: $pw).dash0Redacted()
```

`.dash0Redacted()` (a public `ViewModifier`) installs a transparent,
non-interactive backing `UIView` (`Dash0RedactionBackingView`, via
`UIViewRepresentable`) as a `.background` of the modified view. That backing
view carries the `dash0MarkSensitive()` associated-object flag and tracks the
SwiftUI region's bounds. The capture walk finds it and masks its frame.

This is robust **because the signal is an associated-object flag the SDK set
itself, not a SwiftUI private class name**. The old hotfix matched strings like
`_UITextLayoutCanvasView` / SwiftUI's private secure-field host — fragile
across OS releases and easy to silently regress. The tag survives OS updates,
needs no introspection of Apple-private types, and is unit-testable (the
backing view is built by a single `Dash0RedactionBackingView.makeTagged()`
factory the tests assert against).

> **Divergence #2 (vs. proposal principle 3):** the proposal used
> `.dash0PrivacySafe()` — an **opt-out** where everything masks unless marked
> safe. Shipped is `.dash0Redacted()` — an **opt-in** mask applied to the
> specific sensitive view. Opt-in masking was chosen because mask-everything
> produces unreadable screenshots/wireframes that defeat the debugging purpose,
> while secure fields are already auto-masked deterministically; customers tag
> the residual sensitive views (card numbers, SSNs) explicitly.

### Wireframe redaction

In the wireframe tree walk, a redacted node:
- carries `"redacted": true`,
- drops **all** text-bearing fields (`id`, `hint`, `label`) — enforced in the
  `WireframeNode` initializer, so a redacted node cannot carry text even if the
  walk passed some, and
- is **not descended into** — its sensitive subtree is never serialized.

Layout (`type`, `bounds`) is preserved so the wireframe is still useful.

## `ScreenshotInstrumentation` — proposed API (historical; superseded by Shipped API)

```swift
public struct ScreenshotConfig: Sendable {
    /// Maximum longest-side dimension in points. Default 200; iPhone native
    /// widths are 390-430 pts so 200 is a ~46% scale — enough to see layout,
    /// not enough to read field text.
    public var maxDimensionPoints: Int = 200

    /// JPEG compression quality 0.0-1.0. Default 0.5.
    public var jpegQuality: Double = 0.5

    /// Drop the capture entirely if the encoded payload exceeds this many
    /// bytes. Default 64 KB — fits comfortably in one OTLP log record.
    public var maxPayloadBytes: Int = 64 * 1024

    /// Text redaction mode.
    /// - `.maskAll`: every text view is drawn as a solid block. Default.
    /// - `.maskExceptPrivacySafe`: text views marked `.dash0PrivacySafe()` render; everything else masks.
    /// - `.none`: DEBUG ONLY. Panics at init if used in a Release build.
    public var textRedaction: TextRedactionMode = .maskAll

    /// Rate cap. Default: at most one screenshot every 30 seconds AND at
    /// most 20 per session.
    public var rateLimit: RateLimit = .init(windowSeconds: 30, maxPerSession: 20)

    /// Customer-owned consent gate. Called on every capture attempt; if it
    /// returns false, no capture. Defaults to a function that always
    /// returns false so bootstrapping the module doesn't silently start
    /// capturing.
    public var shouldCapture: @Sendable () -> Bool = { false }
}

public enum TextRedactionMode: Sendable {
    case maskAll
    case maskExceptPrivacySafe
    case none
}

public final class ScreenshotInstrumentation: @unchecked Sendable {
    public static let shared = ScreenshotInstrumentation()
    public func install(logger: Logger, config: ScreenshotConfig = ScreenshotConfig())
    public func capture(context: String, attributes: [String: AttributeValue] = [:])
    public func uninstall()
}
```

### Capture pipeline (redaction path)

1. Caller invokes `capture(context:attributes:)` — typically from an error handler or a "low-confidence span" closer.
2. Check `shouldCapture()`. If false, return immediately.
3. Check rate limit. If over, emit `screenshot.skipped` log with `reason=rate_limited` and return.
4. Walk the SwiftUI/UIKit hierarchy of the key window:
   - For every `UITextField`, `UITextView`, `UILabel`, `SwiftUI Text` view NOT marked `.dash0PrivacySafe()`, render a solid rectangle of the view's frame in the view's foreground color onto an intermediate image layer.
   - For images not marked safe, render a solid block too.
5. Render the window via `UIGraphicsImageRenderer` at `maxDimensionPoints`.
6. JPEG-encode at `jpegQuality`.
7. If encoded size > `maxPayloadBytes`, emit `screenshot.dropped` log with `reason=oversize,bytes=N`, return.
8. Base64-encode, emit as `ui.screenshot` log with the bytes on attribute `screenshot.jpeg_base64` and a `screenshot.dimensions_points` attribute.

### What `.dash0PrivacySafe()` does

A SwiftUI ViewModifier that tags the view so the redaction walker leaves it alone. Applied narrowly — a product image on an e-commerce screen, an app-logo view on a settings screen. Never applied to entire screens.

## `WireframeInstrumentation` — proposed API (historical; superseded by Shipped API)

```swift
public struct WireframeConfig: Sendable {
    public var maxDepth: Int = 10         // how deep the view tree is captured
    public var maxNodes: Int = 150
    public var maxPayloadBytes: Int = 8 * 1024
    public var textRedaction: TextRedactionMode = .maskAll
    public var rateLimit: RateLimit = .init(windowSeconds: 5, maxPerSession: 100)
    public var shouldCapture: @Sendable () -> Bool = { false }
}
```

Emits `ui.wireframe` log. The JSON payload is a tree: each node `{ "type": "VStack", "bounds": [x,y,w,h], "children": [...] }`. Text nodes carry `{ "type": "Text", "redacted": true }` when redaction masks are on. No character counts, no first-letter, no character-class summaries — redacted text is completely opaque.

## What doesn't ship first

- **Automatic capture on span end or error.** The module ships with an explicit `capture()` method; wiring it to error events is customer code. We can add `captureOnError: Bool` in a later version once field deployments show the ergonomics are right.
- **Per-attribute allowlist / denylist.** Out of scope for the first ship — too easy to get wrong.
- **Server-side redaction.** All redaction is on device. Server-side is a second defence, not the primary.
- **Android-identical JSON schema.** SwiftUI's view tree has no clean Android analog. Iterate the schema in a follow-up.

## Test surface (before merging this design)

- Unit test: `TextRedactionMode.maskAll` on a view with known text content produces a JPEG with 0 tokens detectable by an OCR library (the test OCRs the captured bytes and asserts no text).
- Unit test: `shouldCapture: { false }` gate never invokes the encoder (verify via a spy).
- Unit test: `maxPayloadBytes` exceedance produces a `screenshot.dropped` log, not a truncated image.
- Unit test: rate limiter emits `screenshot.skipped` on the (windowSeconds+1)th call within a window.
- Unit test: `.none` redaction mode in a Release build traps.
- Integration test: feed a SwiftUI view into the redaction walker and assert the output image has no text pixels that match the input text (simple pixel diff against a "fully masked" reference).
- Manual test: run the AstronomyShop with screenshots enabled (consent forced to true for the test), verify each `ui.screenshot` log in Dash0 renders as an unreadable redacted thumbnail.

## Implementation sequencing (when approved)

1. **Foundation** — `TextRedactionMode`, `RateLimit`, `.dash0PrivacySafe()` ViewModifier, shared `RedactionWalker` that produces an opaque intermediate layer given a SwiftUI tree. No OTel coupling yet.
2. **`ScreenshotInstrumentation`** — the smaller scope; ship without wireframe, field-test redaction quality.
3. **`WireframeInstrumentation`** — second. Reuses `RedactionWalker` to decide which text nodes emit `redacted:true`.
4. **Demo integration** — opt-in in `AstronomyShopApp` with an in-app toggle for the demo. Validation flow extends to query Dash0 for `ui.screenshot`/`ui.wireframe` logs.

## Open questions

- ~~Should `shouldCapture()` accept a context object (screen name, error class) so customers can redact by context without rebuilding their consent gate? **Lean no** for first ship.~~ **Resolved: yes.** Shipped as `shouldCapture: (@Sendable (CaptureContext) -> Bool)?` where `CaptureContext` carries `trigger`, `kind`, and `screenName`. See **Shipped API**.
- Do we need a way for customers to inspect what was captured before it shipped? **Consider yes** — a debug build delegate hook that gets the encoded bytes + redaction stats. Defer to post-ship.
- PLCrashReporter integration — does crash recovery want to snapshot one last screenshot right before crash? **No.** The crash marker path is signal-handler-scoped, which forbids almost all APIs including anything that touches UIKit/SwiftUI. Screenshots live in the regular runtime path only.
