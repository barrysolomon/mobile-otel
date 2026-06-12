/**
 * @dash0/mobile-react-native — public API
 *
 * Thin JS facade. All buffering, policy, OTLP export, crash recovery happen
 * in the native modules. This file only marshals calls onto a NativeBridge.
 *
 * See docs/epics/REACT_NATIVE_EPIC.md.
 */

import { NativeBridge } from './bridge/NativeBridge';
import { installFetchInstrumentation } from './instrumentation/fetch';
import { installXhrInstrumentation } from './instrumentation/xhr';
import { installErrorInstrumentation } from './instrumentation/errors';
import { installUnhandledRejectionInstrumentation } from './instrumentation/unhandledRejection';
import type {
  Attributes,
  BridgePayload,
  NativeDash0MobileModule,
  SamplingConfig,
  SeverityNumber,
  SpanKind,
  SpanStartPayload,
  StartConfig,
} from './bridge/types';

export type { Attributes, SamplingConfig, SeverityNumber, StartConfig } from './bridge/types';
export { installReactNavigationInstrumentation } from './instrumentation/navigation';
export { withTapTelemetry } from './instrumentation/touch';
export { otel } from './otel-compat';

export interface SpanHandle {
  setAttribute(key: string, value: string | number | boolean): void;
  setStatus(status: 'OK' | 'ERROR', message?: string): void;
  end(): void;
}

interface ActiveSpan {
  payload: SpanStartPayload;
  attrs: Attributes;
  status: 'UNSET' | 'OK' | 'ERROR';
  statusMessage?: string;
  ended: boolean;
}

let injectedNative: NativeDash0MobileModule | null = null;
let bridge: NativeBridge | null = null;
let started = false;
let autoInstrUninstallers: Array<() => void> = [];

// Ambient parent-span stack. Each `startSpan` pushes the new spanId; each
// `.end()` pops. Children emitted while an outer span is live pick up the
// top of the stack as their `parentSpanId`, so Dash0 renders proper
// waterfalls without callers threading parent handles manually.
//
// Limitation: a single global stack is only correct for strictly-nested
// (LIFO) span work — sufficient for `Dash0Mobile.span('x', async () => { ... })`
// and sequential `startSpan/.end()` pairs. Concurrent async span trees would
// need AsyncLocalStorage / Hermes async-hooks; RN doesn't expose those
// cleanly, so we accept the limitation and document it on the public API.
const spanStack: string[] = [];

/// Anchor pair used by `nowUnixNano` to combine an absolute-epoch
/// base (from `Date.now()`) with a sub-ms monotonic delta (from
/// `performance.now()`). Initialized lazily on the first timestamp
/// request so we don't pay the cost for consumers who never emit.
///
/// Why the anchor pattern: `performance.now()` gives sub-ms precision
/// but returns time-since-an-arbitrary-origin, not wall-clock epoch.
/// `Date.now()` gives wall-clock but only ms resolution. Capturing
/// both once, then deriving each timestamp as `epochAnchor + perfDelta`
/// yields sub-ms wall-clock nanoseconds without per-call drift.
let _epochAnchorMs: number | null = null;
let _perfAnchorMs: number | null = null;

function perfNow(): number {
  // RN 0.85 new-arch ships `global.performance.now` via JSI. Older
  // runtimes (Hermes < 0.12, some test harnesses) may not. Fall back
  // gracefully — callers still get correct ms-resolution nanoseconds
  // in that case, just not the sub-ms fidelity.
  const g = globalThis as { performance?: { now?: () => number } };
  if (typeof g.performance?.now === 'function') {
    return g.performance.now();
  }
  return Date.now();
}

function nowUnixNano(): string {
  // Initialize anchor lazily. The offset between the two reads is at
  // most one JS tick — negligible for observability purposes.
  if (_epochAnchorMs === null || _perfAnchorMs === null) {
    _epochAnchorMs = Date.now();
    _perfAnchorMs = perfNow();
  }

  const elapsedMs = perfNow() - _perfAnchorMs;
  // Sub-ms fraction; convert to nanoseconds as an integer.
  const extraNanos = Math.floor(elapsedMs * 1_000_000);

  // BigInt is required for correctness. Unix nanoseconds in 2026
  // are ~1.77e18, well past JS `Number.MAX_SAFE_INTEGER` (2^53 ≈
  // 9.01e15). The previous `Date.now() * 1_000_000` implementation
  // lost precision on every call (rounding to ~128-nanosecond bands),
  // which collapsed nested child spans' start+end timestamps to
  // identical values and surfaced as `duration=0ms` in Dash0.
  const totalNanos =
    BigInt(_epochAnchorMs) * 1_000_000n + BigInt(extraNanos);
  return totalNanos.toString();
}

/// Test-only: reset the anchors so each test case starts fresh.
/// Not exported from the public entry point — accessed in Jest via
/// the internal require path.
export function __resetTimestampAnchorForTests__(): void {
  _epochAnchorMs = null;
  _perfAnchorMs = null;
}

function randomSpanId(): string {
  let id = '';
  for (let i = 0; i < 16; i++) {
    id += Math.floor(Math.random() * 16).toString(16);
  }
  return id;
}

function resolveNative(): NativeDash0MobileModule | null {
  if (injectedNative) return injectedNative;
  // Production path: lazily require react-native to avoid pulling it into
  // tests that stub at the unit level. If RN isn't present we return null
  // and pre-start calls become no-ops.
  try {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const rn = require('react-native');
    return (rn?.NativeModules?.Dash0Mobile as NativeDash0MobileModule) ?? null;
  } catch {
    return null;
  }
}

// Version of this RN bridge package. Sent as `telemetry.distro.version` so
// Dash0 can distinguish RN-originated telemetry from direct native SDK
// callers and correlate issues to a specific bridge release. Keep this in
// sync with package.json on each release.
const DISTRO_NAME = 'dash0-react-native';
const DISTRO_VERSION = '0.3.0-alpha';

function resolveReactNativeVersion(): string | undefined {
  try {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const rn = require('react-native/package.json');
    return typeof rn?.version === 'string' ? rn.version : undefined;
  } catch {
    return undefined;
  }
}

export const Dash0Mobile = {
  async start(config: StartConfig): Promise<void> {
    const native = resolveNative();
    if (!native) {
      // No native module wired. Stay quiet — caller is likely in a non-RN
      // environment (Jest, SSR) and shouldn't crash.
      started = false;
      return;
    }
    bridge = new NativeBridge(native);

    // Inject RN distribution attributes as OTel-spec resource attributes.
    // Caller-provided extras win (lets apps override for testing/tagging).
    const rnVersion = resolveReactNativeVersion();
    // Translate the `autoCapture` flags into a native-capability token list the
    // bridge (iOS/Android) can use to build `AutoCaptureOptions`. Native
    // default is OFF for everything — callers must explicitly set a flag to
    // `true` to enable the native suite on that capability. This avoids the
    // iOS URLProtocol / NSException / signal-handler swizzles by default,
    // which collide with RN's new-arch JS event loop.
    const nativeAutoCapture = buildNativeAutoCaptureTokens(config.autoCapture);
    // RN sampling default: always_on. RN manual spans are root spans with
    // arbitrary names, so the native SDKs' dynamic(0.1) default would
    // silently drop ~90% of a user's first span (Loper finding #4). Sampling
    // for RN architectures belongs in the collector — callers can still opt
    // into on-device sampling by passing an explicit `sampling`. See the
    // SamplingConfig doc comment in bridge/types.ts.
    const sampling: SamplingConfig = config.sampling ?? { strategy: 'always_on' };
    const mergedConfig: StartConfig & { nativeAutoCapture: string[] } = {
      ...config,
      sampling,
      extraResourceAttributes: {
        'telemetry.distro.name': DISTRO_NAME,
        'telemetry.distro.version': DISTRO_VERSION,
        ...(rnVersion ? { 'app.framework': 'react-native', 'app.framework.version': rnVersion } : {}),
        ...(config.extraResourceAttributes ?? {}),
      },
      nativeAutoCapture,
    };

    await native.start(mergedConfig);
    started = true;

    const auto = config.autoCapture ?? {};
    if (auto.network !== false) {
      const collectorHost = hostFromEndpoint(config.endpoint);
      const ignoredHosts = collectorHost ? [collectorHost] : [];
      // On React Native, `fetch` is implemented on top of XHR, so every
      // fetch() call also fires the XHR instrumentation — installing both
      // produces two spans per request. XHR is the authoritative layer
      // because it also captures direct XHR callers (axios, Apollo HTTP
      // link, legacy code) that don't go through fetch. In non-RN JS
      // environments (future web/SSR use) fetch is native and independent
      // of XHR, so both shims are needed.
      if (!isReactNative()) {
        autoInstrUninstallers.push(installFetchInstrumentation({ ignoredHosts }));
      }
      // Android network capture is owned by the native OkHttp interceptor
      // (OTelNetworkInterceptor), installed pre-JS in Dash0MobilePackage. The
      // native layer is the only one that sees traffic under Expo SDK 52+ —
      // expo/fetch routes through OkHttp directly and never touches the JS
      // `fetch`/`XMLHttpRequest` globals these shims wrap, so the JS XHR shim
      // sees zero traffic there. The native interceptor also injects a
      // W3C `traceparent` built from the real native span context, which the
      // JS shim cannot do. Installing the JS XHR shim on Android on top of the
      // native interceptor would (for non-Expo apps) double-count every
      // request. So: gate the JS XHR shim OFF on Android. iOS keeps the JS XHR
      // shim — its native URLProtocol is opt-in (off by default for RN), and
      // RN iOS fetch is XHR-backed, so XHR remains the authoritative JS layer
      // there.
      if (!isAndroid()) {
        autoInstrUninstallers.push(installXhrInstrumentation({ ignoredHosts }));
      }
    }
    if (auto.errors !== false) {
      autoInstrUninstallers.push(installErrorInstrumentation());
      autoInstrUninstallers.push(installUnhandledRejectionInstrumentation());
    }
    // Lifecycle (`app.foreground` / `app.background` / `app.start`) is now
    // emitted by native instrumentation on both platforms — Android via
    // ProcessLifecycleOwner, iOS via NotificationCenter with applicationState
    // late-init synthesis. The previous JS-side AppState shim was deleted
    // because RN 0.85 new-arch made it unreliable (TurboModule init race).
  },

  log(name: string, attributes: Attributes = {}, severity: SeverityNumber = 9): void {
    if (!started || !bridge) return;
    const payload: BridgePayload = {
      kind: 'log',
      name,
      severity,
      attributes,
      timeUnixNano: nowUnixNano(),
    };
    // FATAL-severity logs bypass the 50ms debounce via `emitSync`, which
    // calls `native.emitBatch` without any `await`. The payload crosses
    // the RN bridge in the current stack frame — critical on crash paths
    // because any microtask boundary (including the `await` inside
    // `flush()`) loses the race against the handler's continuation into
    // RN's fatal reporter. Once the payload is on the native side, the
    // iOS SDK's willTerminate auto-forceFlush (commit 1a69c7e) persists
    // it to disk and attempts OTLP export before process exit.
    if (severity >= 21) {
      bridge.emitSync(payload);
    } else {
      bridge.emit(payload);
    }
  },

  startSpan(name: string, attributes: Attributes = {}, spanKind: SpanKind = 'INTERNAL'): SpanHandle {
    const spanId = randomSpanId();
    const parentSpanId = spanStack.length > 0 ? spanStack[spanStack.length - 1] : undefined;
    const startPayload: SpanStartPayload = {
      kind: 'spanStart',
      spanId,
      parentSpanId,
      name,
      spanKind,
      attributes: { ...attributes },
      startTimeUnixNano: nowUnixNano(),
    };
    const active: ActiveSpan = {
      payload: startPayload,
      attrs: {},
      status: 'UNSET',
      ended: false,
    };
    spanStack.push(spanId);

    if (started && bridge) {
      bridge.emit(startPayload);
    }

    return {
      setAttribute(key, value) {
        active.attrs[key] = value;
      },
      setStatus(status, message) {
        active.status = status;
        active.statusMessage = message;
      },
      end() {
        if (active.ended) return;
        active.ended = true;
        // Pop this spanId off the stack. Tolerate out-of-order ends by
        // scanning from the top — the common case is LIFO, but mis-ordered
        // ends (e.g. a long-lived outer span that ends after an inner
        // one that was itself popped correctly) shouldn't corrupt the stack.
        const idx = spanStack.lastIndexOf(spanId);
        if (idx >= 0) spanStack.splice(idx, 1);
        if (!started || !bridge) return;
        bridge.emit({
          kind: 'spanEnd',
          spanId,
          status: active.status === 'UNSET' ? 'OK' : active.status,
          statusMessage: active.statusMessage,
          attributes: active.attrs,
          endTimeUnixNano: nowUnixNano(),
        });
      },
    };
  },

  async span<T>(
    name: string,
    fn: (handle: SpanHandle) => Promise<T> | T,
    attributes?: Attributes,
  ): Promise<T> {
    const handle = this.startSpan(name, attributes);
    try {
      const result = await fn(handle);
      handle.setStatus('OK');
      return result;
    } catch (err) {
      handle.setStatus('ERROR', err instanceof Error ? err.message : String(err));
      throw err;
    } finally {
      handle.end();
    }
  },

  recordMetric(
    name: string,
    value: number,
    instrumentType: 'counter' | 'histogram' | 'gauge' = 'counter',
    attributes: Attributes = {},
  ): void {
    if (!started || !bridge) return;
    bridge.emit({
      kind: 'metric',
      name,
      instrumentType,
      value,
      attributes,
      timeUnixNano: nowUnixNano(),
    });
  },

  async flushWindow(minutes: number): Promise<void> {
    if (!started || !bridge) return;
    await bridge.flush();
    const native = resolveNative();
    if (native) await native.flushWindow(minutes);
  },

  // ─── User Journey API (UJ-020) ──────────────────────────────────────────
  // Thin passthrough to native `OTelMobile.startJourney/endJourney`. The
  // native side creates the journey span and triggers screenshot + wireframe
  // captures at start/end boundaries.

  async startJourney(name: string): Promise<string | null> {
    if (!started) return null;
    const native = resolveNative();
    if (!native) return null;
    return native.startJourney(name);
  },

  async endJourney(journeyId: string): Promise<void> {
    if (!started) return;
    const native = resolveNative();
    if (!native) return;
    await native.endJourney(journeyId);
  },

  async captureScreenshot(trigger: string = 'manual'): Promise<void> {
    if (!started) return;
    const native = resolveNative();
    if (!native) return;
    await native.captureScreenshot(trigger);
  },

  async captureWireframe(trigger: string = 'manual'): Promise<void> {
    if (!started) return;
    const native = resolveNative();
    if (!native) return;
    await native.captureWireframe(trigger);
  },

  async shutdown(): Promise<void> {
    for (const uninstall of autoInstrUninstallers) {
      try {
        uninstall();
      } catch {
        // Swallow — shutdown should never throw.
      }
    }
    autoInstrUninstallers = [];
    if (!bridge) return;
    await bridge.flush();
    const native = resolveNative();
    if (native) await native.shutdown();
    started = false;
    bridge = null;
  },
};

const ENDPOINT_HOST_RE = /^[a-z][a-z0-9+.-]*:\/\/([^/:?#]+)/i;
function hostFromEndpoint(endpoint: string): string | null {
  const match = ENDPOINT_HOST_RE.exec(endpoint);
  return match ? match[1].toLowerCase() : null;
}

// React Native sets `navigator.product === 'ReactNative'` — the standard
// public signal for code that wants to differ by JS environment. This is
// the same check used by Sentry, Datadog, and RN itself internally. Jest
// / Node / SSR won't match, so test and non-RN environments still
// exercise both network shims.
function isReactNative(): boolean {
  const nav = (globalThis as unknown as { navigator?: { product?: string } }).navigator;
  return nav?.product === 'ReactNative';
}

// True only on React Native running on Android. Reads `Platform.OS` from the
// react-native module rather than a global so it's accurate on both old and
// new arch. Used to gate OFF the JS `XMLHttpRequest` shim on Android, where
// the native OkHttp interceptor (OTelNetworkInterceptor) owns network capture
// and traceparent injection. Any failure to resolve `Platform` (Jest, SSR,
// non-RN) returns false so those environments keep the JS shim — exactly the
// behavior the dedup test pins.
function isAndroid(): boolean {
  try {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const rn = require('react-native') as { Platform?: { OS?: string } };
    return rn?.Platform?.OS === 'android';
  } catch {
    return false;
  }
}

// Map JS autoCapture flags onto native-capability tokens the bridge
// translates to AutoCaptureOptions. Defaults are all-OFF: callers must
// explicitly set `true` to enable a native suite.
type AutoCaptureFlag = keyof NonNullable<StartConfig['autoCapture']>;
const NATIVE_AUTO_CAPTURE_FLAGS: ReadonlyArray<[AutoCaptureFlag, string]> = [
  ['network', 'network'],
  ['errors', 'errors'],
  ['tap', 'tap'],
  ['scroll', 'scroll'],
  ['textInput', 'textInput'],
  ['screen', 'screen'],
  ['freeze', 'freeze'],
  ['vitals', 'vitals'],
  ['deviceStats', 'deviceStats'],
  ['screenshot', 'screenshot'],
  ['wireframe', 'wireframe'],
];
function buildNativeAutoCaptureTokens(ac: StartConfig['autoCapture']): string[] {
  if (!ac) return [];
  const out: string[] = [];
  for (const [flag, token] of NATIVE_AUTO_CAPTURE_FLAGS) {
    const value = ac[flag];
    // Object form (screenshot / wireframe per-trigger): treat as enabled
    // unless `enabled` is explicitly `false`. The bridge protocol carries
    // only this on/off bit today — per-trigger fields are forward-compatible
    // and currently must be configured natively. See ScreenshotAutoCapture /
    // WireframeAutoCapture doc comments in bridge/types.ts.
    if (value === true) {
      out.push(token);
    } else if (typeof value === 'object' && value !== null) {
      const enabled = (value as { enabled?: boolean }).enabled;
      if (enabled !== false) out.push(token);
    }
  }
  return out;
}

// ─── test hooks ──────────────────────────────────────────────────────────────
// These are intentionally exported but prefixed __ to signal "not for app use."
// Native-module tests inject a mock here; a CI lint rule should ban callers
// outside __tests__/ from importing them.

export function __setNativeForTesting(native: NativeDash0MobileModule | null): void {
  injectedNative = native;
}

export function __resetForTesting(): void {
  injectedNative = null;
  bridge = null;
  started = false;
  spanStack.length = 0;
}
