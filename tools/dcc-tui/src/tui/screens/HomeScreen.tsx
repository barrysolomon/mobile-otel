import React, { useState } from 'react';
import { Box, Text, useInput } from 'ink';
import { navigate, type ScreenProps, type Screen } from '../types.js';
import { RunConfigPanel } from '../RunConfigPanel.js';

interface MenuItem {
  label: string;
  hint: string;
  screen: Screen;
  /** Single-char hotkey, advertised in the footer + handled here directly. */
  hotkey?: string;
}

export function HomeScreen({ state, setState }: ScreenProps) {
  const [cursor, setCursor] = useState(0);

  const items: MenuItem[] = [
    { hotkey: 'D', label: 'Devices…',             hint: 'multi-select emulators + AVDs',          screen: 'devices' },
    { hotkey: 'P', label: 'Platform…',            hint: 'Android / iOS / RN-Android / RN-iOS',    screen: 'platform' },
    { hotkey: 'M', label: 'Export mode…',         hint: 'CONTINUOUS / CONDITIONAL / HYBRID',      screen: 'mode' },
    { hotkey: 'T', label: 'Export target…',       hint: 'Dash0 / local / custom',                 screen: 'target' },
    { hotkey: 'O', label: 'Options…',             hint: 'parallel, keep-app, custom endpoint',    screen: 'options' },
    { hotkey: 's', label: 'Pre-flight status',    hint: 'collector + backend + dash0 probes',     screen: 'status' },
    { hotkey: 'L', label: 'Scenario Library…',    hint: 'every demo + smoke + UAT cell',          screen: 'scenarios' },
    { hotkey: 'n', label: 'Network-restored toggle', hint: 'NF-001…NF-011 demo moment',           screen: 'networkRestored' },
    { hotkey: 'u', label: 'UAT matrix cell',      hint: 'pick mode × connectivity × crash',       screen: 'uatCell' },
    { hotkey: '?', label: 'Help',                 hint: 'hotkey reference + architecture',        screen: 'help' },
  ];

  useInput((input, key) => {
    if (key.upArrow) setCursor((c) => Math.max(0, c - 1));
    else if (key.downArrow) setCursor((c) => Math.min(items.length - 1, c + 1));
    else if (key.return) setState(navigate(state, items[cursor]!.screen));
    else {
      const match = items.find((it) => it.hotkey === input);
      if (match) setState(navigate(state, match.screen));
    }
  });

  return (
    <Box flexDirection="column" paddingX={2} paddingY={1}>
      <RunConfigPanel config={state.runConfig} />
      <Box marginTop={1}>
        <Text bold color="cyan">Menu</Text>
      </Box>
      <Box marginTop={0} flexDirection="column">
        {items.map((item, i) => (
          <Box key={i}>
            <Text color={i === cursor ? 'cyan' : 'gray'}>{i === cursor ? '› ' : '  '}</Text>
            {item.hotkey ? <Text color="cyan">[{item.hotkey}] </Text> : <Text>    </Text>}
            <Text bold={i === cursor}>{item.label}</Text>
            <Text color="gray" dimColor>  {item.hint}</Text>
          </Box>
        ))}
      </Box>
    </Box>
  );
}
