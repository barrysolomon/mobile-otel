/**
 * Minimal @opentelemetry/api compatibility surface.
 *
 * Exposes `otel.trace.getTracer`, `otel.logs.getLogger`, `otel.metrics.getMeter`
 * backed by our bridge. Scope is the subset of the API third-party JS libraries
 * actually use at runtime — this is NOT a drop-in for the full upstream API.
 *
 * Why a shim instead of the real package: adding `@opentelemetry/api` as a
 * runtime dep pulls version-matching and global-registration concerns into
 * every RN app that uses us. The shim keeps our bundle small and our bridge
 * the sole source of truth.
 */

import { Dash0Mobile } from './index';
import type { SpanHandle } from './index';
import type { Attributes, SeverityNumber, SpanKind } from './bridge/types';

// ─── Tracer ──────────────────────────────────────────────────────────────────

interface SpanStatus {
  code: number; // 0 UNSET, 1 OK, 2 ERROR
  message?: string;
}

interface SpanOptions {
  kind?: number; // OTel SpanKind enum (0-5). We only map 0 INTERNAL / 3 CLIENT / 4 PRODUCER / 5 CONSUMER.
  attributes?: Attributes;
}

interface CompatSpan {
  setAttribute(key: string, value: string | number | boolean): void;
  setStatus(status: SpanStatus): void;
  end(): void;
}

interface CompatTracer {
  startSpan(name: string, options?: SpanOptions): CompatSpan;
}

const OTEL_SPAN_KIND_TO_OURS: Record<number, SpanKind> = {
  0: 'INTERNAL',
  1: 'SERVER',
  2: 'CLIENT',
  3: 'PRODUCER',
  4: 'CONSUMER',
};

function mapStatus(code: number): 'OK' | 'ERROR' {
  return code === 2 ? 'ERROR' : 'OK';
}

function wrapSpan(handle: SpanHandle): CompatSpan {
  return {
    setAttribute(key, value) {
      handle.setAttribute(key, value);
    },
    setStatus(status) {
      handle.setStatus(mapStatus(status.code), status.message);
    },
    end() {
      handle.end();
    },
  };
}

function getTracer(_name: string, _version?: string): CompatTracer {
  return {
    startSpan(name, options) {
      const kind = OTEL_SPAN_KIND_TO_OURS[options?.kind ?? 0] ?? 'INTERNAL';
      const handle = Dash0Mobile.startSpan(name, options?.attributes, kind);
      return wrapSpan(handle);
    },
  };
}

// ─── Logger ──────────────────────────────────────────────────────────────────

interface LogRecord {
  body?: string;
  severityNumber?: SeverityNumber;
  attributes?: Attributes;
}

interface CompatLogger {
  emit(record: LogRecord): void;
}

function getLogger(_name: string, _version?: string): CompatLogger {
  return {
    emit(record) {
      const name = record.body ?? 'log';
      Dash0Mobile.log(name, record.attributes ?? {}, record.severityNumber ?? 9);
    },
  };
}

// ─── Meter ──────────────────────────────────────────────────────────────────

interface CompatCounter {
  add(value: number, attributes?: Attributes): void;
}

interface CompatHistogram {
  record(value: number, attributes?: Attributes): void;
}

interface CompatGauge {
  record(value: number, attributes?: Attributes): void;
}

interface CompatMeter {
  createCounter(name: string): CompatCounter;
  createHistogram(name: string): CompatHistogram;
  createGauge(name: string): CompatGauge;
}

function getMeter(_name: string, _version?: string): CompatMeter {
  return {
    createCounter(name) {
      return {
        add(value, attributes) {
          Dash0Mobile.recordMetric(name, value, 'counter', attributes ?? {});
        },
      };
    },
    createHistogram(name) {
      return {
        record(value, attributes) {
          Dash0Mobile.recordMetric(name, value, 'histogram', attributes ?? {});
        },
      };
    },
    createGauge(name) {
      return {
        record(value, attributes) {
          Dash0Mobile.recordMetric(name, value, 'gauge', attributes ?? {});
        },
      };
    },
  };
}

// ─── Namespace ──────────────────────────────────────────────────────────────

export const otel = {
  trace: { getTracer },
  logs: { getLogger },
  metrics: { getMeter },
};

export type {
  CompatSpan,
  CompatTracer,
  CompatLogger,
  CompatMeter,
  CompatCounter,
  CompatHistogram,
  CompatGauge,
};
