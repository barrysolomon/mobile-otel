import React, { useState } from 'react';
import { spawn } from 'node:child_process';
import { Box, Text, useInput } from 'ink';
import { back, navigate, type ScreenProps } from '../types.js';

interface Scenario {
  id: string;
  label: string;
  hint: string;
  /** Bash command to invoke when picked. Empty = navigate to dedicated screen. */
  command?: string;
  /** Navigate to a dedicated screen instead of running a command. */
  navigateTo?: 'networkRestored' | 'uatCell';
}

/** Mirror of scripts/test/lib/scenarios.sh — labels match the bash submenu. */
const scenarios: Scenario[] = [
  { id: 'ci',                 label: 'Automated crash + recovery', hint: 'demo-control-center.sh --ci',            command: 'scripts/test/demo-control-center.sh --ci' },
  { id: 'airplane-crash',     label: 'Airplane mode crash demo',   hint: 'demo-control-center.sh --airplane',      command: 'scripts/test/demo-control-center.sh --airplane' },
  { id: 'full-demo',          label: 'Full narrated demo',         hint: 'crash then airplane, narrated',          command: 'scripts/test/demo-control-center.sh --full-demo' },
  { id: 'network-restored',   label: 'Network-restored toggle',    hint: 'NF-001…NF-011 demo moment',              navigateTo: 'networkRestored' },
  { id: 'journey',            label: 'User-journey booking demo',  hint: 'spans + screenshots + wireframes',       command: 'scripts/test/demo-control-center.sh --journey' },
  { id: 'selective-flush',    label: 'Selective flush showcase',   hint: '20 silent events → trigger → flush',     command: 'scripts/test/demo-control-center.sh --selective-flush' },
  { id: 'uat-cell',           label: 'Run one UAT cell',           hint: 'pick mode × connectivity × crash',       navigateTo: 'uatCell' },
  { id: 'uat-matrix',         label: 'Full 12-cell UAT matrix',    hint: 'all Android-native cells (~15 min)',     command: 'scripts/test/uat/run-uat-matrix.sh --platform=android-native' },
  { id: 'ios-smoke',          label: 'iOS native smoke',           hint: 'validate-ios-end-to-end.sh',             command: 'validate-ios-end-to-end.sh' },
  { id: 'rn-android-smoke',   label: 'RN Android smoke',           hint: 'validate-rn-end-to-end.sh',              command: 'scripts/test/validate-rn-end-to-end.sh --platform=android --mode=jest' },
  { id: 'rn-ios-smoke',       label: 'RN iOS smoke',               hint: 'validate-rn-end-to-end.sh',              command: 'scripts/test/validate-rn-end-to-end.sh --platform=ios --mode=jest' },
];

export function ScenariosScreen({ state, setState }: ScreenProps) {
  const [cursor, setCursor] = useState(0);
  const [output, setOutput] = useState<string[]>([]);
  const [running, setRunning] = useState(false);

  const launch = (s: Scenario) => {
    if (s.navigateTo) {
      setState(navigate(state, s.navigateTo));
      return;
    }
    if (!s.command) return;
    setRunning(true);
    setOutput([`$ ${s.command}`, '']);
    // bash -c is portable across the ./run-* root forwarders + script paths.
    const child = spawn('bash', ['-c', s.command], { cwd: process.cwd() });
    child.stdout.on('data', (b) => setOutput((o) => [...o, ...b.toString().split('\n')]));
    child.stderr.on('data', (b) => setOutput((o) => [...o, ...b.toString().split('\n')]));
    child.on('exit', (code) => {
      setOutput((o) => [...o, '', `exit ${code}`]);
      setRunning(false);
    });
  };

  useInput((input, key) => {
    if (running) return;  // disable nav while a scenario runs
    if (key.upArrow) setCursor((c) => Math.max(0, c - 1));
    else if (key.downArrow) setCursor((c) => Math.min(scenarios.length - 1, c + 1));
    else if (key.return) launch(scenarios[cursor]!);
    else if (key.escape) setState(back(state));
  });

  return (
    <Box flexDirection="column" paddingX={2} paddingY={1}>
      <Text bold color="cyan">Scenario Library</Text>
      <Box marginTop={1} flexDirection="row">
        <Box flexDirection="column" width={48}>
          {scenarios.map((s, i) => (
            <Box key={s.id}>
              <Text color={i === cursor ? 'cyan' : 'gray'}>{i === cursor ? '› ' : '  '}</Text>
              <Text bold={i === cursor}>{s.label}</Text>
            </Box>
          ))}
        </Box>
        <Box flexDirection="column" flexGrow={1} paddingLeft={2}>
          <Text color="gray" dimColor>{scenarios[cursor]!.hint}</Text>
          <Box marginTop={1} flexDirection="column">
            {output.slice(-10).map((line, i) => (
              <Text key={i} color="gray" dimColor>{line}</Text>
            ))}
          </Box>
          {running ? <Text color="yellow">running…</Text> : null}
        </Box>
      </Box>
    </Box>
  );
}
