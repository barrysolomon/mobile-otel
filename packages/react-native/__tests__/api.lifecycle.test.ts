/**
 * Public API lifecycle edge cases: pre-start, double-start, double-shutdown,
 * shutdown-without-start, and flushWindow timing relative to start.
 *
 * These are not covered by api.public.test.ts, which focuses on the
 * happy-path surface. A silent regression here (e.g. a double-start
 * installing two copies of fetch instrumentation) would be painful to
 * diagnose in the wild, so the tests are explicit.
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

function makeFakeNative(): NativeDash0MobileModule & {
  emitted: BridgePayload[];
  startCount: number;
  shutdownCount: number;
  flushMinutesArgs: number[];
} {
  const emitted: BridgePayload[] = [];
  const state = {
    emitted,
    startCount: 0,
    shutdownCount: 0,
    flushMinutesArgs: [] as number[],
    async start() {
      this.startCount++;
    },
    async emitBatch(payloads: BridgePayload[]) {
      emitted.push(...payloads);
    },
    async flushWindow(minutes: number) {
      this.flushMinutesArgs.push(minutes);
    },
    async shutdown() {
      this.shutdownCount++;
    },
  };
  return state as never;
}

describe('Dash0Mobile lifecycle edge cases', () => {
  afterEach(async () => {
    // Best-effort tear-down — some tests leave the SDK started intentionally.
    try {
      await Dash0Mobile.shutdown();
    } catch {
      // ignore
    }
    __resetForTesting();
  });

  it('flushWindow() before start() is a silent no-op (does not throw, does not call native)', async () => {
    const native = makeFakeNative();
    __setNativeForTesting(native);
    await expect(Dash0Mobile.flushWindow(5)).resolves.toBeUndefined();
    expect(native.flushMinutesArgs).toEqual([]);
  });

  it('shutdown() before start() is a silent no-op', async () => {
    const native = makeFakeNative();
    __setNativeForTesting(native);
    await expect(Dash0Mobile.shutdown()).resolves.toBeUndefined();
    expect(native.shutdownCount).toBe(0);
  });

  it('calling start() twice forwards twice to native.start (caller responsibility)', async () => {
    const native = makeFakeNative();
    __setNativeForTesting(native);
    await Dash0Mobile.start({
      serviceName: 'rn-test',
      endpoint: 'https://collector.example.com:4317',
    });
    await Dash0Mobile.start({
      serviceName: 'rn-test-2',
      endpoint: 'https://other.example.com:4317',
    });
    expect(native.startCount).toBe(2);
  });

  it('shutdown() is idempotent — second call after first tears down cleanly is a no-op', async () => {
    const native = makeFakeNative();
    __setNativeForTesting(native);
    await Dash0Mobile.start({
      serviceName: 'rn-test',
      endpoint: 'https://collector.example.com:4317',
    });
    await Dash0Mobile.shutdown();
    await Dash0Mobile.shutdown();
    expect(native.shutdownCount).toBe(1);
  });

  it('log() after shutdown() is a no-op (does not resurrect the bridge)', async () => {
    const native = makeFakeNative();
    __setNativeForTesting(native);
    await Dash0Mobile.start({
      serviceName: 'rn-test',
      endpoint: 'https://collector.example.com:4317',
    });
    await Dash0Mobile.shutdown();
    native.emitted.length = 0;
    Dash0Mobile.log('after.shutdown');
    await Dash0Mobile.flushWindow(0);
    expect(native.emitted).toHaveLength(0);
  });

  it('flushWindow() after shutdown() is a silent no-op', async () => {
    const native = makeFakeNative();
    __setNativeForTesting(native);
    await Dash0Mobile.start({
      serviceName: 'rn-test',
      endpoint: 'https://collector.example.com:4317',
    });
    await Dash0Mobile.shutdown();
    native.flushMinutesArgs.length = 0;
    await expect(Dash0Mobile.flushWindow(10)).resolves.toBeUndefined();
    expect(native.flushMinutesArgs).toEqual([]);
  });
});
