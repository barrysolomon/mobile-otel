import React from 'react';
import { Box, Text } from 'ink';

interface BannerProps {
  width: number;
  serial?: string;
  exportTarget?: string;
  exportMode?: string;
}

/** Persistent top band. Three-cell layout: title left, target middle, mode right. */
export function Banner({ width, serial, exportTarget, exportMode }: BannerProps) {
  return (
    <Box width={width} flexDirection="column" borderStyle="single" borderColor="cyan" paddingX={1}>
      <Box justifyContent="space-between">
        <Text bold color="cyan">
          dcc-tui
          <Text color="gray" dimColor>
            {' '}
            · Demo Control Center (Ink+React)
          </Text>
        </Text>
        <Box>
          <Text color="gray" dimColor>emu </Text>
          <Text color={serial ? 'green' : 'red'}>{serial ?? 'none'}</Text>
          <Text color="gray" dimColor>  ·  target </Text>
          <Text color={exportTarget === 'dash0' ? 'green' : 'yellow'}>{exportTarget ?? 'unknown'}</Text>
          <Text color="gray" dimColor>  ·  mode </Text>
          <Text color="cyan">{exportMode ?? 'unknown'}</Text>
        </Box>
      </Box>
    </Box>
  );
}

export const BANNER_HEIGHT = 3;
