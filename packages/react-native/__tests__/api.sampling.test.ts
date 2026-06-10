/**
 * Loper finding #4 — RN sampling default + passthrough.
 *
 * The RN bridge MUST default trace sampling to `always_on` (NOT the native
 * SDKs' `dynamic(0.1)` default). RN manual spans are root spans with
 * arbitrary names, so a 10% baseline silently drops ~90% of a user's first
 * span. These tests assert the exact `sampling` payload that crosses the
 * native bridge for both the default and the explicit-opt-in cases.
 */

import {
  Dash0Mobile,
  __setNativeForTesting,
  __resetForTesting,
} from '../src';
import type { NativeDash0MobileModule, StartConfig } from '../src/bridge/types';

function makeFakeNative(): NativeDash0MobileModule & { startConfigs: StartConfig[] } {
  const startConfigs: StartConfig[] = [];
  const state = {
    startConfigs,
    async start(config: StartConfig) {
      startConfigs.push(config);
    },
    async emitBatch() {},
    async flushWindow() {},
    async shutdown() {},
    async startJourney() {
      return 'jid';
    },
    async endJourney() {},
    async captureScreenshot() {},
    async captureWireframe() {},
  };
  return state as never;
}

describe('Dash0Mobile sampling', () => {
  afterEach(async () => {
    try {
      await Dash0Mobile.shutdown();
    } catch {
      // ignore
    }
    __resetForTesting();
  });

  it('defaults to always_on when the caller omits sampling', async () => {
    const native = makeFakeNative();
    __setNativeForTesting(native);
    await Dash0Mobile.start({
      serviceName: 'rn-test',
      endpoint: 'https://collector.example.com:4317',
    });
    expect(native.startConfigs).toHaveLength(1);
    expect(native.startConfigs[0].sampling).toEqual({ strategy: 'always_on' });
  });

  it('passes an explicit always_off through verbatim', async () => {
    const native = makeFakeNative();
    __setNativeForTesting(native);
    await Dash0Mobile.start({
      serviceName: 'rn-test',
      endpoint: 'https://collector.example.com:4317',
      sampling: { strategy: 'always_off' },
    });
    expect(native.startConfigs[0].sampling).toEqual({ strategy: 'always_off' });
  });

  it('passes an explicit dynamic config with rates through verbatim', async () => {
    const native = makeFakeNative();
    __setNativeForTesting(native);
    await Dash0Mobile.start({
      serviceName: 'rn-test',
      endpoint: 'https://collector.example.com:4317',
      sampling: { strategy: 'dynamic', normalRate: 0.1, highPriorityRate: 1.0 },
    });
    expect(native.startConfigs[0].sampling).toEqual({
      strategy: 'dynamic',
      normalRate: 0.1,
      highPriorityRate: 1.0,
    });
  });

  it('does not mutate the caller-supplied config object', async () => {
    const native = makeFakeNative();
    __setNativeForTesting(native);
    const config: StartConfig = {
      serviceName: 'rn-test',
      endpoint: 'https://collector.example.com:4317',
    };
    await Dash0Mobile.start(config);
    // The default must be injected into the merged config sent to native,
    // not back onto the caller's object.
    expect(config.sampling).toBeUndefined();
    expect(native.startConfigs[0].sampling).toEqual({ strategy: 'always_on' });
  });
});
