/**
 * RN-031 (continued) — unhandled promise rejection capture.
 *
 * RN doesn't use Node's process.on('unhandledRejection'). The runtime dispatches
 * `unhandledrejection` CustomEvents to a HermesInternal / globalThis listener
 * registry managed by the `promise` polyfill (react-native uses it by default).
 *
 * Our contract: if a listener-registration path exists on globalThis, we hook
 * it and emit an `app.error` log keyed by the reason's `name::message` so that
 * a synchronous thrown Error and its promise-rejection counterpart dedupe
 * together (same key space as the sync path).
 */

import {
  Dash0Mobile,
  __setNativeForTesting,
  __resetForTesting,
} from '../../src';
import type {
  BridgePayload,
  LogPayload,
  NativeDash0MobileModule,
} from '../../src/bridge/types';
import { installUnhandledRejectionInstrumentation } from '../../src/instrumentation/unhandledRejection';

function makeFakeNative(): NativeDash0MobileModule & { emitted: BridgePayload[] } {
  const emitted: BridgePayload[] = [];
  return {
    emitted,
    async start() {},
    async emitBatch(payloads) {
      emitted.push(...payloads);
    },
    async flushWindow() {},
    async shutdown() {},
    async startJourney() { return 'mock-journey-id'; },
    async endJourney() {},
    async captureScreenshot() {},
    async captureWireframe() {},
  };
}

type Listener = (ev: { reason: unknown; promise?: Promise<unknown> }) => void;

interface EventTargetLike {
  addEventListener(type: string, listener: Listener): void;
  removeEventListener(type: string, listener: Listener): void;
  _listeners: Map<string, Set<Listener>>;
  _dispatch(type: string, ev: { reason: unknown }): void;
}

function installFakeEventTarget(): EventTargetLike {
  const listeners = new Map<string, Set<Listener>>();
  const target: EventTargetLike = {
    _listeners: listeners,
    addEventListener(type, listener) {
      if (!listeners.has(type)) listeners.set(type, new Set());
      listeners.get(type)!.add(listener);
    },
    removeEventListener(type, listener) {
      listeners.get(type)?.delete(listener);
    },
    _dispatch(type, ev) {
      listeners.get(type)?.forEach(l => l(ev));
    },
  };
  const g = globalThis as unknown as {
    addEventListener?: EventTargetLike['addEventListener'];
    removeEventListener?: EventTargetLike['removeEventListener'];
  };
  g.addEventListener = target.addEventListener;
  g.removeEventListener = target.removeEventListener;
  return target;
}

describe('unhandledrejection auto-instrumentation', () => {
  let uninstall: (() => void) | null = null;
  let native: ReturnType<typeof makeFakeNative>;
  let target: EventTargetLike;

  beforeEach(async () => {
    native = makeFakeNative();
    __setNativeForTesting(native);
    await Dash0Mobile.start({
      serviceName: 'test-rn',
      endpoint: 'https://collector.example.com:4317',
    });
    target = installFakeEventTarget();
  });

  afterEach(async () => {
    if (uninstall) uninstall();
    uninstall = null;
    const g = globalThis as unknown as {
      addEventListener?: unknown;
      removeEventListener?: unknown;
    };
    delete g.addEventListener;
    delete g.removeEventListener;
    await Dash0Mobile.shutdown();
    __resetForTesting();
  });

  function findErrorLogs(): LogPayload[] {
    return native.emitted.filter(
      (p): p is LogPayload => p.kind === 'log' && p.name === 'app.error',
    );
  }

  it('emits ERROR-severity log when an Error reason is rejected', async () => {
    uninstall = installUnhandledRejectionInstrumentation();

    target._dispatch('unhandledrejection', { reason: new TypeError('async boom') });
    await Dash0Mobile.flushWindow(0);

    const logs = findErrorLogs();
    expect(logs).toHaveLength(1);
    expect(logs[0].severity).toBe(17);
    expect(logs[0].attributes['exception.type']).toBe('TypeError');
    expect(logs[0].attributes['exception.message']).toBe('async boom');
    expect(logs[0].attributes['exception.escaped']).toBe(true);
  });

  it('handles non-Error reason by stringifying it', async () => {
    uninstall = installUnhandledRejectionInstrumentation();

    target._dispatch('unhandledrejection', { reason: 'just a string' });
    await Dash0Mobile.flushWindow(0);

    const logs = findErrorLogs();
    expect(logs).toHaveLength(1);
    expect(logs[0].attributes['exception.type']).toBe('UnhandledRejection');
    expect(logs[0].attributes['exception.message']).toBe('just a string');
  });

  it('deduplicates identical rejections within the window', async () => {
    uninstall = installUnhandledRejectionInstrumentation();

    const err = new Error('same');
    target._dispatch('unhandledrejection', { reason: err });
    target._dispatch('unhandledrejection', { reason: err });
    target._dispatch('unhandledrejection', { reason: err });
    await Dash0Mobile.flushWindow(0);

    expect(findErrorLogs()).toHaveLength(1);
  });

  it('returns a no-op uninstaller when no event target is present', () => {
    const g = globalThis as unknown as {
      addEventListener?: unknown;
      removeEventListener?: unknown;
    };
    delete g.addEventListener;
    delete g.removeEventListener;
    const un = installUnhandledRejectionInstrumentation();
    expect(typeof un).toBe('function');
    un();
  });

  it('uninstall removes the listener so further dispatches do not emit', async () => {
    const un = installUnhandledRejectionInstrumentation();
    un();

    target._dispatch('unhandledrejection', { reason: new Error('after uninstall') });
    await Dash0Mobile.flushWindow(0);

    expect(findErrorLogs()).toHaveLength(0);
  });
});
