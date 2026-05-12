import React, { useState } from 'react';
import { Box, useApp, useInput } from 'ink';
import { initialState, navigate, back, type AppState, type Screen, type ScreenProps } from './types.js';
import { Banner, BANNER_HEIGHT } from './Banner.js';
import { Footer, FOOTER_HEIGHT } from './Footer.js';
import { useTerminalSize } from './hooks/useTerminalSize.js';
import { HomeScreen } from './screens/HomeScreen.js';
import { StatusScreen } from './screens/StatusScreen.js';
import { ScenariosScreen } from './screens/ScenariosScreen.js';
import { NetworkRestoredScreen } from './screens/NetworkRestoredScreen.js';
import { UatCellScreen } from './screens/UatCellScreen.js';
import { HelpScreen } from './screens/HelpScreen.js';
import { DeviceScreen } from './screens/DeviceScreen.js';
import { PlatformScreen } from './screens/PlatformScreen.js';
import { ModeScreen } from './screens/ModeScreen.js';
import { TargetScreen } from './screens/TargetScreen.js';
import { OptionsScreen } from './screens/OptionsScreen.js';

/** Big switch — see types.ts `Screen` for the full union. */
function ScreenRouter(props: ScreenProps) {
  switch (props.state.screen) {
    case 'home':             return <HomeScreen {...props} />;
    case 'status':           return <StatusScreen {...props} />;
    case 'scenarios':        return <ScenariosScreen {...props} />;
    case 'networkRestored':  return <NetworkRestoredScreen {...props} />;
    case 'uatCell':          return <UatCellScreen {...props} />;
    case 'devices':          return <DeviceScreen {...props} />;
    case 'platform':         return <PlatformScreen {...props} />;
    case 'mode':             return <ModeScreen {...props} />;
    case 'target':           return <TargetScreen {...props} />;
    case 'options':          return <OptionsScreen {...props} />;
    case 'help':             return <HelpScreen {...props} />;
    case 'error':            return <HomeScreen {...props} />; // TODO: dedicated error screen
  }
}

export function App() {
  const [state, setState] = useState<AppState>(initialState);
  const { columns, rows } = useTerminalSize();
  const { exit } = useApp();

  // Global hotkeys. Each screen owns its own local useInput for screen-specific keys.
  useInput((input, key) => {
    if (key.ctrl && input === 'c') { exit(); return; }
    if (input === 'q' && state.screen === 'home') { exit(); return; }
    if (key.escape && state.screen !== 'home') { setState(back(state)); return; }
    if (input === '?') { setState(navigate(state, 'help')); return; }
  });

  const contentHeight = Math.max(1, rows - BANNER_HEIGHT - FOOTER_HEIGHT);

  const deviceSummary = state.runConfig.devices.length === 0
    ? 'none'
    : `${state.runConfig.devices.length}× ${state.runConfig.devices.slice(0, 2).join(',')}${state.runConfig.devices.length > 2 ? '…' : ''}`;

  return (
    <Box flexDirection="column" width={columns} height={rows}>
      <Banner
        width={columns}
        serial={deviceSummary}
        exportTarget={state.runConfig.target === 'custom' ? (state.runConfig.customEndpoint || 'custom (unset)') : state.runConfig.target}
        exportMode={state.runConfig.mode}
      />
      <Box width={columns} height={contentHeight} overflow="hidden">
        <ScreenRouter state={state} setState={setState} />
      </Box>
      <Footer width={columns} screen={state.screen} status={state.status} />
    </Box>
  );
}
