import React, { useState } from 'react';
import { spawn } from 'node:child_process';
import { Box, Text, useInput } from 'ink';
import { back, type ScreenProps } from '../types.js';
import { resolveRepoRoot } from '../lib/parallelRunner.js';

/**
 * Headline demo screen for NF-001…NF-011. Two paths:
 *   n — full toggle: airplane on → fail booking → airplane off → flush
 *   N — clean transition probe: just toggle + watch logcat
 *
 * Both delegate to the same bash script — this screen is a TUI front for
 * the well-tested shell logic, not a re-implementation.
 */
export function NetworkRestoredScreen({ state, setState }: ScreenProps) {
  const [output, setOutput] = useState<string[]>([
    'NF-001…NF-011 — Network-Restored Flush Demo Moment',
    '',
    'Press n to run the full airplane-toggle demo (boot → on → fail → off → flush).',
    'Press N to run the clean transition probe (no booking; just verify watcher fires).',
    'Press esc to go back.',
  ]);
  const [running, setRunning] = useState(false);

  const run = (flag: '--network-restored' | '--network-restored-lite') => {
    setRunning(true);
    setOutput([`$ scripts/test/demo-control-center.sh ${flag}`, '']);
    const child = spawn('bash', ['scripts/test/demo-control-center.sh', flag], { cwd: resolveRepoRoot() });
    child.stdout.on('data', (b) => setOutput((o) => [...o, ...b.toString().split('\n')]));
    child.stderr.on('data', (b) => setOutput((o) => [...o, ...b.toString().split('\n')]));
    child.on('exit', (code) => {
      setOutput((o) => [...o, '', `exit ${code}`]);
      setRunning(false);
    });
  };

  useInput((input, key) => {
    if (running) return;
    if (input === 'n') run('--network-restored');
    else if (input === 'N') run('--network-restored-lite');
    else if (key.escape) setState(back(state));
  });

  return (
    <Box flexDirection="column" paddingX={2} paddingY={1}>
      <Text bold color="cyan">Network-Restored Flush — NF-001…NF-011</Text>
      <Box marginTop={1} flexDirection="column">
        {output.slice(-Math.max(5, process.stdout.rows - 12)).map((line, i) => (
          <Text key={i} color="gray" dimColor={!line.startsWith('$')}>{line}</Text>
        ))}
      </Box>
      {running ? (
        <Box marginTop={1}>
          <Text color="yellow">running… (output is decoupled from the bash menu's interactive prompts; some demos may need stdin)</Text>
        </Box>
      ) : null}
    </Box>
  );
}
