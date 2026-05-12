import React from 'react';
import { Box, Text } from 'ink';
import type { RunConfig } from './types.js';

interface RunConfigPanelProps {
  config: RunConfig;
  compact?: boolean;
}

/**
 * Renders the current RunConfig as a labeled box. Used on Home (full size)
 * and Scenarios (compact, single-line summary).
 *
 * Compact mode fits the 3-line space below the banner — useful when a
 * screen already has a primary content panel and just wants the breadcrumb.
 */
export function RunConfigPanel({ config, compact }: RunConfigPanelProps) {
  const endpoint = config.target === 'custom' ? (config.customEndpoint || '(unset)') : config.target;
  const devices = config.devices.length === 0 ? 'none' : `${config.devices.length} (${config.devices.slice(0, 2).join(', ')}${config.devices.length > 2 ? '…' : ''})`;

  if (compact) {
    return (
      <Box>
        <Text color="gray" dimColor>platform </Text>
        <Text color="cyan">{config.platform}</Text>
        <Text color="gray" dimColor>  ·  mode </Text>
        <Text color="cyan">{config.mode}</Text>
        <Text color="gray" dimColor>  ·  target </Text>
        <Text color="cyan">{endpoint}</Text>
        <Text color="gray" dimColor>  ·  devices </Text>
        <Text color={config.devices.length > 0 ? 'cyan' : 'yellow'}>{devices}</Text>
        {config.parallel ? <Text color="gray" dimColor>  ·  parallel</Text> : null}
      </Box>
    );
  }

  return (
    <Box flexDirection="column" borderStyle="round" borderColor="gray" paddingX={1}>
      <Text bold color="cyan">Run Configuration</Text>
      <Box flexDirection="row" marginTop={0}>
        <Box flexDirection="column" width={28}>
          <Text color="gray" dimColor>Platform</Text>
          <Text color="cyan">{config.platform}</Text>
        </Box>
        <Box flexDirection="column" width={28}>
          <Text color="gray" dimColor>Export mode</Text>
          <Text color="cyan">{config.mode}</Text>
        </Box>
        <Box flexDirection="column">
          <Text color="gray" dimColor>Target</Text>
          <Text color="cyan">{endpoint}</Text>
        </Box>
      </Box>
      <Box flexDirection="row" marginTop={1}>
        <Box flexDirection="column" width={28}>
          <Text color="gray" dimColor>Devices</Text>
          <Text color={config.devices.length > 0 ? 'cyan' : 'yellow'}>{devices}</Text>
        </Box>
        <Box flexDirection="column" width={28}>
          <Text color="gray" dimColor>Parallel</Text>
          <Text color={config.parallel ? 'green' : 'gray'}>{config.parallel ? 'on' : 'off'}</Text>
        </Box>
        <Box flexDirection="column">
          <Text color="gray" dimColor>Keep app</Text>
          <Text color={config.keepApp ? 'green' : 'gray'}>{config.keepApp ? 'on' : 'off'}</Text>
        </Box>
      </Box>
    </Box>
  );
}
