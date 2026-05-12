import React from 'react';
import { Box, Text } from 'ink';
import type { Screen, StatusLine } from './types.js';

/**
 * Hotkey hints per screen. THIS IS DECOUPLED from the screens' real
 * useInput handlers — if you add a key in a screen and forget here, the
 * footer lies. Follow-up: useScreenHotkeys() helper that registers both
 * in one call.
 */
export const SCREEN_HOTKEYS: Partial<Record<Screen, Array<[string, string]>>> = {
  home: [
    ['↑↓', 'move'],
    ['↵', 'select'],
    ['D', 'devices'],
    ['M', 'mode'],
    ['T', 'target'],
    ['L', 'library'],
    ['?', 'help'],
    ['q', 'quit'],
  ],
  status: [['r', 'refresh'], ['esc', 'back']],
  scenarios: [
    ['↑↓', 'move'],
    ['↵', 'run'],
    ['D', 'devices'],
    ['M', 'mode'],
    ['T', 'target'],
    ['k', 'kill (when running)'],
    ['esc', 'back'],
  ],
  networkRestored: [
    ['n', 'run airplane toggle'],
    ['N', 'lite (no booking)'],
    ['esc', 'back'],
  ],
  uatCell: [
    ['1-3', 'mode'],
    ['a/b', 'connectivity'],
    ['x/y', 'crash'],
    ['↵', 'run'],
    ['esc', 'back'],
  ],
  devices: [
    ['↑↓', 'move'],
    ['space/↵', 'toggle'],
    ['a', 'all booted'],
    ['c', 'clear'],
    ['r', 'refresh'],
    ['esc', 'back'],
  ],
  platform: [['↑↓', 'move'], ['↵', 'select'], ['esc', 'back']],
  mode: [['↑↓', 'move'], ['↵', 'select'], ['esc', 'back']],
  target: [['↑↓', 'move'], ['↵', 'select'], ['esc', 'back']],
  options: [
    ['p', 'parallel'],
    ['k', 'keep app'],
    ['e', 'edit endpoint'],
    ['esc', 'back'],
  ],
  help: [['esc', 'back']],
  error: [['esc', 'dismiss']],
};

interface FooterProps {
  width: number;
  screen: Screen;
  status: StatusLine;
}

const toneColor = (tone: StatusLine['tone']) => {
  switch (tone) {
    case 'ok': return 'green';
    case 'warn': return 'yellow';
    case 'error': return 'red';
    default: return 'cyan';
  }
};

export function Footer({ width, screen, status }: FooterProps) {
  const hints = SCREEN_HOTKEYS[screen] ?? [];
  return (
    <Box width={width} flexDirection="column" borderStyle="single" borderColor="gray" paddingX={1}>
      <Box justifyContent="space-between">
        <Text color={toneColor(status.tone)}>
          {status.tone === 'ok' ? '✓ ' : status.tone === 'error' ? '✗ ' : '• '}
          {status.text}
        </Text>
        <Box>
          {hints.map(([key, label], i) => (
            <Text key={i}>
              <Text bold color="cyan">{key}</Text>
              <Text color="gray" dimColor>{' '}{label}</Text>
              {i < hints.length - 1 ? <Text color="gray" dimColor>  ·  </Text> : null}
            </Text>
          ))}
        </Box>
      </Box>
    </Box>
  );
}

export const FOOTER_HEIGHT = 3;
