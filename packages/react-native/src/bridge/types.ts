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
  autoCapture?: {
    network?: boolean;
    errors?: boolean;
    lifecycle?: boolean;
    appState?: boolean;
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
}
