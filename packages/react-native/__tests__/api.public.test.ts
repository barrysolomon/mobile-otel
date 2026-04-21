/**
 * RN-020 tests — public Dash0Mobile API wired through NativeBridge.
 *
 * These tests inject a mock native module via __setNativeForTesting so they
 * never touch the real RN bridge. Production callers go through the default
 * NativeModules.Dash0Mobile lookup (exercised in native-module tests).
 */

import { Dash0Mobile, __setNativeForTesting, __resetForTesting } from '../src';
import type {
  BridgePayload,
  LogPayload,
  MetricPayload,
  NativeDash0MobileModule,
  SpanEndPayload,
  SpanStartPayload,
  StartConfig,
} from '../src/bridge/types';

type Mocks = {
  native: NativeDash0MobileModule;
  start: jest.Mock<Promise<void>, [StartConfig]>;
  emitBatch: jest.Mock<Promise<void>, [BridgePayload[]]>;
  flushWindow: jest.Mock<Promise<void>, [number]>;
  shutdown: jest.Mock<Promise<void>, []>;
};

function installMock(): Mocks {
  const start = jest.fn<Promise<void>, [StartConfig]>(async () => {});
  const emitBatch = jest.fn<Promise<void>, [BridgePayload[]]>(async () => {});
  const flushWindow = jest.fn<Promise<void>, [number]>(async () => {});
  const shutdown = jest.fn<Promise<void>, []>(async () => {});
  const native: NativeDash0MobileModule = {
    start,
    emitBatch,
    flushWindow,
    shutdown,
  };
  __setNativeForTesting(native);
  return { native, start, emitBatch, flushWindow, shutdown };
}

async function flush(): Promise<void> {
  // NativeBridge debounce is 50ms; drain timers then microtasks.
  jest.advanceTimersByTime(60);
  await Promise.resolve();
  await Promise.resolve();
}

describe('Dash0Mobile public API', () => {
  beforeEach(() => {
    jest.useFakeTimers();
  });
  afterEach(() => {
    jest.useRealTimers();
    __resetForTesting();
  });

  describe('start()', () => {
    it('forwards the config to native.start and injects distro attributes', async () => {
      const { start } = installMock();
      const cfg: StartConfig = {
        serviceName: 'otel-rn-astronomy-shop',
        endpoint: 'https://ingress/v1/logs',
        dataset: 'otel-mobile',
      };
      await Dash0Mobile.start(cfg);
      expect(start).toHaveBeenCalledTimes(1);
      const forwarded = start.mock.calls[0][0];
      expect(forwarded).toMatchObject(cfg);
      // The bridge auto-injects OTel-spec distribution attributes so Dash0
      // can distinguish RN-originated telemetry from direct native use.
      expect(forwarded.extraResourceAttributes).toMatchObject({
        'telemetry.distro.name': 'dash0-react-native',
        'telemetry.distro.version': expect.stringMatching(/^\d+\.\d+\.\d+/),
      });
    });

    it('caller-provided extraResourceAttributes win on key collision', async () => {
      const { start } = installMock();
      await Dash0Mobile.start({
        serviceName: 's',
        endpoint: 'e',
        extraResourceAttributes: {
          'telemetry.distro.name': 'custom-distro',
          'build.channel': 'nightly',
        },
      });
      const forwarded = start.mock.calls[0][0];
      const extras = forwarded.extraResourceAttributes ?? {};
      expect(extras['telemetry.distro.name']).toBe('custom-distro');
      expect(extras['build.channel']).toBe('nightly');
    });
  });

  describe('log()', () => {
    it('emits a LogPayload with default severity INFO (9)', async () => {
      const { emitBatch } = installMock();
      await Dash0Mobile.start({ serviceName: 's', endpoint: 'e' });
      Dash0Mobile.log('cart.add_item', { 'shop.item_id': 'abc', qty: 2 });
      await flush();
      expect(emitBatch).toHaveBeenCalledTimes(1);
      const batch = emitBatch.mock.calls[0][0];
      expect(batch).toHaveLength(1);
      const log = batch[0] as LogPayload;
      expect(log.kind).toBe('log');
      expect(log.name).toBe('cart.add_item');
      expect(log.severity).toBe(9);
      expect(log.attributes).toEqual({ 'shop.item_id': 'abc', qty: 2 });
      expect(typeof log.timeUnixNano).toBe('string');
      expect(log.timeUnixNano.length).toBeGreaterThan(10);
    });

    it('honors an explicit severity', async () => {
      const { emitBatch } = installMock();
      await Dash0Mobile.start({ serviceName: 's', endpoint: 'e' });
      Dash0Mobile.log('boom', { 'exception.type': 'E' }, 17);
      await flush();
      expect((emitBatch.mock.calls[0][0][0] as LogPayload).severity).toBe(17);
    });
  });

  describe('startSpan() / span()', () => {
    it('emits a spanStart then a spanEnd with matching spanId', async () => {
      const { emitBatch } = installMock();
      await Dash0Mobile.start({ serviceName: 's', endpoint: 'e' });
      const handle = Dash0Mobile.startSpan('checkout', { 'shop.cart_size': 3 });
      handle.setAttribute('http.response.status_code', 200);
      handle.setStatus('OK');
      handle.end();
      await flush();

      const all = emitBatch.mock.calls.flatMap(([b]) => b);
      const start = all.find(p => p.kind === 'spanStart') as
        | SpanStartPayload
        | undefined;
      const end = all.find(p => p.kind === 'spanEnd') as
        | SpanEndPayload
        | undefined;
      expect(start).toBeDefined();
      expect(end).toBeDefined();
      expect(start!.name).toBe('checkout');
      expect(start!.spanKind).toBe('INTERNAL');
      expect(start!.attributes['shop.cart_size']).toBe(3);
      expect(end!.status).toBe('OK');
      expect(end!.attributes['http.response.status_code']).toBe(200);
      expect(end!.spanId).toBe(start!.spanId);
      expect(start!.spanId).toMatch(/^[0-9a-f]{16}$/);
    });

    it('span() wraps a callback, sets OK on success, and ends the span', async () => {
      const { emitBatch } = installMock();
      await Dash0Mobile.start({ serviceName: 's', endpoint: 'e' });
      const result = await Dash0Mobile.span('load', async () => 42);
      expect(result).toBe(42);
      await flush();
      const ends = emitBatch.mock.calls
        .flatMap(([b]) => b)
        .filter((p): p is SpanEndPayload => p.kind === 'spanEnd');
      expect(ends).toHaveLength(1);
      expect(ends[0].status).toBe('OK');
    });

    it('span() sets ERROR status when the callback throws', async () => {
      const { emitBatch } = installMock();
      await Dash0Mobile.start({ serviceName: 's', endpoint: 'e' });
      await expect(
        Dash0Mobile.span('load', async () => {
          throw new Error('nope');
        }),
      ).rejects.toThrow('nope');
      await flush();
      const ends = emitBatch.mock.calls
        .flatMap(([b]) => b)
        .filter((p): p is SpanEndPayload => p.kind === 'spanEnd');
      expect(ends).toHaveLength(1);
      expect(ends[0].status).toBe('ERROR');
      expect(ends[0].statusMessage).toBe('nope');
    });
  });

  describe('recordMetric()', () => {
    it.each(['counter', 'histogram', 'gauge'] as const)(
      'emits a MetricPayload for %s',
      async instrumentType => {
        const { emitBatch } = installMock();
        await Dash0Mobile.start({ serviceName: 's', endpoint: 'e' });
        Dash0Mobile.recordMetric('shop.x', 3, instrumentType, { foo: 'bar' });
        await flush();
        const m = emitBatch.mock.calls[0][0][0] as MetricPayload;
        expect(m.kind).toBe('metric');
        expect(m.instrumentType).toBe(instrumentType);
        expect(m.value).toBe(3);
        expect(m.attributes).toEqual({ foo: 'bar' });
      },
    );
  });

  describe('flushWindow() / shutdown()', () => {
    it('flushWindow() forwards minutes and flushes pending bridge events first', async () => {
      const { emitBatch, flushWindow } = installMock();
      await Dash0Mobile.start({ serviceName: 's', endpoint: 'e' });
      Dash0Mobile.log('x');
      await Dash0Mobile.flushWindow(5);
      // The pending log must have been drained before flushWindow fires.
      expect(emitBatch).toHaveBeenCalledTimes(1);
      expect(flushWindow).toHaveBeenCalledWith(5);
    });

    it('shutdown() drains pending events then calls native.shutdown', async () => {
      const { emitBatch, shutdown } = installMock();
      await Dash0Mobile.start({ serviceName: 's', endpoint: 'e' });
      Dash0Mobile.log('last');
      await Dash0Mobile.shutdown();
      expect(emitBatch).toHaveBeenCalledTimes(1);
      expect(shutdown).toHaveBeenCalled();
    });
  });

  describe('pre-start calls', () => {
    it('log() before start() is a no-op (does not throw, does not emit)', async () => {
      const { emitBatch } = installMock();
      // Intentionally NO start().
      expect(() => Dash0Mobile.log('pre-start')).not.toThrow();
      await flush();
      expect(emitBatch).not.toHaveBeenCalled();
    });
  });
});
