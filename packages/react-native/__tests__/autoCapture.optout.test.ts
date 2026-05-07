/**
 * Verifies that `autoCapture: { network: false, errors: false }` actually
 * prevents JS-side auto-instrumentation from installing. A silent
 * regression here would force users into instrumentation they want to
 * disable — the single most important opt-out contract in the SDK.
 *
 * Lifecycle is intentionally absent: it's now native-only (Android
 * ProcessLifecycleOwner, iOS NotificationCenter) with no JS-side shim
 * and no per-flag knob.
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
    async startJourney() { return 'mock-journey-id'; },
    async endJourney() {},
    async captureScreenshot() {},
    async captureWireframe() {},
  };
}

type ErrorHandler = (err: Error, isFatal: boolean) => void;

function installFakeErrorUtils(): {
  getGlobalHandler: () => ErrorHandler;
  setGlobalHandler: jest.Mock<void, [ErrorHandler]>;
} {
  let current: ErrorHandler = () => {};
  const setGlobalHandler = jest.fn<void, [ErrorHandler]>(h => {
    current = h;
  });
  const eu = {
    getGlobalHandler: () => current,
    setGlobalHandler,
  };
  (globalThis as unknown as { ErrorUtils?: unknown }).ErrorUtils = eu;
  return eu;
}

describe('autoCapture opt-outs', () => {
  let originalFetch: typeof globalThis.fetch | undefined;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(async () => {
    try {
      await Dash0Mobile.shutdown();
    } catch {
      // ignore
    }
    globalThis.fetch = originalFetch as typeof globalThis.fetch;
    delete (globalThis as unknown as { ErrorUtils?: unknown }).ErrorUtils;
    __resetForTesting();
  });

  it('network=false leaves global fetch unwrapped', async () => {
    const native = makeFakeNative();
    __setNativeForTesting(native);

    const beforeFetch = globalThis.fetch;
    await Dash0Mobile.start({
      serviceName: 'rn-test',
      endpoint: 'https://collector.example.com:4317',
      autoCapture: { network: false },
    });
    expect(globalThis.fetch).toBe(beforeFetch);
  });

  it('network=true (default) DOES wrap global fetch', async () => {
    const native = makeFakeNative();
    __setNativeForTesting(native);

    const beforeFetch = globalThis.fetch;
    await Dash0Mobile.start({
      serviceName: 'rn-test',
      endpoint: 'https://collector.example.com:4317',
    });
    expect(globalThis.fetch).not.toBe(beforeFetch);
  });

  it('errors=false leaves ErrorUtils.setGlobalHandler untouched', async () => {
    const native = makeFakeNative();
    __setNativeForTesting(native);

    const eu = installFakeErrorUtils();
    await Dash0Mobile.start({
      serviceName: 'rn-test',
      endpoint: 'https://collector.example.com:4317',
      autoCapture: { errors: false },
    });
    expect(eu.setGlobalHandler).not.toHaveBeenCalled();
  });

  it('errors=true (default) DOES call ErrorUtils.setGlobalHandler', async () => {
    const native = makeFakeNative();
    __setNativeForTesting(native);

    const eu = installFakeErrorUtils();
    await Dash0Mobile.start({
      serviceName: 'rn-test',
      endpoint: 'https://collector.example.com:4317',
    });
    expect(eu.setGlobalHandler).toHaveBeenCalledTimes(1);
  });

  // Removed: a test that asserted `autoCapture: { lifecycle: false }`
  // produced a no-op install. Lifecycle is now native-only (Android
  // ProcessLifecycleOwner, iOS NotificationCenter); there is no JS-side
  // shim to opt out of, and no `lifecycle` field on the autoCapture type.

  it('both opt-outs combined: fetch unwrapped, ErrorUtils untouched, start still succeeds', async () => {
    const native = makeFakeNative();
    __setNativeForTesting(native);

    const beforeFetch = globalThis.fetch;
    const eu = installFakeErrorUtils();
    await Dash0Mobile.start({
      serviceName: 'rn-test',
      endpoint: 'https://collector.example.com:4317',
      autoCapture: { network: false, errors: false },
    });
    expect(globalThis.fetch).toBe(beforeFetch);
    expect(eu.setGlobalHandler).not.toHaveBeenCalled();
  });

  it('partial opt-out: network=false still installs errors + lifecycle (independent flags)', async () => {
    const native = makeFakeNative();
    __setNativeForTesting(native);

    const beforeFetch = globalThis.fetch;
    const eu = installFakeErrorUtils();
    await Dash0Mobile.start({
      serviceName: 'rn-test',
      endpoint: 'https://collector.example.com:4317',
      autoCapture: { network: false },
    });
    expect(globalThis.fetch).toBe(beforeFetch);
    expect(eu.setGlobalHandler).toHaveBeenCalledTimes(1);
  });

  describe('nativeAutoCapture bridge payload', () => {
    function makeCapturingNative(): NativeDash0MobileModule & { lastStartConfig: unknown } {
      const self = {
        lastStartConfig: undefined as unknown,
        async start(cfg: unknown) {
          self.lastStartConfig = cfg;
        },
        async emitBatch() {},
        async flushWindow() {},
        async shutdown() {},
        async startJourney() { return 'mock-journey-id'; },
        async endJourney() {},
        async captureScreenshot() {},
        async captureWireframe() {},
      };
      return self;
    }

    it('defaults to empty array when autoCapture is absent (native suite OFF by default on RN)', async () => {
      const native = makeCapturingNative();
      __setNativeForTesting(native);
      await Dash0Mobile.start({
        serviceName: 'rn-test',
        endpoint: 'https://collector.example.com:4317',
      });
      expect((native.lastStartConfig as { nativeAutoCapture: string[] }).nativeAutoCapture).toEqual([]);
    });

    it('opt-in flags propagate to nativeAutoCapture tokens', async () => {
      const native = makeCapturingNative();
      __setNativeForTesting(native);
      await Dash0Mobile.start({
        serviceName: 'rn-test',
        endpoint: 'https://collector.example.com:4317',
        autoCapture: { vitals: true, deviceStats: true, network: true },
      });
      const tokens = (native.lastStartConfig as { nativeAutoCapture: string[] }).nativeAutoCapture;
      expect(tokens).toEqual(expect.arrayContaining(['vitals', 'deviceStats', 'network']));
      expect(tokens).toHaveLength(3);
    });

    it('explicit `false` does NOT appear in nativeAutoCapture tokens', async () => {
      const native = makeCapturingNative();
      __setNativeForTesting(native);
      await Dash0Mobile.start({
        serviceName: 'rn-test',
        endpoint: 'https://collector.example.com:4317',
        autoCapture: { vitals: true, network: false },
      });
      expect((native.lastStartConfig as { nativeAutoCapture: string[] }).nativeAutoCapture).toEqual(['vitals']);
    });
  });
});
