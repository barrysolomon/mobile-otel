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
        instance.__dash0_method = method.toUpperCase();
        instance.__dash0_url = typeof url === 'string' ? url : url.toString();
        // @ts-expect-error — forwarding variadic
        return originalOpen(method, url, ...rest);
      };

      const originalSend = instance.send.bind(instance);
      (instance as unknown as { send: XMLHttpRequest['send'] }).send = function send(
        body?: Document | XMLHttpRequestBodyInit | null,
      ) {
        const url = instance.__dash0_url ?? '';
        const host = hostFromUrl(url);
        if (!host || !ignored.has(host)) {
          const method = instance.__dash0_method ?? 'GET';
          instance.__dash0_span = Dash0Mobile.startSpan(
            `${method} ${host ?? 'unknown'}`,
            {
              'http.request.method': method,
              'url.full': url,
              ...(host ? { 'server.address': host } : {}),
            },
            'CLIENT',
          );

          const onError = () => {
            instance.__dash0_failed = true;
          };
          const onLoadEnd = () => {
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
          };

          instance.addEventListener('error', onError);
          instance.addEventListener('loadend', onLoadEnd);
        }
        return originalSend(body);
      };

      return instance;
    },
  });

  (globalThis as unknown as { XMLHttpRequest: XhrCtor }).XMLHttpRequest =
    wrapped as XhrCtor;

  return function uninstall() {
    const current = (globalThis as unknown as { XMLHttpRequest?: XhrCtor })
      .XMLHttpRequest;
    if (current === wrapped) {
      (globalThis as unknown as { XMLHttpRequest?: XhrCtor }).XMLHttpRequest =
        OriginalXHR;
    }
  };
}
