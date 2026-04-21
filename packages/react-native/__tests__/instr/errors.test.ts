/**
 * RN-031 test for error auto-instrumentation.
 *
 * Wraps RN's `ErrorUtils.setGlobalHandler` to capture uncaught errors and
 * emit ERROR-severity logs with OTel `exception.*` semconv attributes.
 *
 * Two important invariants (both asserted below):
 *   1. The previous handler is chained through — other tools (Sentry,
 *      Bugsnag, the default RN redbox) must keep working.
 *   2. Identical errors inside a 5-minute window are deduplicated to avoid
 *      flooding on tight loops (mirrors Android ErrorInstrumentation).
 */

import {
  Dash0Mobile,
  __setNativeForTesting,
  __resetForTesting,
} from '../../src';
import type {
  BridgePayload,
  LogPayload,
  NativeDash0MobileModule,
} from '../../src/bridge/types';
import {
  installErrorInstrumentation,
  DEDUPE_WINDOW_MS,
} from '../../src/instrumentation/errors';

type ErrorUtilsLike = {
  getGlobalHandler: () => (e: Error, isFatal: boolean) => void;
  setGlobalHandler: (h: (e: Error, isFatal: boolean) => void) => void;
};

function makeFakeNative(): NativeDash0MobileModule & {emitted: BridgePayload[]} {
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

function installFakeErrorUtils(): {
  errorUtils: ErrorUtilsLike;
  previousHandler: jest.Mock;
  trigger: (e: Error, fatal?: boolean) => void;
} {
  const previousHandler = jest.fn();
  let current: (e: Error, fatal: boolean) => void = previousHandler;
  const errorUtils: ErrorUtilsLike = {
    getGlobalHandler: () => current,
    setGlobalHandler: h => {
      current = h;
    },
  };
  (globalThis as unknown as {ErrorUtils: ErrorUtilsLike}).ErrorUtils =
    errorUtils;
  return {
    errorUtils,
    previousHandler,
    trigger: (e, fatal = false) => current(e, fatal),
  };
}

describe('error auto-instrumentation', () => {
  let uninstall: (() => void) | null = null;
  let native: ReturnType<typeof makeFakeNative>;
  let fakeEU: ReturnType<typeof installFakeErrorUtils>;

  beforeEach(async () => {
    native = makeFakeNative();
    __setNativeForTesting(native);
    await Dash0Mobile.start({
      serviceName: 'test-rn',
      endpoint: 'https://collector.example.com:4317',
    });
    fakeEU = installFakeErrorUtils();
  });

  afterEach(async () => {
    if (uninstall) {
      uninstall();
      uninstall = null;
    }
    delete (globalThis as unknown as {ErrorUtils?: ErrorUtilsLike}).ErrorUtils;
    const g = globalThis as unknown as {
      removeAllListeners?: (evt: string) => void;
    };
    g.removeAllListeners?.('unhandledrejection');
    await Dash0Mobile.shutdown();
    __resetForTesting();
  });

  function findErrorLogs(): LogPayload[] {
    return native.emitted.filter(
      (p): p is LogPayload => p.kind === 'log' && p.name === 'app.error',
    );
  }

  it('captures thrown errors via ErrorUtils.setGlobalHandler and emits ERROR-severity log', async () => {
    uninstall = installErrorInstrumentation();

    const err = new TypeError('boom');
    fakeEU.trigger(err, false);
    await Dash0Mobile.flushWindow(0);

    const logs = findErrorLogs();
    expect(logs).toHaveLength(1);
    expect(logs[0].severity).toBe(17); // ERROR
    expect(logs[0].attributes['exception.type']).toBe('TypeError');
    expect(logs[0].attributes['exception.message']).toBe('boom');
    expect(typeof logs[0].attributes['exception.stacktrace']).toBe('string');
  });

  it('emits FATAL severity when isFatal is true', async () => {
    uninstall = installErrorInstrumentation();

    fakeEU.trigger(new Error('crash'), true);
    await Dash0Mobile.flushWindow(0);

    const logs = findErrorLogs();
    expect(logs).toHaveLength(1);
    expect(logs[0].severity).toBe(21); // FATAL
  });

  it('chains through to the previous ErrorUtils handler (does not replace it)', async () => {
    uninstall = installErrorInstrumentation();

    const err = new Error('downstream');
    fakeEU.trigger(err, false);

    expect(fakeEU.previousHandler).toHaveBeenCalledWith(err, false);
  });

  it('deduplicates identical errors within the dedupe window', async () => {
    uninstall = installErrorInstrumentation();

    const err = new TypeError('same');
    fakeEU.trigger(err, false);
    fakeEU.trigger(err, false);
    fakeEU.trigger(err, false);
    await Dash0Mobile.flushWindow(0);

    expect(findErrorLogs()).toHaveLength(1);
    expect(DEDUPE_WINDOW_MS).toBe(5 * 60 * 1000);
  });

  it('uninstall restores the previous handler', () => {
    const beforeHandler = fakeEU.errorUtils.getGlobalHandler();
    const unwrap = installErrorInstrumentation();
    expect(fakeEU.errorUtils.getGlobalHandler()).not.toBe(beforeHandler);
    unwrap();
    expect(fakeEU.errorUtils.getGlobalHandler()).toBe(beforeHandler);
  });
});
