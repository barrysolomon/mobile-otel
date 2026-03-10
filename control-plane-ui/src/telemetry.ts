// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

import { WebTracerProvider } from '@opentelemetry/sdk-trace-web'
import { LoggerProvider, SimpleLogRecordProcessor } from '@opentelemetry/sdk-logs'
import { OTLPLogExporter } from '@opentelemetry/exporter-logs-otlp-http'
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http'
import { BatchSpanProcessor } from '@opentelemetry/sdk-trace-web'
import { Resource } from '@opentelemetry/resources'
import { ATTR_SERVICE_NAME, ATTR_SERVICE_VERSION } from '@opentelemetry/semantic-conventions'
import { registerInstrumentations } from '@opentelemetry/instrumentation'
import { FetchInstrumentation } from '@opentelemetry/instrumentation-fetch'
import { XMLHttpRequestInstrumentation } from '@opentelemetry/instrumentation-xml-http-request'
import { SeverityNumber } from '@opentelemetry/api-logs'

const ENDPOINT = 'https://ingress.us-west-2.aws.dash0.com'
const AUTH_TOKEN = 'auth_fI0GuunaYYbw8u0n0iyFAC4Wt2FMf0jh'
const DATASET = 'otel-mobile'

const headers = {
  Authorization: `Bearer ${AUTH_TOKEN}`,
  'Dash0-Dataset': DATASET,
}

const resource = new Resource({
  [ATTR_SERVICE_NAME]: 'mobile-otel-control-plane',
  [ATTR_SERVICE_VERSION]: '1.0.0',
  'deployment.environment': 'development',
})

// ── Traces ────────────────────────────────────────────────────────────────────

const traceExporter = new OTLPTraceExporter({
  url: `${ENDPOINT}/v1/traces`,
  headers,
})

const tracerProvider = new WebTracerProvider({ resource })
tracerProvider.addSpanProcessor(new BatchSpanProcessor(traceExporter))
tracerProvider.register()

// ── Logs ──────────────────────────────────────────────────────────────────────

const logExporter = new OTLPLogExporter({
  url: `${ENDPOINT}/v1/logs`,
  headers,
})

const loggerProvider = new LoggerProvider({ resource })
loggerProvider.addLogRecordProcessor(new SimpleLogRecordProcessor(logExporter))

const logger = loggerProvider.getLogger('control-plane-ui')

// ── Console capture ───────────────────────────────────────────────────────────

const severityMap: Record<string, SeverityNumber> = {
  warn:  SeverityNumber.WARN,
  error: SeverityNumber.ERROR,
  info:  SeverityNumber.INFO,
  debug: SeverityNumber.DEBUG,
}

function patchConsole(level: 'warn' | 'error' | 'info' | 'debug') {
  const original = console[level].bind(console)
  console[level] = (...args: unknown[]) => {
    original(...args)
    const body = args.map(a => (typeof a === 'string' ? a : JSON.stringify(a))).join(' ')
    logger.emit({
      severityNumber: severityMap[level],
      severityText: level.toUpperCase(),
      body,
      attributes: { 'log.source': 'console', 'log.level': level },
    })
  }
}

patchConsole('warn')
patchConsole('error')
patchConsole('info')
patchConsole('debug')

// ── Unhandled errors & promise rejections ─────────────────────────────────────

window.addEventListener('error', (event) => {
  logger.emit({
    severityNumber: SeverityNumber.ERROR,
    severityText: 'ERROR',
    body: event.message,
    attributes: {
      'exception.type': event.error?.name ?? 'Error',
      'exception.message': event.message,
      'exception.stacktrace': event.error?.stack ?? '',
      'code.filepath': event.filename,
      'code.lineno': event.lineno,
      'code.colno': event.colno,
    },
  })
})

window.addEventListener('unhandledrejection', (event) => {
  const reason = event.reason
  const message = reason instanceof Error ? reason.message : String(reason)
  logger.emit({
    severityNumber: SeverityNumber.ERROR,
    severityText: 'ERROR',
    body: `Unhandled promise rejection: ${message}`,
    attributes: {
      'exception.type': reason instanceof Error ? reason.name : 'UnhandledRejection',
      'exception.message': message,
      'exception.stacktrace': reason instanceof Error ? (reason.stack ?? '') : '',
    },
  })
})

// ── HTTP instrumentation (fetch + XHR) ────────────────────────────────────────

registerInstrumentations({
  instrumentations: [
    new FetchInstrumentation({
      propagateTraceHeaderCorsUrls: [/localhost:8080/, /dash0\.com/],
    }),
    new XMLHttpRequestInstrumentation({
      propagateTraceHeaderCorsUrls: [/localhost:8080/, /dash0\.com/],
    }),
  ],
})

export { logger, loggerProvider, tracerProvider }
