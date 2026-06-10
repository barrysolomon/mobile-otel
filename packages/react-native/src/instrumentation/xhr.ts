/**
 * XMLHttpRequest auto-instrumentation for React Native.
 *
 * RN's fetch is itself XHR-backed, so libraries that use XHR directly
 * (axios, apollo) bypass the fetch wrapper. This module replaces the
 * global `XMLHttpRequest` constructor with a Proxy that captures a
 * CLIENT span per request. Attribute shape matches the fetch wrapper so
 * Dash0 queries see a single consistent HTTP span schema.
 */

import { Dash0Mobile } from '../index';
import type { SpanHandle } from '../index';
import { sanitizeUrl } from '../redact';

export interface XhrInstrumentationConfig {
  ignoredHosts?: readonly string[];
}

const HOST_PATTERN = /^[a-z][a-z0-9+.-]*:\/\/([^/:?#]+)/i;

function hostFromUrl(url: string): string | null {
  const match = url.match(HOST_PATTERN);
  return match ? match[1].toLowerCase() : null;
}

type XhrCtor = typeof globalThis.XMLHttpRequest;

export function installXhrInstrumentation(
  config: XhrInstrumentationConfig = {},
): () => void {
  const OriginalXHR: XhrCtor | undefined = (
    globalThis as unknown as { XMLHttpRequest?: XhrCtor }
  ).XMLHttpRequest;

  if (!OriginalXHR) return () => {};

  const ignored = new Set(
    (config.ignoredHosts ?? []).map(h => h.toLowerCase()),
  );

  const wrapped = new Proxy(OriginalXHR, {
    construct(target, args, newTarget) {
      const instance = Reflect.construct(target, args, newTarget) as XMLHttpRequest & {
        __dash0_method?: string;
        __dash0_url?: string;
        __dash0_span?: SpanHandle;
        __dash0_failed?: boolean;
      };

      const originalOpen = instance.open.bind(instance);
      (instance as unknown as { open: XMLHttpRequest['open'] }).open = function open(
        method: string,
        url: string | URL,
        ...rest: unknown[]
      ) {
        try {
          instance.__dash0_method = method.toUpperCase();
          instance.__dash0_url = typeof url === 'string' ? url : url.toString();
        } catch {
          // Capturing request metadata must never block the real open().
        }
        // @ts-expect-error — forwarding variadic
        return originalOpen(method, url, ...rest);
      };

      const originalSend = instance.send.bind(instance);
      (instance as unknown as { send: XMLHttpRequest['send'] }).send = function send(
        body?: Document | XMLHttpRequestBodyInit | null,
      ) {
        // ALL telemetry setup is best-effort: a throw here must never stop
        // the host's real request from going out.
        try {
          const url = instance.__dash0_url ?? '';
          const host = hostFromUrl(url);
          if (!host || !ignored.has(host)) {
            const method = instance.__dash0_method ?? 'GET';
            instance.__dash0_span = Dash0Mobile.startSpan(
              `${method} ${host ?? 'unknown'}`,
              {
                'http.request.method': method,
                'url.full': sanitizeUrl(url),
                ...(host ? { 'server.address': host } : {}),
              },
              'CLIENT',
            );

            const onError = () => {
              instance.__dash0_failed = true;
            };
            const onLoadEnd = () => {
              try {
                const span = instance.__dash0_span;
                if (!span) return;
                if (instance.__dash0_failed) {
                  span.setStatus('ERROR', 'network error');
                } else {
                  const status = instance.status;
                  if (typeof status === 'number' && status > 0) {
                    span.setAttribute('http.response.status_code', status);
                    if (status >= 400) {
                      span.setStatus('ERROR', `HTTP ${status}`);
                    } else {
                      span.setStatus('OK');
                    }
                  } else {
                    span.setStatus('OK');
                  }
                }
                span.end();
                instance.__dash0_span = undefined;
              } catch {
                // Telemetry finalization failure must not surface to the host.
              }
            };

            instance.addEventListener('error', onError);
            instance.addEventListener('loadend', onLoadEnd);
          }
        } catch (telemetryErr) {
          // eslint-disable-next-line no-console
          console.warn?.('[@dash0/mobile] xhr instrumentation setup failed', telemetryErr);
        }
        return originalSend(body);
      };

      return instance;
    },
  });

  // Double-install guard: Fast Refresh / repeated start() would otherwise
  // stack Proxy wrappers and leak the original constructor.
  if ((OriginalXHR as { __dash0_installed?: boolean }).__dash0_installed) {
    return () => {};
  }
  (OriginalXHR as { __dash0_installed?: boolean }).__dash0_installed = true;

  (globalThis as unknown as { XMLHttpRequest: XhrCtor }).XMLHttpRequest =
    wrapped as XhrCtor;

  return function uninstall() {
    const current = (globalThis as unknown as { XMLHttpRequest?: XhrCtor })
      .XMLHttpRequest;
    if (current === wrapped) {
      (globalThis as unknown as { XMLHttpRequest?: XhrCtor }).XMLHttpRequest =
        OriginalXHR;
    }
    // Clear the guard so a later install() can re-instrument.
    delete (OriginalXHR as { __dash0_installed?: boolean }).__dash0_installed;
  };
}
