/**
 * RN-030 test for fetch() auto-instrumentation.
 *
 * The instrumentation wraps the global fetch to emit a CLIENT span around
 * each request. Attribute names follow OTel HTTP semconv v1.23+ so they
 * match the iOS URLProtocol interceptor and the Android OkHttp interceptor —
 * one Dash0 filter surfaces all three platforms' network spans.
 */

import {Dash0Mobile, __setNativeForTesting, __resetForTesting} from '../../src';
import type {
  BridgePayload,
  NativeDash0MobileModule,
  SpanStartPayload,
  SpanEndPayload,
} from '../../src/bridge/types';
import {installFetchInstrumentation} from '../../src/instrumentation/fetch';

type Emitted = BridgePayload[];

function makeFakeNative(): NativeDash0MobileModule & {emitted: Emitted} {
  const emitted: Emitted = [];
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

function findSpanPair(
  payloads: BridgePayload[],
  name: string,
): {start: SpanStartPayload; end: SpanEndPayload} | null {
  const start = payloads.find(
    p => p.kind === 'spanStart' && p.name === name,
  ) as SpanStartPayload | undefined;
  if (!start) return null;
  const end = payloads.find(
    p => p.kind === 'spanEnd' && p.spanId === start.spanId,
  ) as SpanEndPayload | undefined;
  if (!end) return null;
  return {start, end};
}

describe('fetch auto-instrumentation', () => {
  const originalFetch = global.fetch;
  let uninstall: (() => void) | null = null;
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
    if (uninstall) {
      uninstall();
      uninstall = null;
    }
    global.fetch = originalFetch;
    await Dash0Mobile.shutdown();
    __resetForTesting();
  });

  it('wraps global fetch and emits an HTTP CLIENT span with semconv attributes', async () => {
    global.fetch = jest.fn(async () =>
      ({
        status: 200,
        ok: true,
      } as unknown as Response),
    );
    uninstall = installFetchInstrumentation();

    await global.fetch('https://example.com/api/x', {method: 'GET'});
    await Dash0Mobile.flushWindow(0); // force the debounced batch

    const pair = findSpanPair(native.emitted, 'GET');
    expect(pair).not.toBeNull();
    expect(pair!.start.spanKind).toBe('CLIENT');
    expect(pair!.start.attributes['http.request.method']).toBe('GET');
    expect(pair!.start.attributes['url.full']).toBe('https://example.com/api/x');
    expect(pair!.start.attributes['server.address']).toBe('example.com');
    expect(pair!.end.attributes['http.response.status_code']).toBe(200);
    expect(pair!.end.status).toBe('OK');
  });

  it('records status ERROR when fetch rejects (network failure)', async () => {
    global.fetch = jest.fn(async () => {
      throw new TypeError('Network request failed');
    });
    uninstall = installFetchInstrumentation();

    await expect(
      global.fetch('https://example.com/api/y'),
    ).rejects.toThrow('Network request failed');
    await Dash0Mobile.flushWindow(0);

    const pair = findSpanPair(native.emitted, 'GET');
    expect(pair).not.toBeNull();
    expect(pair!.end.status).toBe('ERROR');
    expect(pair!.end.statusMessage).toBe('Network request failed');
  });

  it('respects ignoredHosts config (no self-capture of OTLP exports)', async () => {
    global.fetch = jest.fn(async () => ({status: 200} as unknown as Response));
    uninstall = installFetchInstrumentation({
      ignoredHosts: ['collector.example.com'],
    });

    await global.fetch('https://collector.example.com:4317/v1/traces');
    await Dash0Mobile.flushWindow(0);

    const spanStarts = native.emitted.filter(p => p.kind === 'spanStart');
    expect(spanStarts).toHaveLength(0);
  });

  it('restores original fetch on uninstall', () => {
    const before = global.fetch;
    const unwrap = installFetchInstrumentation();
    expect(global.fetch).not.toBe(before);
    unwrap();
    expect(global.fetch).toBe(before);
  });
});
