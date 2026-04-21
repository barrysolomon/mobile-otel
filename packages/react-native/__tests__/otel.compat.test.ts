/**
 * RN-021 — OTel API compatibility shim.
 *
 * Third-party JS libraries expect `@opentelemetry/api`'s `trace.getTracer()`,
 * `logs.getLogger()`, `metrics.getMeter()` surface. We provide a minimal
 * compatible implementation backed by our bridge so app code can use the
 * standard API without caring where signals end up.
 *
 * Scope: the subset libraries actually call —
 *   tracer.startSpan(name, options?) → Span { setAttribute, setStatus, end }
 *   logger.emit({ body, severityNumber, attributes })
 *   meter.createCounter(name).add(value, attrs)
 *   meter.createHistogram(name).record(value, attrs)
 */

import {
  Dash0Mobile,
  __setNativeForTesting,
  __resetForTesting,
} from '../src';
import type {
  BridgePayload,
  LogPayload,
  MetricPayload,
  NativeDash0MobileModule,
  SpanEndPayload,
  SpanStartPayload,
} from '../src/bridge/types';
import { otel } from '../src/otel-compat';

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

describe('OTel API compatibility shim', () => {
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

  it('trace.getTracer returns an object with startSpan that forwards to the bridge', async () => {
    const tracer = otel.trace.getTracer('my-lib', '1.0.0');
    const span = tracer.startSpan('my.operation', {
      attributes: { 'code.function': 'render' },
    });
    span.setAttribute('retries', 2);
    span.setStatus({ code: 1 }); // OK
    span.end();
    await Dash0Mobile.flushWindow(0);

    const start = native.emitted.find(
      (p): p is SpanStartPayload => p.kind === 'spanStart',
    );
    const end = native.emitted.find(
      (p): p is SpanEndPayload => p.kind === 'spanEnd',
    );
    expect(start?.name).toBe('my.operation');
    expect(start?.attributes['code.function']).toBe('render');
    expect(end?.status).toBe('OK');
    expect(end?.attributes.retries).toBe(2);
  });

  it('trace.getTracer error status maps to ERROR', async () => {
    const tracer = otel.trace.getTracer('test');
    const span = tracer.startSpan('fail');
    span.setStatus({ code: 2, message: 'bad thing' });
    span.end();
    await Dash0Mobile.flushWindow(0);

    const end = native.emitted.find(
      (p): p is SpanEndPayload => p.kind === 'spanEnd',
    );
    expect(end?.status).toBe('ERROR');
    expect(end?.statusMessage).toBe('bad thing');
  });

  it('logs.getLogger emit forwards with mapped severity', async () => {
    const logger = otel.logs.getLogger('my-lib');
    logger.emit({
      body: 'user.login',
      severityNumber: 13, // WARN
      attributes: { 'user.id': 'u1' },
    });
    await Dash0Mobile.flushWindow(0);

    const log = native.emitted.find(
      (p): p is LogPayload => p.kind === 'log' && p.name === 'user.login',
    );
    expect(log?.severity).toBe(13);
    expect(log?.attributes['user.id']).toBe('u1');
  });

  it('metrics.getMeter counter.add forwards as counter metric', async () => {
    const meter = otel.metrics.getMeter('my-lib');
    const counter = meter.createCounter('requests_total');
    counter.add(1, { route: '/items' });
    counter.add(2, { route: '/items' });
    await Dash0Mobile.flushWindow(0);

    const metrics = native.emitted.filter(
      (p): p is MetricPayload => p.kind === 'metric' && p.name === 'requests_total',
    );
    expect(metrics).toHaveLength(2);
    expect(metrics[0].instrumentType).toBe('counter');
    expect(metrics[0].value).toBe(1);
    expect(metrics[1].value).toBe(2);
  });

  it('metrics.getMeter histogram.record forwards as histogram metric', async () => {
    const meter = otel.metrics.getMeter('my-lib');
    const hist = meter.createHistogram('request_duration_ms');
    hist.record(42.5, { route: '/items' });
    await Dash0Mobile.flushWindow(0);

    const metric = native.emitted.find(
      (p): p is MetricPayload => p.kind === 'metric' && p.name === 'request_duration_ms',
    );
    expect(metric?.instrumentType).toBe('histogram');
    expect(metric?.value).toBe(42.5);
    expect(metric?.attributes.route).toBe('/items');
  });
});
