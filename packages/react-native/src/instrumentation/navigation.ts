/**
 * React Navigation auto-instrumentation (opt-in).
 *
 * Usage:
 *   const navRef = useNavigationContainerRef();
 *   useEffect(() => installReactNavigationInstrumentation(navRef), [navRef]);
 *
 * Emits `ui.screen_view` logs and `page.<name>` spans keyed by the current
 * route. Consecutive emissions for the same route are suppressed.
 */

import { Dash0Mobile } from '../index';
import type { SpanHandle } from '../index';

interface NavRefLike {
  addListener(type: string, listener: () => void): () => void;
  getCurrentRoute(): { name: string } | undefined;
}

export function installReactNavigationInstrumentation(
  navRef: NavRefLike | null | undefined,
): () => void {
  if (!navRef) return () => {};

  let currentName: string | null = null;
  let currentSpan: SpanHandle | null = null;

  const endCurrentSpan = () => {
    if (currentSpan) {
      currentSpan.end();
      currentSpan = null;
    }
  };

  const onState = () => {
    const route = navRef.getCurrentRoute();
    if (!route) return;
    if (route.name === currentName) return;

    endCurrentSpan();

    currentName = route.name;
    Dash0Mobile.log('ui.screen_view', { 'screen.name': route.name }, 9);
    currentSpan = Dash0Mobile.startSpan(`page.${route.name}`);
  };

  const unsub = navRef.addListener('state', onState);

  // End the active route span when the app leaves the foreground. Without
  // this, a span started on the visible screen stays open until the next
  // route change or uninstall — which on background may be minutes/never,
  // producing absurd durations. Re-entering 'active' lets the next route
  // change open a fresh span. AppState is required lazily so non-RN
  // (Jest/SSR) environments don't need the native module.
  let appStateSub: { remove?: () => void } | undefined;
  try {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const { AppState } = require('react-native') as {
      AppState?: {
        addEventListener: (
          type: string,
          listener: (state: string) => void,
        ) => { remove?: () => void };
      };
    };
    if (AppState && typeof AppState.addEventListener === 'function') {
      appStateSub = AppState.addEventListener('change', (state: string) => {
        if (state === 'background' || state === 'inactive') {
          endCurrentSpan();
          // Force the next foregrounded route to re-open a span.
          currentName = null;
        }
      });
    }
  } catch {
    // No AppState available (test/SSR) — background-end is best-effort.
  }

  return function uninstall() {
    unsub();
    appStateSub?.remove?.();
    endCurrentSpan();
  };
}
