import React, { useEffect, useState } from 'react';
import { Box, Text, useInput } from 'ink';
import { back, navigate, type ScreenProps, type Screen } from '../types.js';
import { RunConfigPanel } from '../RunConfigPanel.js';
import { fanOut, killAll, resolveRepoRoot, type SpawnHandle } from '../lib/parallelRunner.js';

interface Scenario {
  id: string;
  label: string;
  hint: string;
  /** When set, the scenario shells out to bash with these args. */
  bash?: string[];
  /** When set, switching to this screen instead of running. */
  navigateTo?: Screen;
}

/** Mirror of scripts/test/lib/scenarios.sh + the canonical 4 from crash-test-menu.sh. */
const scenarios: Scenario[] = [
  { id: 'ci',              label: 'Automated crash + recovery', hint: 'CI mode, no prompts',                    bash: ['scripts/test/demo-control-center.sh', '--ci'] },
  { id: 'airplane-crash',  label: 'Airplane mode crash demo',   hint: 'offline crash → reconnect → flush',      bash: ['scripts/test/demo-control-center.sh', '--airplane'] },
  { id: 'full-demo',       label: 'Full narrated demo',         hint: 'crash then airplane, narrated',          bash: ['scripts/test/demo-control-center.sh', '--full-demo'] },
  { id: 'network-restored',label: 'Network-restored toggle',    hint: 'NF-001…NF-011 demo moment',              navigateTo: 'networkRestored' },
  { id: 'journey',         label: 'User-journey booking demo',  hint: 'spans + screenshots + wireframes',       bash: ['scripts/test/demo-control-center.sh', '--journey'] },
  { id: 'selective-flush', label: 'Selective flush showcase',   hint: '20 silent events → trigger → flush',     bash: ['scripts/test/demo-control-center.sh', '--selective-flush'] },
  { id: 'uat-cell',        label: 'Run one UAT cell',           hint: 'pick mode × connectivity × crash',       navigateTo: 'uatCell' },
  { id: 'uat-matrix',      label: 'Full 12-cell UAT matrix',    hint: 'all Android-native cells (~15 min)',     bash: ['scripts/test/uat/run-uat-matrix.sh', '--platform=android-native'] },
  { id: 'ios-smoke',       label: 'iOS native smoke',           hint: 'validate-ios-end-to-end.sh',             bash: ['validate-ios-end-to-end.sh'] },
  { id: 'rn-android-smoke',label: 'RN Android smoke',           hint: 'validate-rn-end-to-end.sh',              bash: ['scripts/test/validate-rn-end-to-end.sh', '--platform=android', '--mode=jest'] },
  { id: 'rn-ios-smoke',    label: 'RN iOS smoke',               hint: 'validate-rn-end-to-end.sh',              bash: ['scripts/test/validate-rn-end-to-end.sh', '--platform=ios', '--mode=jest'] },
];

export function ScenariosScreen({ state, setState }: ScreenProps) {
  const [cursor, setCursor] = useState(0);
  const [handles, setHandles] = useState<SpawnHandle[]>([]);
  const [tick, setTick] = useState(0);

  const running = handles.some((h) => !h.exited);

  // Force a re-render on each tick so child output appears live. Cheap —
  // 5Hz is plenty for human-readable streaming.
  useEffect(() => {
    if (handles.length === 0) return;
    const id = setInterval(() => setTick((t) => t + 1), 200);
    return () => clearInterval(id);
  }, [handles]);

  // Clean up children if user navigates away mid-run.
  useEffect(() => {
    return () => { if (handles.length > 0) killAll(handles); };
  }, [handles]);

  const launch = (s: Scenario) => {
    if (s.navigateTo) { setState(navigate(state, s.navigateTo)); return; }
    if (!s.bash) return;
    const [cmd, ...args] = s.bash;
    const pool = fanOut(cmd!, args, state.runConfig, resolveRepoRoot());
    setHandles(pool);
  };

  useInput((input, key) => {
    if (running) {
      if (input === 'k') killAll(handles);
      else if (key.escape) { killAll(handles); setHandles([]); }
      return;
    }
    if (key.upArrow) setCursor((c) => Math.max(0, c - 1));
    else if (key.downArrow) setCursor((c) => Math.min(scenarios.length - 1, c + 1));
    else if (key.return) launch(scenarios[cursor]!);
    else if (key.escape) setState(back(state));
    else if (input === 'D') setState(navigate(state, 'devices'));
    else if (input === 'M') setState(navigate(state, 'mode'));
    else if (input === 'T') setState(navigate(state, 'target'));
    else if (input === 'P') setState(navigate(state, 'platform'));
    else if (input === 'O') setState(navigate(state, 'options'));
  });

  return (
    <Box flexDirection="column" paddingX={2} paddingY={1}>
      <RunConfigPanel config={state.runConfig} compact />
      <Box marginTop={1}>
        <Text bold color="cyan">Scenario Library</Text>
        <Text color="gray" dimColor>  ·  D devices  ·  M mode  ·  T target  ·  P platform  ·  O options</Text>
      </Box>

      <Box marginTop={1} flexDirection="row">
        <Box flexDirection="column" width={42}>
          {scenarios.map((s, i) => (
            <Box key={s.id}>
              <Text color={i === cursor ? 'cyan' : 'gray'}>{i === cursor ? '› ' : '  '}</Text>
              <Text bold={i === cursor}>{s.label}</Text>
            </Box>
          ))}
        </Box>
        <Box flexDirection="column" flexGrow={1} paddingLeft={2}>
          <Text color="gray" dimColor>{scenarios[cursor]?.hint}</Text>

          {handles.length === 0 ? (
            <Box marginTop={1}>
              <Text color="gray" dimColor>
                ↵ run on {state.runConfig.devices.length === 0
                  ? 'default device (auto-pick)'
                  : `${state.runConfig.devices.length} selected device${state.runConfig.devices.length === 1 ? '' : 's'}${state.runConfig.parallel ? ' in parallel' : ''}`}
              </Text>
            </Box>
          ) : (
            <Box marginTop={1} flexDirection="column">
              <Text color="cyan">running on {handles.length} device{handles.length === 1 ? '' : 's'}  ·  k kill  ·  esc stop+back</Text>
              {handles.map((h) => (
                <Box key={h.key} marginTop={1} flexDirection="column" borderStyle="round" borderColor={h.exited ? (h.exitCode === 0 ? 'green' : 'red') : 'yellow'} paddingX={1}>
                  <Text bold>
                    <Text color={h.exited ? (h.exitCode === 0 ? 'green' : 'red') : 'yellow'}>
                      {h.exited ? (h.exitCode === 0 ? '✓' : '✗') : '●'}
                    </Text>
                    {' '}
                    {h.key}
                    {h.exited ? <Text color="gray" dimColor>  (exit {h.exitCode})</Text> : null}
                  </Text>
                  {/* Tail the last 4 lines for compact rendering. */}
                  {h.lines.slice(-4).map((line, i) => (
                    <Text key={i} color="gray" dimColor>  {line}</Text>
                  ))}
                </Box>
              ))}
              <Box marginTop={1}>
                <Text color="gray" dimColor>tick {tick}</Text>
              </Box>
            </Box>
          )}
        </Box>
      </Box>
    </Box>
  );
}
