import React, { useEffect, useState } from 'react';
import { Box, Text, useInput } from 'ink';
import { back, type ScreenProps, type Device } from '../types.js';
import { enumerateDevices, deviceKey, deviceRow } from '../lib/adb.js';

/**
 * Multi-select device picker. Spacebar toggles inclusion in
 * runConfig.devices. Booted emulators + offline AVDs both appear — the
 * latter are prefixed `avd:` to signal "boot first, then target".
 */
export function DeviceScreen({ state, setState }: ScreenProps) {
  const [devices, setDevices] = useState<Device[]>([]);
  const [cursor, setCursor] = useState(0);
  const [loading, setLoading] = useState(true);

  const refresh = () => {
    setLoading(true);
    void enumerateDevices().then((list) => {
      setDevices(list);
      setLoading(false);
      // clamp cursor in case the list shrank
      setCursor((c) => Math.min(c, Math.max(0, list.length - 1)));
    });
  };

  useEffect(() => { refresh(); }, []);

  const toggle = (key: string) => {
    const next = new Set(state.runConfig.devices);
    if (next.has(key)) next.delete(key); else next.add(key);
    setState({ ...state, runConfig: { ...state.runConfig, devices: Array.from(next) } });
  };

  const selectAllBooted = () => {
    const bootedKeys = devices.filter((d) => d.booted).map(deviceKey);
    setState({ ...state, runConfig: { ...state.runConfig, devices: bootedKeys } });
  };

  const clearAll = () => {
    setState({ ...state, runConfig: { ...state.runConfig, devices: [] } });
  };

  useInput((input, key) => {
    if (loading) return;
    if (key.escape) { setState(back(state)); return; }
    if (input === 'r') refresh();
    else if (input === 'a') selectAllBooted();
    else if (input === 'c') clearAll();
    else if (key.upArrow) setCursor((c) => Math.max(0, c - 1));
    else if (key.downArrow) setCursor((c) => Math.min(devices.length - 1, c + 1));
    else if (input === ' ' || key.return) {
      if (devices[cursor]) toggle(deviceKey(devices[cursor]!));
    }
  });

  const selected = new Set(state.runConfig.devices);

  return (
    <Box flexDirection="column" paddingX={2} paddingY={1}>
      <Text bold color="cyan">Select Devices</Text>
      <Text color="gray" dimColor>
        space/↵ toggle  ·  a all-booted  ·  c clear  ·  r refresh  ·  esc back
      </Text>
      <Box marginTop={1} flexDirection="column">
        {loading ? (
          <Text color="gray" dimColor>scanning adb + emulator -list-avds…</Text>
        ) : devices.length === 0 ? (
          <Text color="yellow">No devices found. Start an emulator first, then press r.</Text>
        ) : (
          devices.map((d, i) => {
            const key = deviceKey(d);
            const isSelected = selected.has(key);
            const isCursor = i === cursor;
            const checkbox = isSelected ? '☑' : '☐';
            const dotColor = d.booted ? 'green' : 'gray';
            return (
              <Box key={key}>
                <Text color={isCursor ? 'cyan' : 'gray'}>{isCursor ? '› ' : '  '}</Text>
                <Text color={isSelected ? 'cyan' : 'white'}>{checkbox}</Text>
                <Text>  </Text>
                <Text color={dotColor}>●</Text>
                <Text>  </Text>
                <Text bold={isSelected || isCursor}>{deviceRow(d)}</Text>
              </Box>
            );
          })
        )}
      </Box>
      <Box marginTop={1}>
        <Text color="gray" dimColor>
          {selected.size} selected · {devices.filter((d) => d.booted).length} booted ·{' '}
          {devices.filter((d) => !d.booted && d.avd).length} AVD(s) not running
        </Text>
      </Box>
    </Box>
  );
}
