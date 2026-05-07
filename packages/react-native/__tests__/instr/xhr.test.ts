/**
 * RN-030 — XMLHttpRequest auto-instrumentation.
 *
 * RN's `fetch` delegates to XHR under Hermes, so some libraries (axios,
 * apollo) go straight to XHR and bypass the fetch wrapper. We patch
 * `XMLHttpRequest.prototype.open / send` to capture a CLIENT span for
 * each request with OTel semconv HTTP attributes.
 *
 * Mirrors the fetch wrapper's behavior: honors `ignoredHosts` (so our own
 * OTLP exports don't self-capture) and marks 4xx/5xx as ERROR status.
 */

import {
  Dash0Mobile,
  __setNativeForTesting,
  __resetForTesting,
} from '../../src';
import type {
  BridgePayload,
  NativeDash0MobileModule,
  SpanEndPayload,
  SpanStartPayload,
} from '../../src/bridge/types';
import { installXhrInstrumentation } from '../../src/instrumentation/xhr';

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

type Listener = () => void;

class FakeXHR {
  static instances: FakeXHR[] = [];
  method = '';
  url = '';
  status = 0;
  readyState = 0;
  private listeners = new Map<string, Set<Listener>>();

  constructor() {
    FakeXHR.instances.push(this);
  }

  open(method: string, url: string) {
    this.method = method;
    this.url = url;
  }

  send(_body?: string) {
    // no-op — test drives completion via _finish()
  }

  addEventListener(type: string, listener: Listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type)!.add(listener);
  }

  removeEventListener(type: string, listener: Listener) {
    this.listeners.get(type)?.delete(listener);
  }

  _finish(status: number) {
    this.status = status;
    this.readyState = 4;
    this.listeners.get('loadend')?.forEach(l => l());
  }

  _fail() {
    this.listeners.get('error')?.forEach(l => l());
    this.listeners.get('loadend')?.forEach(l => l());
  }
}

describe('XMLHttpRequest auto-instrumentation', () => {
  let native: ReturnType<typeof makeFakeNative>;
  let uninstall: (() => void) | null = null;
  let originalXHR: typeof globalThis.XMLHttpRequest | undefined;

  beforeEach(async () => {
    native = makeFakeNative();
    __setNativeForTesting(native);
    await Dash0Mobile.start({
      serviceName: 'test-rn',
      endpoint: 'https://collector.example.com:4317',
    });

    originalXHR = (globalThis as unknown as { XMLHttpRequest?: unknown })
      .XMLHttpRequest as typeof globalThis.XMLHttpRequest | undefined;
    (globalThis as unknown as { XMLHttpRequest: unknown }).XMLHttpRequest =
      FakeXHR;
    FakeXHR.instances = [];
  });

  afterEach(async () => {
    if (uninstall) uninstall();
    uninstall = null;
    (globalThis as unknown as { XMLHttpRequest: unknown }).XMLHttpRequest =
      originalXHR;
    await Dash0Mobile.shutdown();
    __resetForTesting();
  });

  function spanEvents(): {
    starts: SpanStartPayload[];
    ends: SpanEndPayload[];
  } {
    const starts = native.emitted.filter(
      (p): p is SpanStartPayload => p.kind === 'spanStart',
    );
    const ends = native.emitted.filter(
      (p): p is SpanEndPayload => p.kind === 'spanEnd',
    );
    return { starts, ends };
  }

  it('emits a CLIENT span with semconv attrs on successful 200', async () => {
    uninstall = installXhrInstrumentation();

    const xhr = new (globalThis.XMLHttpRequest as unknown as typeof FakeXHR)();
    xhr.open('GET', 'https://api.example.com/items?q=1');
    xhr.send();
    xhr._finish(200);
    await Dash0Mobile.flushWindow(0);

    const { starts, ends } = spanEvents();
    expect(starts).toHaveLength(1);
    expect(starts[0].spanKind).toBe('CLIENT');
    expect(starts[0].name).toBe('GET api.example.com');
    expect(starts[0].attributes['http.request.method']).toBe('GET');
    expect(starts[0].attributes['url.full']).toBe(
      'https://api.example.com/items?q=1',
    );
    expect(starts[0].attributes['server.address']).toBe('api.example.com');
    expect(ends).toHaveLength(1);
    expect(ends[0].attributes['http.response.status_code']).toBe(200);
    expect(ends[0].status).toBe('OK');
  });

  it('marks 5xx as ERROR', async () => {
    uninstall = installXhrInstrumentation();
    const xhr = new (globalThis.XMLHttpRequest as unknown as typeof FakeXHR)();
    xhr.open('POST', 'https://api.example.com/checkout');
    xhr.send();
    xhr._finish(500);
    await Dash0Mobile.flushWindow(0);

    const { ends } = spanEvents();
    expect(ends[0].status).toBe('ERROR');
    expect(ends[0].attributes['http.response.status_code']).toBe(500);
  });

  it('emits ERROR for network failure', async () => {
    uninstall = installXhrInstrumentation();
    const xhr = new (globalThis.XMLHttpRequest as unknown as typeof FakeXHR)();
    xhr.open('GET', 'https://api.example.com/items');
    xhr.send();
    xhr._fail();
    await Dash0Mobile.flushWindow(0);

    const { ends } = spanEvents();
    expect(ends[0].status).toBe('ERROR');
  });

  it('respects ignoredHosts', async () => {
    uninstall = installXhrInstrumentation({
      ignoredHosts: ['collector.example.com'],
    });
    const xhr = new (globalThis.XMLHttpRequest as unknown as typeof FakeXHR)();
    xhr.open('POST', 'https://collector.example.com:4317/v1/traces');
    xhr.send();
    xhr._finish(200);
    await Dash0Mobile.flushWindow(0);

    const { starts } = spanEvents();
    expect(starts).toHaveLength(0);
  });

  it('uninstall restores the original XMLHttpRequest', () => {
    const before = globalThis.XMLHttpRequest;
    const un = installXhrInstrumentation();
    expect(globalThis.XMLHttpRequest).not.toBe(before);
    un();
    expect(globalThis.XMLHttpRequest).toBe(before);
  });
});
