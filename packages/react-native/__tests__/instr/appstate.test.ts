/**
 * RN-032 — AppState foreground/background lifecycle logs.
 *
 * Contract:
 *   - `active`        → log `app.foreground`
 *   - `background`    → log `app.background`
 *   - `inactive`      → log `app.inactive` (iOS transient state, rare)
 *
 * We emit INFO severity (9) since these are normal lifecycle events, not
 * problems. Native SDKs already emit their own platform-native lifecycle
 * telemetry; the RN JS layer adds an additional, app-logic-centric signal
 * so JS instrumentation can correlate user interactions with lifecycle.
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
import { installAppStateInstrumentation } from '../../src/instrumentation/appstate';

type AppStateValue = 'active' | 'background' | 'inactive' | 'unknown';

interface FakeAppState {
  addEventListener(
    type: 'change',
    listener: (state: AppStateValue) => void,
  ): { remove: () => void };
  _fire(state: AppStateValue): void;
}

function makeFakeAppState(): FakeAppState {
  const listeners = new Set<(state: AppStateValue) => void>();
  return {
    addEventListener(_type, listener) {
      listeners.add(listener);
      return {
        remove: () => listeners.delete(listener),
      };
    },
    _fire(state) {
      listeners.forEach(l => l(state));
    },
  };
}

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

describe('AppState auto-instrumentation', () => {
  let native: ReturnType<typeof makeFakeNative>;
  let appState: FakeAppState;
  let uninstall: (() => void) | null;

  beforeEach(async () => {
    native = makeFakeNative();
    __setNativeForTesting(native);
    await Dash0Mobile.start({
      serviceName: 'test-rn',
      endpoint: 'https://collector.example.com:4317',
    });
    appState = makeFakeAppState();
    uninstall = null;
  });

  afterEach(async () => {
    if (uninstall) uninstall();
    await Dash0Mobile.shutdown();
    __resetForTesting();
  });

  function logsNamed(name: string): LogPayload[] {
    return native.emitted.filter(
      (p): p is LogPayload => p.kind === 'log' && p.name === name,
    );
  }

  // Passing a test double bypasses the production deferral path — tests
  // want deterministic synchronous subscription. The deferred path is
  // exercised by a separate test below.

  it('emits app.foreground when state goes to active', async () => {
    uninstall = installAppStateInstrumentation(appState);
    appState._fire('active');
    await Dash0Mobile.flushWindow(0);

    const logs = logsNamed('app.foreground');
    expect(logs).toHaveLength(1);
    expect(logs[0].severity).toBe(9);
    expect(logs[0].attributes['app.state']).toBe('active');
  });

  it('emits app.background when state goes to background', async () => {
    uninstall = installAppStateInstrumentation(appState);
    appState._fire('background');
    await Dash0Mobile.flushWindow(0);

    const logs = logsNamed('app.background');
    expect(logs).toHaveLength(1);
    expect(logs[0].attributes['app.state']).toBe('background');
  });

  it('emits app.inactive for iOS inactive transient state', async () => {
    uninstall = installAppStateInstrumentation(appState);
    appState._fire('inactive');
    await Dash0Mobile.flushWindow(0);

    expect(logsNamed('app.inactive')).toHaveLength(1);
  });

  it('does not emit duplicates when fired with the same state twice in a row', async () => {
    uninstall = installAppStateInstrumentation(appState);
    appState._fire('background');
    appState._fire('background');
    await Dash0Mobile.flushWindow(0);

    expect(logsNamed('app.background')).toHaveLength(1);
  });

  it('uninstall removes the subscription (test double = sync path)', async () => {
    const un = installAppStateInstrumentation(appState);
    un();
    appState._fire('active');
    await Dash0Mobile.flushWindow(0);

    expect(logsNamed('app.foreground')).toHaveLength(0);
  });

  it('defers resolution when no appState is passed (production path) and uninstall before timer fires is clean', async () => {
    // Call with no argument → production defer path. Since no `react-native`
    // is mocked in this Jest env, resolveAppState() returns null inside the
    // setTimeout. The install must not throw either at call time or in the
    // deferred callback, and uninstall must cancel the pending timer if
    // called before it fires.
    const un = installAppStateInstrumentation();
    un(); // immediate — cancels deferred work
    // Let any scheduled setTimeout fire; nothing should blow up.
    await new Promise<void>(resolve => setTimeout(resolve, 0));
    expect(true).toBe(true);
  });

  it('returns no-op uninstaller when appState is not available', () => {
    const un = installAppStateInstrumentation(null);
    expect(typeof un).toBe('function');
    un();
  });
});
