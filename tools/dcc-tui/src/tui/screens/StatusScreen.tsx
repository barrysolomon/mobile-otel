import React, { useEffect, useState } from 'react';
import { execFile } from 'node:child_process';
import { Box, Text, useInput } from 'ink';
import { back, type ScreenProps } from '../types.js';

type Health = 'unknown' | 'ok' | 'fail' | 'pending';

interface Probe {
  label: string;
  hint: string;
  state: Health;
  detail?: string;
}

const initial: Probe[] = [
  { label: 'Emulator', hint: 'adb device booted', state: 'pending' },
  { label: 'Demo backend', hint: 'http://localhost:3001/health', state: 'pending' },
  { label: 'Local collector', hint: 'docker compose ps', state: 'pending' },
  { label: 'Dash0', hint: 'ingress reachability', state: 'pending' },
];

function run(cmd: string, args: string[], timeoutMs = 3000): Promise<{ ok: boolean; out: string }> {
  return new Promise((resolve) => {
    const child = execFile(cmd, args, { timeout: timeoutMs }, (err, stdout) => {
      resolve({ ok: !err, out: (stdout ?? '').trim() });
    });
    // Kill on timeout — execFile's timeout sends SIGTERM which Node sometimes misses.
    setTimeout(() => child.kill('SIGKILL'), timeoutMs + 250).unref?.();
  });
}

async function probeAll(): Promise<Probe[]> {
  const results: Probe[] = initial.map((p) => ({ ...p }));

  const adb = await run('adb', ['devices']);
  const adbOk = adb.ok && /\bdevice\b/.test(adb.out.split('\n').slice(1).join('\n'));
  results[0] = { ...results[0]!, state: adbOk ? 'ok' : 'fail', detail: adbOk ? adb.out.split('\n')[1] : 'no booted device' };

  const backend = await run('curl', ['-sfm', '2', 'http://localhost:3001/health']);
  results[1] = { ...results[1]!, state: backend.ok ? 'ok' : 'fail' };

  const docker = await run('docker', ['compose', '-f', '../../k8s/docker-compose.yaml', 'ps']);
  const collectorUp = docker.ok && /Up/.test(docker.out);
  results[2] = { ...results[2]!, state: collectorUp ? 'ok' : 'fail' };

  // Cheap Dash0 reachability — we just check the credentials file exists.
  // The bash menu has a richer 200/401/403 probe; that lives in the script.
  const dash0Config = await run('test', ['-f', '../../examples/demo-app/android/src/debug/assets/otel-config.json']);
  results[3] = { ...results[3]!, state: dash0Config.ok ? 'ok' : 'fail', detail: dash0Config.ok ? 'config present' : 'config missing' };

  return results;
}

export function StatusScreen({ state, setState }: ScreenProps) {
  const [probes, setProbes] = useState<Probe[]>(initial);
  const [running, setRunning] = useState(true);

  const refresh = () => {
    setRunning(true);
    setProbes(initial.map((p) => ({ ...p, state: 'pending' })));
    void probeAll().then((next) => {
      setProbes(next);
      setRunning(false);
    });
  };

  useEffect(() => { refresh(); }, []);

  useInput((input, key) => {
    if (key.escape) setState(back(state));
    else if (input === 'r') refresh();
  });

  const sym = (s: Health) => s === 'ok' ? '✓' : s === 'fail' ? '✗' : s === 'pending' ? '·' : '?';
  const color = (s: Health) => s === 'ok' ? 'green' : s === 'fail' ? 'red' : 'gray';

  return (
    <Box flexDirection="column" paddingX={2} paddingY={1}>
      <Text bold color="cyan">Pre-flight Status</Text>
      <Box marginTop={1} flexDirection="column">
        {probes.map((p, i) => (
          <Box key={i}>
            <Text color={color(p.state)}>{sym(p.state)}</Text>
            <Text>  </Text>
            <Text>{p.label.padEnd(18)}</Text>
            <Text color="gray" dimColor>{p.hint}</Text>
            {p.detail ? <Text color="gray" dimColor>  ({p.detail})</Text> : null}
          </Box>
        ))}
      </Box>
      <Box marginTop={1}>
        <Text color="gray" dimColor>
          {running ? 'probing…' : 'press r to refresh, esc to go back'}
        </Text>
      </Box>
    </Box>
  );
}
