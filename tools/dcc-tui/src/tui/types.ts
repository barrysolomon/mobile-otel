/**
 * Stack-routed screen enumeration. To add a screen:
 *   1. Add a member to this union.
 *   2. Add a screen component under src/tui/screens/<Name>Screen.tsx.
 *   3. Add a case in ScreenRouter (App.tsx).
 *   4. Add advertised hotkeys to SCREEN_HOTKEYS in Footer.tsx.
 * The four-step ritual is intentional — drift on step 4 is the only way the
 * footer can lie, so a useScreenHotkeys() helper that registers both the
 * useInput handler and the footer hint in one call would be the right
 * follow-up evolution.
 */
export type Screen =
  | 'home'
  | 'status'
  | 'scenarios'
  | 'networkRestored'
  | 'uatCell'
  | 'devices'
  | 'platform'
  | 'mode'
  | 'target'
  | 'options'
  | 'help'
  | 'error';

/** Platform a scenario will run against. */
export type Platform = 'android' | 'ios' | 'rn-android' | 'rn-ios';

/** Export mode the demo app should run in. Mirrors SDK's ExportMode enum. */
export type ExportMode = 'CONTINUOUS' | 'CONDITIONAL' | 'HYBRID';

/** Export target — where telemetry goes. */
export type ExportTarget = 'dash0' | 'local' | 'custom';

/** A device the user might run a scenario against. */
export interface Device {
  /** `serial` is the adb serial when booted (e.g. `emulator-5554`). Empty for AVDs not yet booted. */
  serial: string;
  /** `avd` is the AVD name (e.g. `Pixel_7`). Empty for physical devices. */
  avd: string;
  /** model + API level for the display row. */
  model: string;
  api: string;
  /** `booted` is whether sys.boot_completed=1 right now. */
  booted: boolean;
}

/**
 * RunConfig is the bundle of selections every scenario inherits.
 * Lives on AppState so all screens read/write the same thing.
 */
export interface RunConfig {
  platform: Platform;
  mode: ExportMode;
  target: ExportTarget;
  /** Selected device serials (or AVD names for not-yet-booted ones, prefixed `avd:`). */
  devices: string[];
  /** Custom endpoint when target === 'custom'. */
  customEndpoint: string;
  /** Run scenarios in parallel across all selected devices. */
  parallel: boolean;
  /** Keep app installed after run (suppresses teardown). */
  keepApp: boolean;
}

export const defaultRunConfig: RunConfig = {
  platform: 'android',
  mode: 'CONDITIONAL',
  target: 'dash0',
  devices: [],
  customEndpoint: '',
  parallel: true,
  keepApp: false,
};

export interface StatusLine {
  text: string;
  tone: 'info' | 'warn' | 'error' | 'ok';
}

export interface AppState {
  /** Current screen. */
  screen: Screen;
  /** History stack for Esc-back navigation. Empty means we're at home. */
  back: Screen[];
  /** Multi-select scratchpad — toggled by spacebar on list screens. */
  selected: Set<string>;
  /** Single focused item, e.g. when entering a detail/action screen. */
  focused?: string;
  /** Footer status line. */
  status: StatusLine;
  /** Suppress automatic redirects (e.g. if an env probe wants to bounce
   *  the user to the error screen). */
  suppressAutoRoute: boolean;
  /** Persistent run configuration inherited by every scenario. */
  runConfig: RunConfig;
}

export interface ScreenProps {
  state: AppState;
  setState: (next: AppState) => void;
}

export const initialState: AppState = {
  screen: 'home',
  back: [],
  selected: new Set(),
  status: { text: 'ready', tone: 'info' },
  suppressAutoRoute: false,
  runConfig: defaultRunConfig,
};

/** Push current screen onto the history and switch. Used by every nav callsite. */
export function navigate(state: AppState, screen: Screen): AppState {
  return {
    ...state,
    screen,
    back: [...state.back, state.screen],
  };
}

/** Pop the history. Falls back to 'home' when empty. */
export function back(state: AppState): AppState {
  if (state.back.length === 0) return { ...state, screen: 'home' };
  const next = state.back[state.back.length - 1]!;
  return {
    ...state,
    screen: next,
    back: state.back.slice(0, -1),
  };
}
