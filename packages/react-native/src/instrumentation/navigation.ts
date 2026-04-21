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

  const onState = () => {
    const route = navRef.getCurrentRoute();
    if (!route) return;
    if (route.name === currentName) return;

    if (currentSpan) {
      currentSpan.end();
      currentSpan = null;
    }

    currentName = route.name;
    Dash0Mobile.log('ui.screen_view', { 'screen.name': route.name }, 9);
    currentSpan = Dash0Mobile.startSpan(`page.${route.name}`);
  };

  const unsub = navRef.addListener('state', onState);

  return function uninstall() {
    unsub();
    if (currentSpan) {
      currentSpan.end();
      currentSpan = null;
    }
  };
}
