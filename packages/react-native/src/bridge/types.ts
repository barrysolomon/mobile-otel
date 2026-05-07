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
  enablePolicyPolling?: boolean;
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
    /** Native-only — screenshot capture at journey boundaries + errors. Off by default on RN. */
    screenshot?: boolean;
    /** Native-only — wireframe capture at journey boundaries + screen transitions. Off by default on RN. */
    wireframe?: boolean;
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
