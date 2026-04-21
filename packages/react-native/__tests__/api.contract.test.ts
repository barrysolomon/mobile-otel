/**
 * RN-001 contract test — MUST fail red until Phase 19a.2 (RN-020) lands.
 *
 * Asserts the public API shape of `@dash0/mobile-react-native`. If any of
 * these expectations drift, it's a breaking change to callers and must be
 * reflected in docs/REACT_NATIVE_SDK_GUIDE.md simultaneously.
 */

import { Dash0Mobile } from '../src';

describe('Dash0Mobile public API contract', () => {
  it('exposes the documented methods', () => {
    expect(typeof Dash0Mobile.start).toBe('function');
    expect(typeof Dash0Mobile.log).toBe('function');
    expect(typeof Dash0Mobile.startSpan).toBe('function');
    expect(typeof Dash0Mobile.span).toBe('function');
    expect(typeof Dash0Mobile.recordMetric).toBe('function');
    expect(typeof Dash0Mobile.flushWindow).toBe('function');
    expect(typeof Dash0Mobile.shutdown).toBe('function');
  });

  it('start() accepts the minimum required config', async () => {
    await expect(
      Dash0Mobile.start({
        serviceName: 'otel-rn-astronomy-shop',
        endpoint: 'https://ingress.example/v1/logs',
      })
    ).resolves.not.toThrow();
  });

  it('log() returns synchronously (fire-and-forget, batched under the hood)', () => {
    expect(() =>
      Dash0Mobile.log('cart.add_item', { 'shop.item_id': 'abc', qty: 2 })
    ).not.toThrow();
  });

  it('startSpan() returns a handle with setAttribute/setStatus/end', () => {
    const span = Dash0Mobile.startSpan('checkout');
    expect(typeof span.setAttribute).toBe('function');
    expect(typeof span.setStatus).toBe('function');
    expect(typeof span.end).toBe('function');
    span.end();
  });

  it('span() wraps a callback with automatic status + end', async () => {
    const result = await Dash0Mobile.span('load_catalog', async () => 42);
    expect(result).toBe(42);
  });

  it('recordMetric() accepts counter / histogram / gauge', () => {
    expect(() => Dash0Mobile.recordMetric('shop.cart.items_added', 1, 'counter')).not.toThrow();
    expect(() => Dash0Mobile.recordMetric('shop.checkout.duration_ms', 1234, 'histogram')).not.toThrow();
    expect(() => Dash0Mobile.recordMetric('app.memory_bytes', 1024 * 1024, 'gauge')).not.toThrow();
  });

  it('flushWindow() accepts minutes as a number', async () => {
    await expect(Dash0Mobile.flushWindow(5)).resolves.not.toThrow();
  });
});
