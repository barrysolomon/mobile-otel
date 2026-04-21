/**
 * OTel-API compat shim edge cases: span kind mapping, gauge instrument,
 * multi-provider independence, and body-less log emit.
 *
 * The happy-path + status mapping is covered by otel.compat.test.ts. These
 * tests target the specific branches the happy-path doesn't hit.
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

describe('OTel compat shim — edge cases', () => {
  let native: ReturnType<typeof makeFakeNative>;

  beforeEach(async () => {
    native = makeFakeNative();
    __setNativeForTesting(native);
    await Dash0Mobile.start({
      serviceName: 'test',
      endpoint: 'https://collector.example.com:4317',
    });
  });

  afterEach(async () => {
    await Dash0Mobile.shutdown();
    __resetForTesting();
  });

  it('span kind 0 (INTERNAL)', async () => {
    const tracer = otel.trace.getTracer('t');
    tracer.startSpan('s', { kind: 0 }).end();
    await Dash0Mobile.flushWindow(0);
    const start = native.emitted.find(
      (p): p is SpanStartPayload => p.kind === 'spanStart',
    );
    expect(start?.spanKind).toBe('INTERNAL');
  });

  it('span kind 1 (SERVER)', async () => {
    const tracer = otel.trace.getTracer('t');
    tracer.startSpan('s', { kind: 1 }).end();
    await Dash0Mobile.flushWindow(0);
    const start = native.emitted.find(
      (p): p is SpanStartPayload => p.kind === 'spanStart',
    );
    expect(start?.spanKind).toBe('SERVER');
  });

  it('span kind 2 (CLIENT)', async () => {
    const tracer = otel.trace.getTracer('t');
    tracer.startSpan('s', { kind: 2 }).end();
    await Dash0Mobile.flushWindow(0);
    const start = native.emitted.find(
      (p): p is SpanStartPayload => p.kind === 'spanStart',
    );
    expect(start?.spanKind).toBe('CLIENT');
  });

  it('span kind 3 (PRODUCER)', async () => {
    const tracer = otel.trace.getTracer('t');
    tracer.startSpan('s', { kind: 3 }).end();
    await Dash0Mobile.flushWindow(0);
    const start = native.emitted.find(
      (p): p is SpanStartPayload => p.kind === 'spanStart',
    );
    expect(start?.spanKind).toBe('PRODUCER');
  });

  it('span kind 4 (CONSUMER)', async () => {
    const tracer = otel.trace.getTracer('t');
    tracer.startSpan('s', { kind: 4 }).end();
    await Dash0Mobile.flushWindow(0);
    const start = native.emitted.find(
      (p): p is SpanStartPayload => p.kind === 'spanStart',
    );
    expect(start?.spanKind).toBe('CONSUMER');
  });

  it('out-of-range span kind falls back to INTERNAL', async () => {
    const tracer = otel.trace.getTracer('t');
    // 99 is not a valid OTel SpanKind — shim maps unknown → INTERNAL.
    tracer.startSpan('s', { kind: 99 as unknown as number }).end();
    await Dash0Mobile.flushWindow(0);
    const start = native.emitted.find(
      (p): p is SpanStartPayload => p.kind === 'spanStart',
    );
    expect(start?.spanKind).toBe('INTERNAL');
  });

  it('missing options uses INTERNAL kind', async () => {
    const tracer = otel.trace.getTracer('t');
    tracer.startSpan('s').end();
    await Dash0Mobile.flushWindow(0);
    const start = native.emitted.find(
      (p): p is SpanStartPayload => p.kind === 'spanStart',
    );
    expect(start?.spanKind).toBe('INTERNAL');
  });

  it('meter.createGauge(name).record(value) forwards as gauge metric', async () => {
    const meter = otel.metrics.getMeter('m');
    const gauge = meter.createGauge('cpu.temp');
    gauge.record(42.5, { region: 'us-east' });
    await Dash0Mobile.flushWindow(0);

    const metric = native.emitted.find(
      (p): p is MetricPayload => p.kind === 'metric' && p.name === 'cpu.temp',
    );
    expect(metric?.instrumentType).toBe('gauge');
    expect(metric?.value).toBe(42.5);
    expect(metric?.attributes.region).toBe('us-east');
  });

  it('multiple independent tracers from different instrumentation names', async () => {
    const t1 = otel.trace.getTracer('lib-1');
    const t2 = otel.trace.getTracer('lib-2');
    t1.startSpan('s1').end();
    t2.startSpan('s2').end();
    await Dash0Mobile.flushWindow(0);

    const starts = native.emitted.filter(
      (p): p is SpanStartPayload => p.kind === 'spanStart',
    );
    expect(starts.map(s => s.name)).toEqual(['s1', 's2']);
  });

  it('logger.emit with no body uses a safe fallback name', async () => {
    const logger = otel.logs.getLogger('lib');
    logger.emit({ severityNumber: 9, attributes: { k: 'v' } });
    await Dash0Mobile.flushWindow(0);

    const log = native.emitted.find(
      (p): p is LogPayload => p.kind === 'log',
    );
    expect(log?.name).toBe('log'); // the shim default
    expect(log?.attributes.k).toBe('v');
  });

  it('logger.emit with no severity defaults to INFO (9)', async () => {
    const logger = otel.logs.getLogger('lib');
    logger.emit({ body: 'some.event', attributes: {} });
    await Dash0Mobile.flushWindow(0);

    const log = native.emitted.find(
      (p): p is LogPayload => p.kind === 'log' && p.name === 'some.event',
    );
    expect(log?.severity).toBe(9);
  });

  it('counter.add with zero is still forwarded (value:0 not dropped)', async () => {
    const meter = otel.metrics.getMeter('m');
    const counter = meter.createCounter('noop.counter');
    counter.add(0);
    await Dash0Mobile.flushWindow(0);

    const metric = native.emitted.find(
      (p): p is MetricPayload => p.kind === 'metric' && p.name === 'noop.counter',
    );
    expect(metric?.value).toBe(0);
  });
});
