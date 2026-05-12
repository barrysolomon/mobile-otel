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
  | 'help'
  | 'error';

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
