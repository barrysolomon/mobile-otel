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
import { installAppStateInstrumentation } from './instrumentation/appstate';
import type {
  Attributes,
  NativeDash0MobileModule,
  SeverityNumber,
  SpanKind,
  SpanStartPayload,
  StartConfig,
} from './bridge/types';

export type { Attributes, SeverityNumber, StartConfig } from './bridge/types';
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

function nowUnixNano(): string {
  // Date.now() is ms; multiply to nanoseconds. JS numbers lose precision past
  // 2^53, but UNIX nanos in 2026 fit well under that — keep as string for the
  // bridge contract regardless.
  return String(Date.now() * 1_000_000);
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
const DISTRO_VERSION = '0.1.0-alpha';

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
    const mergedConfig: StartConfig = {
      ...config,
      extraResourceAttributes: {
        'telemetry.distro.name': DISTRO_NAME,
        'telemetry.distro.version': DISTRO_VERSION,
        ...(rnVersion ? { 'app.framework': 'react-native', 'app.framework.version': rnVersion } : {}),
        ...(config.extraResourceAttributes ?? {}),
      },
    };

    await native.start(mergedConfig);
    started = true;

    const auto = config.autoCapture ?? {};
    if (auto.network !== false) {
      const collectorHost = hostFromEndpoint(config.endpoint);
      const ignoredHosts = collectorHost ? [collectorHost] : [];
      autoInstrUninstallers.push(installFetchInstrumentation({ ignoredHosts }));
      autoInstrUninstallers.push(installXhrInstrumentation({ ignoredHosts }));
    }
    if (auto.errors !== false) {
      autoInstrUninstallers.push(installErrorInstrumentation());
      autoInstrUninstallers.push(installUnhandledRejectionInstrumentation());
    }
    if (auto.lifecycle !== false) {
      autoInstrUninstallers.push(installAppStateInstrumentation());
    }
  },

  log(name: string, attributes: Attributes = {}, severity: SeverityNumber = 9): void {
    if (!started || !bridge) return;
    bridge.emit({
      kind: 'log',
      name,
      severity,
      attributes,
      timeUnixNano: nowUnixNano(),
    });
  },

  startSpan(name: string, attributes: Attributes = {}, spanKind: SpanKind = 'INTERNAL'): SpanHandle {
    const startPayload: SpanStartPayload = {
      kind: 'spanStart',
      spanId: randomSpanId(),
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
        if (!started || !bridge) return;
        bridge.emit({
          kind: 'spanEnd',
          spanId: startPayload.spanId,
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
}
