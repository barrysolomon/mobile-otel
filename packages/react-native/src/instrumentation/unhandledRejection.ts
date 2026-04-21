/**
 * Unhandled promise rejection auto-instrumentation for React Native.
 *
 * Shares a dedupe window with the sync error handler so a thrown Error and
 * its rejected-promise twin collapse to one log — matches Android's
 * ErrorInstrumentation dedupe semantics.
 */

import { Dash0Mobile } from '../index';

const DEDUPE_WINDOW_MS = 5 * 60 * 1000;
const SEVERITY_ERROR = 17 as const;

type RejectionEvent = { reason: unknown };
type RejectionListener = (ev: RejectionEvent) => void;

interface GlobalWithEventTarget {
  addEventListener?: (type: string, listener: RejectionListener) => void;
  removeEventListener?: (type: string, listener: RejectionListener) => void;
}

function dedupeKey(reason: unknown): string {
  if (reason instanceof Error) {
    return `${reason.name ?? 'Error'}::${reason.message ?? ''}`;
  }
  return `UnhandledRejection::${String(reason)}`;
}

export function installUnhandledRejectionInstrumentation(): () => void {
  const g = globalThis as unknown as GlobalWithEventTarget;
  if (typeof g.addEventListener !== 'function' || typeof g.removeEventListener !== 'function') {
    // Environment doesn't expose a global event target (most bare Node contexts).
    return () => {};
  }

  const recentlySeen = new Map<string, number>();

  const handler: RejectionListener = (ev) => {
    const reason = ev?.reason;
    const key = dedupeKey(reason);
    const now = Date.now();
    const lastAt = recentlySeen.get(key);
    if (lastAt !== undefined && now - lastAt < DEDUPE_WINDOW_MS) return;
    recentlySeen.set(key, now);

    if (reason instanceof Error) {
      Dash0Mobile.log(
        'app.error',
        {
          'exception.type': reason.name ?? 'Error',
          'exception.message': reason.message ?? String(reason),
          'exception.stacktrace': reason.stack ?? '',
          'exception.escaped': true,
        },
        SEVERITY_ERROR,
      );
    } else {
      Dash0Mobile.log(
        'app.error',
        {
          'exception.type': 'UnhandledRejection',
          'exception.message': String(reason),
          'exception.escaped': true,
        },
        SEVERITY_ERROR,
      );
    }
  };

  g.addEventListener('unhandledrejection', handler);

  return function uninstall() {
    g.removeEventListener?.('unhandledrejection', handler);
  };
}
