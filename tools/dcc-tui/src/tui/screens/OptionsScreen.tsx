import React, { useState } from 'react';
import { Box, Text, useInput } from 'ink';
import { back, type ScreenProps } from '../types.js';

/**
 * Toggles + free-text fields that don't fit the single-select picker shape:
 *   p — parallel runs (one child per device, multiplexed output)
 *   k — keep app installed after run
 *   e — edit custom endpoint (only meaningful when target === 'custom')
 *
 * The custom endpoint editor is a minimal one-line input — Ink doesn't ship a
 * TextInput out of the box and we don't want to add a dependency for one
 * field. Esc cancels the edit, ↵ commits.
 */
export function OptionsScreen({ state, setState }: ScreenProps) {
  const [editingEndpoint, setEditingEndpoint] = useState(false);
  const [draft, setDraft] = useState(state.runConfig.customEndpoint);

  const set = (patch: Partial<typeof state.runConfig>) => {
    setState({ ...state, runConfig: { ...state.runConfig, ...patch } });
  };

  useInput((input, key) => {
    if (editingEndpoint) {
      if (key.escape) { setEditingEndpoint(false); setDraft(state.runConfig.customEndpoint); return; }
      if (key.return) { set({ customEndpoint: draft }); setEditingEndpoint(false); return; }
      if (key.backspace || key.delete) { setDraft((d) => d.slice(0, -1)); return; }
      // Append printable single-byte ASCII (good enough for endpoints; no IME).
      if (input && input.length === 1 && input.charCodeAt(0) >= 0x20) {
        setDraft((d) => d + input);
      }
      return;
    }
    if (key.escape) setState(back(state));
    else if (input === 'p') set({ parallel: !state.runConfig.parallel });
    else if (input === 'k') set({ keepApp: !state.runConfig.keepApp });
    else if (input === 'e') { setDraft(state.runConfig.customEndpoint); setEditingEndpoint(true); }
  });

  const checkbox = (on: boolean) => (on ? '☑' : '☐');

  return (
    <Box flexDirection="column" paddingX={2} paddingY={1}>
      <Text bold color="cyan">Options</Text>
      <Text color="gray" dimColor>
        p toggle parallel  ·  k toggle keep-app  ·  e edit custom endpoint  ·  esc back
      </Text>
      <Box marginTop={1} flexDirection="column">
        <Box>
          <Text color={state.runConfig.parallel ? 'cyan' : 'white'}>{checkbox(state.runConfig.parallel)}</Text>
          <Text>  Run scenarios in parallel across selected devices</Text>
        </Box>
        <Box>
          <Text color={state.runConfig.keepApp ? 'cyan' : 'white'}>{checkbox(state.runConfig.keepApp)}</Text>
          <Text>  Keep app installed after run (suppress teardown)</Text>
        </Box>
        <Box marginTop={1}>
          <Text color={state.runConfig.target === 'custom' ? 'white' : 'gray'} dimColor={state.runConfig.target !== 'custom'}>
            Custom endpoint:
          </Text>
        </Box>
        <Box paddingLeft={2}>
          {editingEndpoint ? (
            <>
              <Text color="cyan">› </Text>
              <Text>{draft}</Text>
              <Text color="cyan">▌</Text>
            </>
          ) : (
            <Text color={state.runConfig.target === 'custom' ? 'white' : 'gray'} dimColor={state.runConfig.target !== 'custom'}>
              {state.runConfig.customEndpoint || '(unset)'}
            </Text>
          )}
        </Box>
        {state.runConfig.target !== 'custom' && !editingEndpoint ? (
          <Box paddingLeft={2}>
            <Text color="gray" dimColor>(only used when target = custom)</Text>
          </Box>
        ) : null}
      </Box>
    </Box>
  );
}
