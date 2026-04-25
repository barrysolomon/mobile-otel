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
  // 2026-04-24 investigation: even with the 100ms defer, attempting to
  // `require('react-native')` to resolve `AppState` consistently
  // redboxes on iPhone 17 Simulator iOS 26.4 + RN 0.85 new-arch +
  // Hermes. Bumping the defer to 1500ms did not help — `getEnforcing`
  // throws BEFORE the defer fires, suggesting the Invariant Violation
  // surfaces on a different scheduler tick than the JS try/catch we
  // wrap it in (RN's `RCTFatal` catches the JS throw and converts it
  // to a native fatal exception that bypasses our try/catch).
  //
  // Conclusion: this is an upstream RN issue, not something a longer
  // timeout fixes. Keep the `lifecycle: false` opt-out in the demo
  // app's `Dash0Mobile.start({...})` call until the upstream new-arch
  // init order is addressed (or until we adopt a registry-ready signal
  // like a `requestAnimationFrame` + `setTimeout(0)` chain that
  // empirically waits for "after first paint").
  //
  // The 100ms defer here is retained as a defensive measure for any
  // RN version where the race is narrower; it doesn't hurt non-redbox
  // cases.
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
