import React from 'react';
import { Box, Text, useInput } from 'ink';
import { back, type ScreenProps } from '../types.js';

export function HelpScreen({ state, setState }: ScreenProps) {
  useInput((_, key) => { if (key.escape) setState(back(state)); });
  return (
    <Box flexDirection="column" paddingX={2} paddingY={1}>
      <Text bold color="cyan">Help</Text>

      <Box marginTop={1} flexDirection="column">
        <Text bold>Global hotkeys</Text>
        <Text color="gray" dimColor>  ctrl-c  exit</Text>
        <Text color="gray" dimColor>  q       exit (only on home)</Text>
        <Text color="gray" dimColor>  esc     pop history (or go home if empty)</Text>
        <Text color="gray" dimColor>  ?       open this help</Text>
      </Box>

      <Box marginTop={1} flexDirection="column">
        <Text bold>Run-configuration workflow</Text>
        <Text color="gray" dimColor>  1. Press D to pick one or more devices (space toggles, a = all-booted).</Text>
        <Text color="gray" dimColor>  2. Press M to choose export mode (CONT / COND / HYB).</Text>
        <Text color="gray" dimColor>  3. Press T to choose export target (Dash0 / local / custom).</Text>
        <Text color="gray" dimColor>  4. Press P to choose platform (Android / iOS / RN variants).</Text>
        <Text color="gray" dimColor>  5. Press O to tweak parallel / keep-app / custom endpoint.</Text>
        <Text color="gray" dimColor>  6. Press L to open the Scenario Library, then ↵ to run.</Text>
        <Text color="gray" dimColor></Text>
        <Text color="gray" dimColor>  The banner (top of every screen) shows your current selections.</Text>
        <Text color="gray" dimColor>  Scenarios fan out across all selected devices in parallel.</Text>
      </Box>

      <Box marginTop={1} flexDirection="column">
        <Text bold>Architecture notes</Text>
        <Text color="gray" dimColor>  Stack-routed: every navigate() pushes onto AppState.back.</Text>
        <Text color="gray" dimColor>  Screens are self-contained: own useInput, own state.</Text>
        <Text color="gray" dimColor>  Footer hints in SCREEN_HOTKEYS are decoupled from the real handlers</Text>
        <Text color="gray" dimColor>    — keep both in sync when adding a new hotkey.</Text>
        <Text color="gray" dimColor>  parallelRunner.fanOut() spawns one bash child per device, with the</Text>
        <Text color="gray" dimColor>    SERIAL env var pre-set so find_emulator picks the right one.</Text>
      </Box>

      <Box marginTop={1} flexDirection="column">
        <Text bold>Authoritative source</Text>
        <Text color="gray" dimColor>  This TUI is a shell over scripts/test/demo-control-center.sh.</Text>
        <Text color="gray" dimColor>  Every scenario shells out — the bash menu is the source of truth.</Text>
      </Box>

      <Box marginTop={1}>
        <Text color="green">esc to go back</Text>
      </Box>
    </Box>
  );
}
