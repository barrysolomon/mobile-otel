/**
 * AppState lifecycle auto-instrumentation for React Native.
 *
 * Emits INFO-severity logs on foreground / background / inactive transitions.
 * Suppresses consecutive duplicates so noisy platforms that double-fire don't
 * multiply the signal.
 */

import { Dash0Mobile } from '../index';

const SEVERITY_INFO = 9 as const;

type AppStateValue = 'active' | 'background' | 'inactive' | 'unknown';

interface Subscription {
  remove(): void;
}

interface AppStateLike {
  addEventListener(
    type: 'change',
    listener: (state: AppStateValue) => void,
  ): Subscription;
}

function nameForState(state: AppStateValue): string | null {
  switch (state) {
    case 'active':
      return 'app.foreground';
    case 'background':
      return 'app.background';
    case 'inactive':
      return 'app.inactive';
    default:
      return null;
  }
}

function resolveAppState(): AppStateLike | null {
  try {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const rn = require('react-native');
    return (rn?.AppState as AppStateLike) ?? null;
  } catch {
    return null;
  }
}

export function installAppStateInstrumentation(
  appStateOverride?: AppStateLike | null,
): () => void {
  // When the caller passes a test double, we skip deferral — tests expect
  // installation to be deterministic.
  if (appStateOverride !== undefined) {
    return installAppStateInstrumentationSync(appStateOverride);
  }

  // Production path: defer EVERYTHING that touches react-native. In RN 0.85's
  // new architecture, importing `AppState` (even via `require('react-native')`)
  // transitively evaluates `NativeEventEmitter` → `Platform` →
  // `TurboModuleRegistry.getEnforcing('PlatformConstants')`, and that registry
  // isn't ready inside the first useEffect of the first render.
  //
  // 100ms is empirically enough for the new-arch TurboModule registry to
  // finish wiring up. setTimeout(0) alone isn't — it fires on the same macrotask
  // cycle as the failing useEffect. We accept the trade: lifecycle events
  // fired within the first 100ms of start() are missed, but start() itself
  // never redboxes.
  let uninstaller: (() => void) | null = null;
  let uninstalled = false;
  const deferHandle = setTimeout(() => {
    if (uninstalled) return;
    const resolved = resolveAppState();
    if (!resolved) return;
    uninstaller = installAppStateInstrumentationSync(resolved);
  }, 100);

  return function uninstall() {
    uninstalled = true;
    clearTimeout(deferHandle);
    uninstaller?.();
  };
}

function installAppStateInstrumentationSync(
  appState: AppStateLike | null,
): () => void {
  if (!appState) return () => {};

  let lastState: AppStateValue | null = null;
  const sub = appState.addEventListener('change', (state) => {
    if (state === lastState) return;
    lastState = state;
    const name = nameForState(state);
    if (!name) return;
    Dash0Mobile.log(name, { 'app.state': state }, SEVERITY_INFO);
  });

  return function uninstall() {
    sub.remove();
  };
}
