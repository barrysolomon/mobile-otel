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
        <Box marginTop={1}>
          <Text bold>Architecture notes</Text>
        </Box>
        <Text color="gray" dimColor>  Stack-routed: every navigate() pushes onto AppState.back.</Text>
        <Text color="gray" dimColor>  Screens are self-contained: own useInput, own state.</Text>
        <Text color="gray" dimColor>  Footer hints are decoupled from real handlers — watch for drift.</Text>
        <Text color="gray" dimColor>  Alt-screen-buffer is owned by Ink (start/stop on render/unmount).</Text>
        <Box marginTop={1}>
          <Text bold>This is a TUI front for the bash control center.</Text>
        </Box>
        <Text color="gray" dimColor>  Every scenario shells out to scripts/test/demo-control-center.sh.</Text>
        <Text color="gray" dimColor>  The bash menu remains the authoritative implementation.</Text>
      </Box>
      <Box marginTop={1}>
        <Text color="green">esc to go back</Text>
      </Box>
    </Box>
  );
}
