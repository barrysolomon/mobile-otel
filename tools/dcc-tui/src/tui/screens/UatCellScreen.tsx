import React, { useState } from 'react';
import { spawn } from 'node:child_process';
import { Box, Text, useInput } from 'ink';
import { back, type ScreenProps } from '../types.js';

type Mode = 'cont' | 'cond' | 'hyb';
type Conn = 'online' | 'offline';
type Crash = 'no' | 'yes';

export function UatCellScreen({ state, setState }: ScreenProps) {
  const [mode, setMode] = useState<Mode>('cond');
  const [conn, setConn] = useState<Conn>('online');
  const [crash, setCrash] = useState<Crash>('no');
  const [output, setOutput] = useState<string[]>([]);
  const [running, setRunning] = useState(false);

  const launch = () => {
    setRunning(true);
    const cmd = `scripts/test/uat/run-uat-cell.sh --platform=android-native --mode=${mode} --connectivity=${conn} --crash=${crash}`;
    setOutput([`$ ${cmd}`, '']);
    const child = spawn('bash', ['-c', cmd], { cwd: process.cwd() });
    child.stdout.on('data', (b) => setOutput((o) => [...o, ...b.toString().split('\n')]));
    child.stderr.on('data', (b) => setOutput((o) => [...o, ...b.toString().split('\n')]));
    child.on('exit', (code) => {
      setOutput((o) => [...o, '', `exit ${code}`]);
      setRunning(false);
    });
  };

  useInput((input, key) => {
    if (running) return;
    if (key.escape) setState(back(state));
    else if (input === '1') setMode('cont');
    else if (input === '2') setMode('cond');
    else if (input === '3') setMode('hyb');
    else if (input === 'a') setConn('online');
    else if (input === 'b') setConn('offline');
    else if (input === 'x') setCrash('no');
    else if (input === 'y') setCrash('yes');
    else if (key.return) launch();
  });

  const Row = ({ label, value, options }: { label: string; value: string; options: Array<[string, string]> }) => (
    <Box>
      <Text>{label.padEnd(14)}</Text>
      {options.map(([k, v]) => (
        <Box key={k}>
          <Text color="gray" dimColor>{k}=</Text>
          <Text color={v === value ? 'cyan' : 'gray'} bold={v === value}>{v}</Text>
          <Text>  </Text>
        </Box>
      ))}
    </Box>
  );

  return (
    <Box flexDirection="column" paddingX={2} paddingY={1}>
      <Text bold color="cyan">UAT Matrix — Single Cell</Text>
      <Box marginTop={1} flexDirection="column">
        <Row label="Mode"         value={mode}  options={[['1', 'cont'], ['2', 'cond'], ['3', 'hyb']]} />
        <Row label="Connectivity" value={conn}  options={[['a', 'online'], ['b', 'offline']]} />
        <Row label="Crash"        value={crash} options={[['x', 'no'], ['y', 'yes']]} />
      </Box>
      <Box marginTop={1}>
        <Text color="green">↵ to run · esc to back</Text>
      </Box>
      <Box marginTop={1} flexDirection="column">
        {output.slice(-12).map((line, i) => (
          <Text key={i} color="gray" dimColor>{line}</Text>
        ))}
        {running ? <Text color="yellow">running…</Text> : null}
      </Box>
    </Box>
  );
}
