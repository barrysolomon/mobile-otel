/**
 * RN-034 — Touch instrumentation (opt-in).
 *
 * Rather than monkey-patching `Touchable*` components (fragile across RN
 * versions and blocked by the New Arch), we ship two ergonomic helpers:
 *
 *   1. `withTapTelemetry(name)` — wraps a bare onPress handler; returns
 *      an onPress that emits a `ui.tap` log before forwarding.
 *
 *   2. `useTapHandler(name, onPress)` — hook-equivalent for function
 *      components.
 *
 * Both emit the same payload so the Dash0 UI sees one consistent event
 * regardless of which idiom the caller used.
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
import { withTapTelemetry } from '../../src/instrumentation/touch';

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

describe('touch instrumentation', () => {
  let native: ReturnType<typeof makeFakeNative>;

  beforeEach(async () => {
    native = makeFakeNative();
    __setNativeForTesting(native);
    await Dash0Mobile.start({
      serviceName: 'test-rn',
      endpoint: 'https://collector.example.com:4317',
    });
  });

  afterEach(async () => {
    await Dash0Mobile.shutdown();
    __resetForTesting();
  });

  function tapLogs(): LogPayload[] {
    return native.emitted.filter(
      (p): p is LogPayload => p.kind === 'log' && p.name === 'ui.tap',
    );
  }

  it('emits ui.tap log with the given target name on press', async () => {
    const underlying = jest.fn();
    const wrapped = withTapTelemetry('cart.checkout_button', underlying);
    wrapped();
    await Dash0Mobile.flushWindow(0);

    const logs = tapLogs();
    expect(logs).toHaveLength(1);
    expect(logs[0].attributes['ui.target']).toBe('cart.checkout_button');
    expect(underlying).toHaveBeenCalledTimes(1);
  });

  it('forwards arguments and return value to the underlying handler', () => {
    const underlying = jest.fn((a: number, b: number) => a + b);
    const wrapped = withTapTelemetry('math.sum', underlying);
    const result = wrapped(2, 3);
    expect(result).toBe(5);
    expect(underlying).toHaveBeenCalledWith(2, 3);
  });

  it('still emits telemetry when the underlying handler throws', async () => {
    const underlying = jest.fn(() => {
      throw new Error('boom');
    });
    const wrapped = withTapTelemetry('broken', underlying);
    expect(() => wrapped()).toThrow('boom');
    await Dash0Mobile.flushWindow(0);
    expect(tapLogs()).toHaveLength(1);
  });

  it('works with undefined underlying handler (read-only buttons)', async () => {
    const wrapped = withTapTelemetry('cosmetic');
    wrapped();
    await Dash0Mobile.flushWindow(0);
    expect(tapLogs()).toHaveLength(1);
  });

  it('includes optional extra attributes', async () => {
    const wrapped = withTapTelemetry('product.row', undefined, {
      'product.sku': 'SKU-123',
    });
    wrapped();
    await Dash0Mobile.flushWindow(0);

    const logs = tapLogs();
    expect(logs[0].attributes['ui.target']).toBe('product.row');
    expect(logs[0].attributes['product.sku']).toBe('SKU-123');
  });
});
