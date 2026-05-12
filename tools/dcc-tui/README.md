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

## Workflow

The whole point of the TUI is to assemble a **RunConfig** (platform × mode × target × devices × options) once, then have every scenario inherit it. The Home screen is mostly nav-into-pickers.

1. `D` — **Devices**. Multi-select. Lists every booted adb emulator plus every AVD known to `emulator -list-avds`. Space/↵ toggles; `a` selects all booted; `c` clears.
2. `P` — **Platform**. Android native / iOS native / RN-Android / RN-iOS.
3. `M` — **Mode**. CONTINUOUS / CONDITIONAL / HYBRID.
4. `T` — **Target**. Dash0 / local collector / custom endpoint.
5. `O` — **Options**. Parallel runs, keep-app, custom endpoint editor.
6. `L` — **Scenario Library**. ↵ runs the highlighted scenario across every selected device. When `parallel: on`, all devices run concurrently; each gets its own bordered output card.

The Banner (top, every screen) shows a compact one-liner of the current RunConfig so you don't have to dig back to the picker to remember what's set.

## Parallel runs

`parallelRunner.fanOut()` spawns one bash child per device. Each child inherits:

- `SERIAL=<adb-serial>` — for booted devices. `find_emulator` in `lib/common.sh` respects it.
- `AVD=<name>` — for AVD entries (prefixed `avd:` in `runConfig.devices`).
- `DCC_MODE`, `DCC_TARGET`, `DCC_PLATFORM`, `DCC_KEEP_APP`, `DCC_CUSTOM_ENDPOINT` — surfaced so scenarios can react.
- `DCC_RUN_KEY` — the device key for logging.

Each child's stdout/stderr is captured into a ring buffer (`lines: string[]`, capped at 500) and rendered in a per-device card. `k` kills all children; `esc` kills + navigates back.

## Architecture

```text
src/tui/
├── index.tsx                    entry — alt-screen-buffer + render(<App />)
├── App.tsx                      banner / router / footer + global hotkeys
├── types.ts                     Screen union, AppState, RunConfig, navigate / back
├── Banner.tsx                   persistent top band — shows RunConfig summary
├── Footer.tsx                   persistent bottom band + per-screen hotkey hints
├── RunConfigPanel.tsx           full + compact renderers for the RunConfig
├── hooks/
│   └── useTerminalSize.ts       re-render on stdout resize
├── lib/
│   ├── adb.ts                   enumerateDevices, deviceKey, deviceRow
│   ├── ListPicker.tsx           single-select keyboard list (used by Mode/Target/Platform)
│   └── parallelRunner.ts        fanOut, killAll, resolveRepoRoot
└── screens/
    ├── HomeScreen.tsx           RunConfig panel + nav menu
    ├── StatusScreen.tsx         pre-flight probes (emu/backend/collector/dash0)
    ├── DeviceScreen.tsx         multi-select emulators + AVDs
    ├── PlatformScreen.tsx       Android / iOS / RN-Android / RN-iOS
    ├── ModeScreen.tsx           CONTINUOUS / CONDITIONAL / HYBRID
    ├── TargetScreen.tsx         Dash0 / local / custom
    ├── OptionsScreen.tsx        parallel + keep-app + custom endpoint editor
    ├── ScenariosScreen.tsx      library + parallel runner output cards
    ├── NetworkRestoredScreen.tsx  NF-001…NF-011 demo moment
    ├── UatCellScreen.tsx        pick mode × connectivity × crash
    └── HelpScreen.tsx           in-app cheat sheet + architecture notes
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
