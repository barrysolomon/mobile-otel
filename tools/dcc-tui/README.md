# dcc-tui — Demo Control Center, Ink+React TUI

Companion to `scripts/test/demo-control-center.sh`. Same scenarios, prettier shell. Stack-routed Ink+React, alt-screen-buffer.

## Run

```bash
cd tools/dcc-tui
npm install
npm run dev          # tsx-driven, hot-restart on save
# or, after first build:
npm run build && node dist/tui/index.js
```

`npm run typecheck` is a non-emitting `tsc` for CI.

## Architecture

```
src/tui/
├── index.tsx            entry — alt-screen-buffer + render(<App />)
├── App.tsx              banner / router / footer + global hotkeys
├── types.ts             Screen union, AppState, navigate / back
├── Banner.tsx           persistent top band (3 rows)
├── Footer.tsx           persistent bottom band + per-screen hotkey hints
├── hooks/
│   └── useTerminalSize.ts  re-render on stdout resize
└── screens/
    ├── HomeScreen.tsx        ↑↓ menu, opens other screens
    ├── StatusScreen.tsx      pre-flight probes (emu/backend/collector/dash0)
    ├── ScenariosScreen.tsx   library — every demo + smoke + UAT cell
    ├── NetworkRestoredScreen.tsx  NF-001…NF-011 demo moment
    ├── UatCellScreen.tsx     pick mode × connectivity × crash
    └── HelpScreen.tsx        in-app cheat sheet
```

### Stack routing

`AppState.back: Screen[]` is the history. `navigate(state, 'screenName')` pushes the current screen and switches. `Esc` pops; empty stack falls back to home. No router library — Ink has no URL bar, so a router would just be weight.

Adding a screen takes four steps:

1. Add a member to the `Screen` union in `types.ts`.
2. Write the screen component under `screens/`.
3. Add a `case` to `ScreenRouter` in `App.tsx`.
4. Add hotkey hints to `SCREEN_HOTKEYS` in `Footer.tsx`.

Step 4 is the drift risk — the footer can lie if you forget. The right follow-up is a `useScreenHotkeys()` helper that registers both the `useInput` handler and the footer hint in one call.

### Two-layer hotkeys

- **Global** (App.tsx): `ctrl-c` exit, `q` exit (home only), `esc` back, `?` help.
- **Screen-local** (each `screens/*.tsx` `useInput`): screen-specific actions.

### Side-effect routing

Today the only side-effect is shelling out scenarios — each scenario screen wraps `spawn('bash', ['-c', cmd])` and streams stdout into local React state. If we add background probes (auth, network, config) the pattern from the spec applies: a hook fires on a schedule, an `App.tsx` effect auto-pushes a dedicated error screen on failure, an `AppState.suppressAutoRoute` flag lets the user cancel out. Not wired yet — leave it for the next session.

## Why this exists

`scripts/test/demo-control-center.sh` is the canonical implementation — every scenario lives there. This TUI is a shell over the same scripts. The bash menu is the source of truth; if the two diverge, fix the script first, then mirror here.
