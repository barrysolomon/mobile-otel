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

/** Big switch — see types.ts `Screen` for the full union. */
function ScreenRouter(props: ScreenProps) {
  switch (props.state.screen) {
    case 'home':             return <HomeScreen {...props} />;
    case 'status':           return <StatusScreen {...props} />;
    case 'scenarios':        return <ScenariosScreen {...props} />;
    case 'networkRestored':  return <NetworkRestoredScreen {...props} />;
    case 'uatCell':          return <UatCellScreen {...props} />;
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

  return (
    <Box flexDirection="column" width={columns} height={rows}>
      <Banner width={columns} serial={process.env.DCC_SERIAL} exportTarget={process.env.DCC_TARGET ?? 'dash0'} exportMode={process.env.DCC_MODE ?? 'CONDITIONAL'} />
      <Box width={columns} height={contentHeight} overflow="hidden">
        <ScreenRouter state={state} setState={setState} />
      </Box>
      <Footer width={columns} screen={state.screen} status={state.status} />
    </Box>
  );
}
