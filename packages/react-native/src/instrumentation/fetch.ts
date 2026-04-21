/**
 * fetch() auto-instrumentation for React Native.
 *
 * Wraps the global fetch so every HTTP call produces a CLIENT span with
 * OTel HTTP semconv v1.23+ attributes. Matches attribute names emitted by
 * the iOS URLProtocol interceptor and the Android OkHttp interceptor so a
 * single Dash0 filter surfaces network spans across all three platforms.
 *
 * Self-capture avoidance: pass the collector host via `ignoredHosts` so
 * OTLP export requests don't generate spans that then need to be exported.
 */

import { Dash0Mobile } from '../index';

export interface FetchInstrumentationConfig {
  ignoredHosts?: readonly string[];
}

type FetchFn = typeof globalThis.fetch;

function hostFromUrl(url: string): string | null {
  const match = /^[a-z][a-z0-9+.-]*:\/\/([^/:?#]+)/i.exec(url);
  return match ? match[1].toLowerCase() : null;
}

function resolveUrl(input: RequestInfo | URL): string {
  if (typeof input === 'string') return input;
  if (input instanceof URL) return input.toString();
  return (input as Request).url;
}

function resolveMethod(input: RequestInfo | URL, init?: RequestInit): string {
  const m = init?.method ?? (input as Request)?.method ?? 'GET';
  return m.toUpperCase();
}

export function installFetchInstrumentation(
  config: FetchInstrumentationConfig = {},
): () => void {
  const original: FetchFn = globalThis.fetch;
  const ignored = new Set(
    (config.ignoredHosts ?? []).map(h => h.toLowerCase()),
  );

  const wrapped: FetchFn = async (input, init) => {
    const url = resolveUrl(input);
    const host = hostFromUrl(url);
    if (host && ignored.has(host)) {
      return original(input, init);
    }

    const method = resolveMethod(input, init);
    const handle = Dash0Mobile.startSpan(
      method,
      {
        'http.request.method': method,
        'url.full': url,
        ...(host ? { 'server.address': host } : {}),
      },
      'CLIENT',
    );

    try {
      const response = await original(input, init);
      const status = (response as Response | undefined)?.status;
      if (typeof status === 'number') {
        handle.setAttribute('http.response.status_code', status);
        if (status >= 400) {
          handle.setStatus('ERROR', `HTTP ${status}`);
        } else {
          handle.setStatus('OK');
        }
      } else {
        handle.setStatus('OK');
      }
      return response;
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      handle.setStatus('ERROR', message);
      throw err;
    } finally {
      handle.end();
    }
  };

  globalThis.fetch = wrapped;

  return function uninstall() {
    if (globalThis.fetch === wrapped) {
      globalThis.fetch = original;
    }
  };
}
