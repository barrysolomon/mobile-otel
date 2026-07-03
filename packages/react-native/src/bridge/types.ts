/**
 * Bridge payload contract between the JS layer and the native modules.
 *
 * This is the stable seam. Any change here is a cross-repo breaking change
 * (JS package + Android module + iOS module must all move together).
 *
 * Values crossing the RN bridge must be JSON-serializable primitives.
 * No Date, no Map, no Symbol, no functions.
 */

export type SeverityNumber =
  | 1  // TRACE
  | 5  // DEBUG
  | 9  // INFO
  | 13 // WARN
  | 17 // ERROR
  | 21; // FATAL

export type SpanKind = 'INTERNAL' | 'CLIENT' | 'SERVER' | 'PRODUCER' | 'CONSUMER';

export type SpanStatus = 'UNSET' | 'OK' | 'ERROR';

export type AttrValue = string | number | boolean | null;
export type Attributes = Record<string, AttrValue>;

/**
 * Per-trigger flags for the native screenshot module. Field names and
 * defaults mirror Android's `ScreenshotConfig` and iOS's `ScreenshotConfig`
 * one-to-one. All fields optional; absent fields fall through to native
 * defaults.
 */
export interface ScreenshotAutoCapture {
  /** Master on/off. Required when passing an object form. Default `true` when present. */
  enabled?: boolean;
  /** Capture on every screen transition. Default `false` (high payload volume). */
  captureOnScreenView?: boolean;
  /** Capture when an uncaught exception occurs. Default `true`. */
  captureOnError?: boolean;
  /** Capture whenever a buffered-export policy fires (crash-recovery, ui-freeze, http-error). Default `true`. */
  captureOnPolicyMatch?: boolean;
}

/**
 * Per-trigger flags for the native wireframe module. Field names and
 * defaults mirror Android's `WireframeConfig` and iOS's `WireframeConfig`
 * one-to-one. All fields optional; absent fields fall through to native
 * defaults.
 */
export interface WireframeAutoCapture {
  /** Master on/off. Required when passing an object form. Default `true` when present. */
  enabled?: boolean;
  /** Capture on every screen transition. Default `true` (primary journey trigger). */
  captureOnScreenView?: boolean;
  /** Capture on every tap. Default `false` — taps rarely change the view hierarchy; dedup absorbs the case anyway. */
  captureOnTap?: boolean;
  /** Capture when an uncaught exception occurs. Default `true`. */
  captureOnError?: boolean;
  /** Capture whenever a buffered-export policy fires. Default `true`. */
  captureOnPolicyMatch?: boolean;
  /** Emit `ui.wireframe.ref` with `mobile.wireframe.id` instead of the full JSON when content matches the last capture. Default `true`. */
  dedupeByContentHash?: boolean;
}

/**
 * Trace sampling strategy passed through the RN bridge into the native SDK's
 * `SamplingConfig`. Field names and semantics mirror Android's
 * `io.opentelemetry.android.mobile.sampling.SamplingConfig` and iOS's
 * `SamplingConfig` factory constructors one-to-one.
 *
 *   - `always_on`  → `SamplingConfig.alwaysOn()`  — sample 100% of spans.
 *   - `always_off` → `SamplingConfig.alwaysOff()` — drop all spans.
 *   - `dynamic`    → `SamplingConfig.dynamic(normalRate, highPriorityRate)`
 *     — `normalRate` baseline with `highPriorityRate` for high-priority
 *     spans (page.* / app.startup).
 *
 * RN DEFAULT: when `sampling` is omitted the bridge defaults to `always_on`
 * (NOT the native SDK's `dynamic(0.1)` default). RN manual spans
 * (`Dash0Mobile.startSpan()`) are ROOT spans with arbitrary names; a 10%
 * baseline silently drops ~90% of a user's very first span, which is a
 * terrible first-run experience and historically read as "spans vanish."
 * Sampling/rate-limiting for RN architectures belongs in the collector, not
 * the on-device SDK. Pass an explicit `sampling` to opt back into on-device
 * sampling. The native-only Android/iOS SDKs keep their `dynamic(0.1)`
 * default — only the RN-bridged default changes.
 */
export interface SamplingConfig {
  strategy: 'always_on' | 'always_off' | 'dynamic';
  /** Baseline rate for `dynamic` (0.0–1.0). Ignored for always_on/always_off. */
  normalRate?: number;
  /** Rate for high-priority spans under `dynamic` (0.0–1.0). Defaults to 1.0 natively. */
  highPriorityRate?: number;
}

export interface StartConfig {
  serviceName: string;
  serviceVersion?: string;
  endpoint: string;
  authToken?: string;
  dataset?: string;
  bufferConfig?: {
    ramEvents?: number;
    diskBytes?: number;
  };
  /**
   * Base URL of the mobile-otel gateway serving `/config?dsl_version=2`.
   * Set this to enable the native RemoteGate kill switch + remote policy
   * config for RN apps: `endpoint` usually points at plain OTLP ingest
   * (e.g. Dash0 ingress), which does not serve policy config. When omitted,
   * the native SDKs poll `endpoint` (their historical behaviour).
   */
  gatewayEndpoint?: string;
  /**
   * Whether the native SDK polls for remote policy config (RemoteGate kill
   * switch + policy DSL updates). Omit to keep the native default (`true`);
   * `false` disables remote config entirely.
   */
  enablePolicyPolling?: boolean;
  /** Remote-config poll interval in seconds. Omit for the native default (300). */
  configPollIntervalSeconds?: number;
  /**
   * Trace sampling strategy. Defaults to `always_on` on RN (see
   * {@link SamplingConfig}) — unlike the native SDKs' `dynamic(0.1)` default.
   * Omit to keep all spans on-device and rate-limit in the collector.
   */
  sampling?: SamplingConfig;
  /**
   * Toggles for JS-side auto-instrumentation (fetch/XHR spans, JS error +
   * unhandled rejection logs). Defaults to all-on. RN bridge uses the same
   * flags to decide which native iOS/Android auto-capture suites to enable
   * via `nativeAutoCapture`, so e.g. `autoCapture: { network: true }`
   * enables the iOS `URLProtocol` swizzle in addition to the JS fetch/XHR
   * wrappers. The default is OFF on the native side for `network`/`errors`
   * — RN host apps typically don't want the native iOS URLProtocol swizzle
   * or NSException handlers because they collide with RN's new-arch JS
   * event loop (touch responder goes dormant).
   *
   * Lifecycle (`app.foreground` / `app.background` / `app.start`) is
   * always-on via native instrumentation (Android ProcessLifecycleOwner,
   * iOS NotificationCenter); there is no JS or per-flag knob for it.
   */
  autoCapture?: {
    network?: boolean;
    errors?: boolean;
    /** Native iOS/Android-only capability — captures UI taps via swizzle/recognizer. Off by default on RN. */
    tap?: boolean;
    /** Native-only — scroll spans. Off by default on RN. */
    scroll?: boolean;
    /** Native-only — text input spans. Off by default on RN. */
    textInput?: boolean;
    /** Native-only — SwiftUI/Fragment screen tracking. Off by default on RN (use react-navigation helper instead). */
    screen?: boolean;
    /** Native-only — main-thread freeze detection. Off by default on RN. */
    freeze?: boolean;
    /** Native-only — app.start + jank + memory gauges. Off by default on RN. */
    vitals?: boolean;
    /** Native-only — periodic device health gauges (battery/thermal/network). Off by default on RN. */
    deviceStats?: boolean;
    /**
     * Native-only — screenshot capture at journey boundaries + errors. Off
     * by default on RN. Accepts either a bare boolean (`true` enables with
     * native defaults) or an options object exposing the per-trigger flags
     * mirrored from Android's `ScreenshotConfig` and iOS's `ScreenshotConfig`.
     *
     * NOTE: the per-trigger fields are forward-compatible. Today's bridge
     * carries only the `enabled` bit through to native. Passing an object
     * is equivalent to passing `true` until the bridge grows per-module
     * options in a follow-up. Set the trigger flags natively (Android: in
     * `MobileConfig.screenshotConfig`, iOS: in `MobileConfig`'s
     * `screenshotConfig` param) until then.
     */
    screenshot?: boolean | ScreenshotAutoCapture;
    /**
     * Native-only — wireframe capture at screen transitions, optional taps,
     * errors, and policy matches. Same shape rules as `screenshot` above.
     */
    wireframe?: boolean | WireframeAutoCapture;
  };
  /**
   * Extra resource attributes merged into the native SDK's resource. Used by
   * the RN bridge to inject `telemetry.distro.name` / `telemetry.distro.version`
   * so Dash0 knows the telemetry came through the React Native distribution
   * rather than direct Kotlin/Swift SDK usage. Caller-overridable for apps
   * that want to add their own build/deployment tags.
   */
  extraResourceAttributes?: Record<string, string>;
}

export interface LogPayload {
  kind: 'log';
  name: string;
  severity: SeverityNumber;
  attributes: Attributes;
  timeUnixNano: string; // string to preserve precision across bridge
}

export interface SpanStartPayload {
  kind: 'spanStart';
  spanId: string;        // JS-generated; native echoes back to correlate
  parentSpanId?: string;
  name: string;
  spanKind: SpanKind;
  attributes: Attributes;
  startTimeUnixNano: string;
}

export interface SpanEndPayload {
  kind: 'spanEnd';
  spanId: string;
  status: SpanStatus;
  statusMessage?: string;
  attributes: Attributes;
  endTimeUnixNano: string;
}

export interface MetricPayload {
  kind: 'metric';
  name: string;
  instrumentType: 'counter' | 'histogram' | 'gauge';
  value: number;
  attributes: Attributes;
  timeUnixNano: string;
}

export type BridgePayload =
  | LogPayload
  | SpanStartPayload
  | SpanEndPayload
  | MetricPayload;

export interface NativeDash0MobileModule {
  start(config: StartConfig): Promise<void>;
  emitBatch(payloads: BridgePayload[]): Promise<void>;
  flushWindow(minutes: number): Promise<void>;
  shutdown(): Promise<void>;
  startJourney(name: string): Promise<string>;
  endJourney(journeyId: string): Promise<void>;
  captureScreenshot(trigger: string): Promise<void>;
  captureWireframe(trigger: string): Promise<void>;
}
