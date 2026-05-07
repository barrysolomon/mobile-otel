/**
 * UJ-020/021 — Dash0Mobile journey + capture API passthrough tests.
 *
 * Verifies the thin JS facade correctly delegates startJourney, endJourney,
 * captureScreenshot, and captureWireframe to the native module, and that
 * pre-start / post-shutdown calls are safe no-ops.
 */

import {
  Dash0Mobile,
  __setNativeForTesting,
  __resetForTesting,
} from '../src';
import type {
  BridgePayload,
  NativeDash0MobileModule,
  StartConfig,
} from '../src/bridge/types';

function makeMockNative(): NativeDash0MobileModule & {
  calls: { method: string; args: unknown[] }[];
} {
  const calls: { method: string; args: unknown[] }[] = [];
  return {
    calls,
    async start() {},
    async emitBatch(_payloads: BridgePayload[]) {},
    async flushWindow() {},
    async shutdown() {},
    async startJourney(name: string) {
      calls.push({ method: 'startJourney', args: [name] });
      return `journey-${name}`;
    },
    async endJourney(journeyId: string) {
      calls.push({ method: 'endJourney', args: [journeyId] });
    },
    async captureScreenshot(trigger: string) {
      calls.push({ method: 'captureScreenshot', args: [trigger] });
    },
    async captureWireframe(trigger: string) {
      calls.push({ method: 'captureWireframe', args: [trigger] });
    },
  };
}

const startConfig: StartConfig = {
  serviceName: 'rn-journey-test',
  endpoint: 'https://collector.example.com:4317',
};

describe('Dash0Mobile journey API (UJ-020)', () => {
  afterEach(async () => {
    try { await Dash0Mobile.shutdown(); } catch {}
    __resetForTesting();
  });

  it('startJourney delegates to native and returns journey ID', async () => {
    const native = makeMockNative();
    __setNativeForTesting(native);
    await Dash0Mobile.start(startConfig);

    const id = await Dash0Mobile.startJourney('checkout');
    expect(id).toBe('journey-checkout');
    expect(native.calls).toContainEqual({
      method: 'startJourney',
      args: ['checkout'],
    });
  });

  it('endJourney delegates to native', async () => {
    const native = makeMockNative();
    __setNativeForTesting(native);
    await Dash0Mobile.start(startConfig);

    await Dash0Mobile.endJourney('journey-checkout');
    expect(native.calls).toContainEqual({
      method: 'endJourney',
      args: ['journey-checkout'],
    });
  });

  it('startJourney returns null before start()', async () => {
    const native = makeMockNative();
    __setNativeForTesting(native);

    const id = await Dash0Mobile.startJourney('checkout');
    expect(id).toBeNull();
    expect(native.calls).toHaveLength(0);
  });

  it('endJourney is no-op before start()', async () => {
    const native = makeMockNative();
    __setNativeForTesting(native);

    await Dash0Mobile.endJourney('nonexistent');
    expect(native.calls).toHaveLength(0);
  });

  it('journey calls are no-ops after shutdown', async () => {
    const native = makeMockNative();
    __setNativeForTesting(native);
    await Dash0Mobile.start(startConfig);
    await Dash0Mobile.shutdown();
    native.calls.length = 0;

    const id = await Dash0Mobile.startJourney('post-shutdown');
    expect(id).toBeNull();
    await Dash0Mobile.endJourney('anything');
    expect(native.calls).toHaveLength(0);
  });
});

describe('Dash0Mobile capture API (UJ-021)', () => {
  afterEach(async () => {
    try { await Dash0Mobile.shutdown(); } catch {}
    __resetForTesting();
  });

  it('captureScreenshot delegates to native with trigger', async () => {
    const native = makeMockNative();
    __setNativeForTesting(native);
    await Dash0Mobile.start(startConfig);

    await Dash0Mobile.captureScreenshot('error');
    expect(native.calls).toContainEqual({
      method: 'captureScreenshot',
      args: ['error'],
    });
  });

  it('captureWireframe delegates to native with trigger', async () => {
    const native = makeMockNative();
    __setNativeForTesting(native);
    await Dash0Mobile.start(startConfig);

    await Dash0Mobile.captureWireframe('screen_view');
    expect(native.calls).toContainEqual({
      method: 'captureWireframe',
      args: ['screen_view'],
    });
  });

  it('captureScreenshot defaults trigger to "manual"', async () => {
    const native = makeMockNative();
    __setNativeForTesting(native);
    await Dash0Mobile.start(startConfig);

    await Dash0Mobile.captureScreenshot();
    expect(native.calls).toContainEqual({
      method: 'captureScreenshot',
      args: ['manual'],
    });
  });

  it('captureWireframe defaults trigger to "manual"', async () => {
    const native = makeMockNative();
    __setNativeForTesting(native);
    await Dash0Mobile.start(startConfig);

    await Dash0Mobile.captureWireframe();
    expect(native.calls).toContainEqual({
      method: 'captureWireframe',
      args: ['manual'],
    });
  });

  it('captures are no-ops before start()', async () => {
    const native = makeMockNative();
    __setNativeForTesting(native);

    await Dash0Mobile.captureScreenshot('error');
    await Dash0Mobile.captureWireframe('error');
    expect(native.calls).toHaveLength(0);
  });

  it('captures are no-ops after shutdown', async () => {
    const native = makeMockNative();
    __setNativeForTesting(native);
    await Dash0Mobile.start(startConfig);
    await Dash0Mobile.shutdown();
    native.calls.length = 0;

    await Dash0Mobile.captureScreenshot('error');
    await Dash0Mobile.captureWireframe('error');
    expect(native.calls).toHaveLength(0);
  });
});
