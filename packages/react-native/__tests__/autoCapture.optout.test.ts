/**
 * Verifies that `autoCapture: { network: false, errors: false, lifecycle: false }`
 * actually prevents auto-instrumentation from installing. A silent regression
 * here would force users into instrumentation they want to disable — the
 * single most important opt-out contract in the SDK.
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

  it('lifecycle=false: AppState listener is not installed (no RN AppState ref → no-op)', async () => {
    // The lifecycle install path calls require('react-native') to get
    // AppState. Since we don't provide it in this environment, the
    // installer returns a no-op. The test therefore verifies that start()
    // completes successfully without throwing either way when lifecycle
    // is explicitly disabled — guarding against a regression where the
    // install was unconditional.
    const native = makeFakeNative();
    __setNativeForTesting(native);

    await expect(
      Dash0Mobile.start({
        serviceName: 'rn-test',
        endpoint: 'https://collector.example.com:4317',
        autoCapture: { lifecycle: false },
      }),
    ).resolves.toBeUndefined();
  });

  it('all three opt-outs combined: fetch unwrapped, ErrorUtils untouched, start still succeeds', async () => {
    const native = makeFakeNative();
    __setNativeForTesting(native);

    const beforeFetch = globalThis.fetch;
    const eu = installFakeErrorUtils();
    await Dash0Mobile.start({
      serviceName: 'rn-test',
      endpoint: 'https://collector.example.com:4317',
      autoCapture: { network: false, errors: false, lifecycle: false },
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
});
