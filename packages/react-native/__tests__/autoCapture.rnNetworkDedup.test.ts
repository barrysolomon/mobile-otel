/**
 * RN's fetch() is implemented on top of XMLHttpRequest, so installing both
 * shims produces two CLIENT spans per request. This test pins the dedup
 * contract: in an RN-detected environment, `Dash0Mobile.start()` installs
 * XHR only; in a non-RN environment (Jest default, web/SSR), both install.
 *
 * Regression guard for the Gate 2 finding from the 2026-04-22 RN iOS
 * validation sweep (see docs/epics/VALIDATION_MATRIX_EPIC.md).
 */

import {
  Dash0Mobile,
  __setNativeForTesting,
  __resetForTesting,
} from '../src';
import type {
  BridgePayload,
  NativeDash0MobileModule,
} from '../src/bridge/types';

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
  };
}

// Minimal placeholder XHR ctor — we're not testing functionality here, just
// whether the Proxy wrapper replaces it. Shape-identical enough for Reflect.construct.
class StubXHR {
  open() {}
  send() {}
  addEventListener() {}
}

describe('RN network shim dedup', () => {
  let originalFetch: typeof globalThis.fetch | undefined;
  let originalXHR: typeof globalThis.XMLHttpRequest | undefined;
  let originalNavigator: unknown;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
    originalXHR = globalThis.XMLHttpRequest;
    originalNavigator = (globalThis as unknown as { navigator?: unknown }).navigator;
    (globalThis as unknown as { XMLHttpRequest: typeof StubXHR }).XMLHttpRequest = StubXHR;
  });

  afterEach(async () => {
    try {
      await Dash0Mobile.shutdown();
    } catch {
      // ignore
    }
    globalThis.fetch = originalFetch as typeof globalThis.fetch;
    if (originalXHR) {
      globalThis.XMLHttpRequest = originalXHR;
    } else {
      delete (globalThis as unknown as { XMLHttpRequest?: unknown }).XMLHttpRequest;
    }
    (globalThis as unknown as { navigator?: unknown }).navigator = originalNavigator;
    __resetForTesting();
  });

  it('on React Native, network=true installs XHR only (fetch stays unwrapped)', async () => {
    (globalThis as unknown as { navigator: { product: string } }).navigator = {
      product: 'ReactNative',
    };

    const native = makeFakeNative();
    __setNativeForTesting(native);

    const beforeFetch = globalThis.fetch;
    const beforeXHR = globalThis.XMLHttpRequest;

    await Dash0Mobile.start({
      serviceName: 'rn-dedup-test',
      endpoint: 'https://collector.example.com:4317',
    });

    expect(globalThis.fetch).toBe(beforeFetch);
    expect(globalThis.XMLHttpRequest).not.toBe(beforeXHR);
  });

  it('outside React Native, network=true installs BOTH fetch and XHR', async () => {
    // Jest default environment — navigator.product is undefined (node) or
    // 'Gecko' (jsdom). Either way, not 'ReactNative'.
    delete (globalThis as unknown as { navigator?: unknown }).navigator;

    const native = makeFakeNative();
    __setNativeForTesting(native);

    const beforeFetch = globalThis.fetch;
    const beforeXHR = globalThis.XMLHttpRequest;

    await Dash0Mobile.start({
      serviceName: 'web-dedup-test',
      endpoint: 'https://collector.example.com:4317',
    });

    expect(globalThis.fetch).not.toBe(beforeFetch);
    expect(globalThis.XMLHttpRequest).not.toBe(beforeXHR);
  });
});
