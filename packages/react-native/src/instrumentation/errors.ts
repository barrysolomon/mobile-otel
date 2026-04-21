/**
 * Error auto-instrumentation for React Native.
 *
 * Chains our handler into RN's `ErrorUtils.setGlobalHandler`, emits an
 * `app.error` log with OTel `exception.*` semconv attributes, then delegates
 * to the previous handler so Sentry/Bugsnag/redbox keep working.
 *
 * Deduplication: identical (type + message) errors inside a 5-minute window
 * collapse to a single log, matching the Android ErrorInstrumentation
 * policy (loops that throw on every frame shouldn't DoS the collector).
 */

import { Dash0Mobile } from '../index';

export const DEDUPE_WINDOW_MS = 5 * 60 * 1000;
const SEVERITY_ERROR = 17 as const;
const SEVERITY_FATAL = 21 as const;

type ErrorHandler = (error: Error, isFatal: boolean) => void;

interface ErrorUtilsLike {
  getGlobalHandler(): ErrorHandler;
  setGlobalHandler(handler: ErrorHandler): void;
}

function resolveErrorUtils(): ErrorUtilsLike | null {
  const g = globalThis as unknown as { ErrorUtils?: ErrorUtilsLike };
  return g.ErrorUtils ?? null;
}

function dedupeKey(err: Error): string {
  const type = err?.name ?? 'Error';
  const message = err?.message ?? '';
  return `${type}::${message}`;
}

export function installErrorInstrumentation(): () => void {
  const errorUtils = resolveErrorUtils();
  if (!errorUtils) {
    // Non-RN environment — caller is probably in Jest/SSR. Return a no-op
    // uninstaller so callers don't need to gate on platform.
    return () => {};
  }

  const previous = errorUtils.getGlobalHandler();
  const recentlySeen = new Map<string, number>();

  const handler: ErrorHandler = (error, isFatal) => {
    const key = dedupeKey(error);
    const now = Date.now();
    const lastAt = recentlySeen.get(key);
    if (lastAt === undefined || now - lastAt >= DEDUPE_WINDOW_MS) {
      recentlySeen.set(key, now);
      Dash0Mobile.log(
        'app.error',
        {
          'exception.type': error?.name ?? 'Error',
          'exception.message': error?.message ?? String(error),
          'exception.stacktrace': error?.stack ?? '',
        },
        isFatal ? SEVERITY_FATAL : SEVERITY_ERROR,
      );
    }
    // Always chain — our capture must never swallow crashes from other
    // reporters or the default redbox.
    try {
      previous(error, isFatal);
    } catch {
      // The previous handler throwing would normally be catastrophic, but
      // we've already emitted the log — nothing safe to do here.
    }
  };

  errorUtils.setGlobalHandler(handler);

  return function uninstall() {
    // Best-effort restore. If someone chained on top of us we can't cleanly
    // unwind — restore `previous` and accept the break (matches how Sentry
    // handles the same race).
    if (errorUtils.getGlobalHandler() === handler) {
      errorUtils.setGlobalHandler(previous);
    }
  };
}
