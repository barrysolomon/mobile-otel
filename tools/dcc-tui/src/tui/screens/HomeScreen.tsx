import React, { useState } from 'react';
import { Box, Text, useInput } from 'ink';
import { navigate, type ScreenProps } from '../types.js';

interface MenuItem {
  label: string;
  hint: string;
  go: () => void;
}

export function HomeScreen({ state, setState }: ScreenProps) {
  const [cursor, setCursor] = useState(0);

  const items: MenuItem[] = [
    { label: 'Pre-flight status check', hint: 'collector, backend, app, dash0', go: () => setState(navigate(state, 'status')) },
    { label: 'Scenario Library', hint: 'every demo + smoke + UAT cell', go: () => setState(navigate(state, 'scenarios')) },
    { label: 'Network-restored toggle', hint: 'NF-001…NF-011 demo moment', go: () => setState(navigate(state, 'networkRestored')) },
    { label: 'UAT matrix cell', hint: 'pick mode × connectivity × crash', go: () => setState(navigate(state, 'uatCell')) },
    { label: 'Help', hint: 'hotkey reference + Ink TUI notes', go: () => setState(navigate(state, 'help')) },
  ];

  useInput((input, key) => {
    if (key.upArrow) setCursor((c) => Math.max(0, c - 1));
    else if (key.downArrow) setCursor((c) => Math.min(items.length - 1, c + 1));
    else if (key.return) items[cursor]!.go();
    else if (input === 's') setState(navigate(state, 'status'));
    else if (input === 'S') setState(navigate(state, 'scenarios'));
  });

  return (
    <Box flexDirection="column" paddingX={2} paddingY={1}>
      <Text bold color="cyan">What do you want to do?</Text>
      <Box marginTop={1} flexDirection="column">
        {items.map((item, i) => (
          <Box key={i}>
            <Text color={i === cursor ? 'cyan' : 'gray'}>{i === cursor ? '› ' : '  '}</Text>
            <Text bold={i === cursor}>{item.label}</Text>
            <Text color="gray" dimColor>  {item.hint}</Text>
          </Box>
        ))}
      </Box>
    </Box>
  );
}
