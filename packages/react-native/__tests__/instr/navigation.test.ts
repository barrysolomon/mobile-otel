/**
 * RN-033 — React Navigation screen tracking (opt-in).
 *
 * Caller passes the NavigationContainer's ref. The helper subscribes to the
 * container's 'state' event, reads the current route via getCurrentRoute(),
 * and emits:
 *   - a `ui.screen_view` log (name = route.name)
 *   - a `page.<route.name>` span that remains open until the next navigation
 *
 * This mirrors the Android `ScreenViewInstrumentation` / iOS
 * `ScreenViewInstrumentation` pattern — every platform's screen name space
 * should line up in Dash0 dashboards.
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
  SpanEndPayload,
  SpanStartPayload,
} from '../../src/bridge/types';
import { installReactNavigationInstrumentation } from '../../src/instrumentation/navigation';

type Listener = () => void;
interface FakeNavRef {
  _current: { name: string } | null;
  _listeners: Map<string, Set<Listener>>;
  addListener(type: string, listener: Listener): () => void;
  getCurrentRoute(): { name: string } | undefined;
  _set(name: string): void;
}

function makeNavRef(): FakeNavRef {
  const listeners = new Map<string, Set<Listener>>();
  return {
    _current: null,
    _listeners: listeners,
    addListener(type, listener) {
      if (!listeners.has(type)) listeners.set(type, new Set());
      listeners.get(type)!.add(listener);
      return () => listeners.get(type)?.delete(listener);
    },
    getCurrentRoute() {
      return this._current ?? undefined;
    },
    _set(name) {
      this._current = { name };
      listeners.get('state')?.forEach(l => l());
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

describe('React Navigation auto-instrumentation', () => {
  let native: ReturnType<typeof makeFakeNative>;
  let nav: FakeNavRef;
  let uninstall: (() => void) | null = null;

  beforeEach(async () => {
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

  function collect() {
    const screenLogs = native.emitted.filter(
      (p): p is LogPayload => p.kind === 'log' && p.name === 'ui.screen_view',
    );
    const pageStarts = native.emitted.filter(
      (p): p is SpanStartPayload =>
        p.kind === 'spanStart' && p.name.startsWith('page.'),
    );
    const pageEnds = native.emitted.filter(
      (p): p is SpanEndPayload => p.kind === 'spanEnd',
    );
    return { screenLogs, pageStarts, pageEnds };
  }

  it('emits ui.screen_view + page.<name> span on first navigation', async () => {
    uninstall = installReactNavigationInstrumentation(nav);
    nav._set('ProductList');
    await Dash0Mobile.flushWindow(0);

    const { screenLogs, pageStarts } = collect();
    expect(screenLogs).toHaveLength(1);
    expect(screenLogs[0].attributes['screen.name']).toBe('ProductList');
    expect(pageStarts).toHaveLength(1);
    expect(pageStarts[0].name).toBe('page.ProductList');
  });

  it('ends the previous page span when navigating to a new screen', async () => {
    uninstall = installReactNavigationInstrumentation(nav);
    nav._set('Home');
    nav._set('Cart');
    await Dash0Mobile.flushWindow(0);

    const { pageStarts, pageEnds } = collect();
    expect(pageStarts).toHaveLength(2);
    expect(pageStarts.map(p => p.name)).toEqual(['page.Home', 'page.Cart']);
    expect(pageEnds).toHaveLength(1); // only Home ended (Cart still open)
  });

  it('does not double-emit if the state event fires with the same route', async () => {
    uninstall = installReactNavigationInstrumentation(nav);
    nav._set('Home');
    nav._set('Home');
    await Dash0Mobile.flushWindow(0);

    const { screenLogs, pageStarts } = collect();
    expect(screenLogs).toHaveLength(1);
    expect(pageStarts).toHaveLength(1);
  });

  it('uninstall closes the active page span', async () => {
    const un = installReactNavigationInstrumentation(nav);
    nav._set('Cart');
    un();
    await Dash0Mobile.flushWindow(0);

    const { pageEnds } = collect();
    expect(pageEnds).toHaveLength(1);
  });

  it('returns a no-op uninstaller when navRef is missing', () => {
    const un = installReactNavigationInstrumentation(null);
    expect(typeof un).toBe('function');
    un();
  });
});
