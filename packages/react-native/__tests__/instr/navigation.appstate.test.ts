/**
 * Verifies the AppState-background span-closing path added to the React
 * Navigation instrumentation: a page span opened on the visible screen must
 * be ended when the app backgrounds (so durations aren't minutes/never), the
 * next foreground must re-open a span, and the AppState listener must be
 * removed on uninstall (no leak).
 */

// Route react-native through the shared mock factory so this suite cannot
// collide with the Android XHR-gate suite over the worker's virtual-mock
// registry. The mock records the AppState listener into __rnMockState; we read
// it back to drive background/foreground transitions.
jest.mock('react-native', () => require('../helpers/reactNativeMock'));

import {
  Dash0Mobile,
  __setNativeForTesting,
  __resetForTesting,
} from '../../src';
import type {
  BridgePayload,
  NativeDash0MobileModule,
  SpanEndPayload,
  SpanStartPayload,
} from '../../src/bridge/types';
import { installReactNavigationInstrumentation } from '../../src/instrumentation/navigation';

// Pull the mock state from the SAME mocked module instance the SDK resolves via
// `require('react-native')` — a direct relative import would be a different
// module instance, so the recorded listener would never be visible here.
const { __rnMockState, resetReactNativeMock } =
  jest.requireMock('react-native') as typeof import('../helpers/reactNativeMock');

function makeNavRef() {
  const listeners = new Set<() => void>();
  let current: { name: string } | null = null;
  return {
    addListener(_type: string, listener: () => void) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    getCurrentRoute() {
      return current ?? undefined;
    },
    _set(name: string) {
      current = { name };
      listeners.forEach(l => l());
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
    async startJourney() {
      return 'mock-journey-id';
    },
    async endJourney() {},
    async captureScreenshot() {},
    async captureWireframe() {},
  };
}

describe('navigation AppState background-end', () => {
  let native: ReturnType<typeof makeFakeNative>;
  let nav: ReturnType<typeof makeNavRef>;
  let uninstall: (() => void) | null = null;

  beforeEach(async () => {
    resetReactNativeMock();
    native = makeFakeNative();
    __setNativeForTesting(native);
    await Dash0Mobile.start({
      serviceName: 'test-rn',
      endpoint: 'https://collector.example.com:4317',
    });
    nav = makeNavRef();
  });

  afterEach(async () => {
    if (uninstall) uninstall();
    uninstall = null;
    await Dash0Mobile.shutdown();
    __resetForTesting();
  });

  const pageEnds = () =>
    native.emitted.filter((p): p is SpanEndPayload => p.kind === 'spanEnd');
  const pageStarts = () =>
    native.emitted.filter(
      (p): p is SpanStartPayload =>
        p.kind === 'spanStart' && p.name.startsWith('page.'),
    );

  it('registers an AppState change listener on install', () => {
    uninstall = installReactNavigationInstrumentation(nav);
    expect(typeof __rnMockState.appStateListener).toBe('function');
  });

  it('ends the active page span when the app backgrounds, and re-opens on next foreground route', async () => {
    uninstall = installReactNavigationInstrumentation(nav);
    nav._set('Home');
    await Dash0Mobile.flushWindow(0);
    expect(pageStarts()).toHaveLength(1);

    // Background → span must end.
    __rnMockState.appStateListener!('background');
    await Dash0Mobile.flushWindow(0);
    expect(pageEnds()).toHaveLength(1);

    // Foregrounding back to the SAME route must re-open a fresh span
    // (currentName was reset), not be suppressed as a duplicate.
    nav._set('Home');
    await Dash0Mobile.flushWindow(0);
    expect(pageStarts()).toHaveLength(2);
  });

  it('treats inactive like background', async () => {
    uninstall = installReactNavigationInstrumentation(nav);
    nav._set('Cart');
    __rnMockState.appStateListener!('inactive');
    await Dash0Mobile.flushWindow(0);
    expect(pageEnds()).toHaveLength(1);
  });

  it('does not end a span on returning to active', async () => {
    uninstall = installReactNavigationInstrumentation(nav);
    nav._set('Home');
    __rnMockState.appStateListener!('active');
    await Dash0Mobile.flushWindow(0);
    expect(pageEnds()).toHaveLength(0); // still open
  });

  it('removes the AppState listener on uninstall (no leak)', () => {
    const un = installReactNavigationInstrumentation(nav);
    un();
    expect(__rnMockState.appStateRemove).toHaveBeenCalledTimes(1);
  });
});
