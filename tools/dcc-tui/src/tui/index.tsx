#!/usr/bin/env node
/**
 * Demo Control Center — Ink+React TUI.
 *
 * Companion to scripts/test/demo-control-center.sh. Same authoritative
 * scenarios, prettier shell.
 *
 * Run: cd tools/dcc-tui && npm install && npm run dev
 * Build: npm run build && node dist/tui/index.js
 *
 * Architecture references types.ts (Screen union, navigate/back, AppState),
 * App.tsx (router + global hotkeys), and the screens/ directory. See HelpScreen
 * for the in-app cheat sheet.
 */
import React from 'react';
import { render } from 'ink';
import { App } from './App.js';

// Alt-screen-buffer: take over the terminal so we paint the whole canvas, then
// restore it on exit. Ink does the inverse on render unmount, so we just open
// the alt-screen, render, and let Ink handle cleanup.
process.stdout.write('\x1B[?1049h');
const restoreAltScreen = () => {
  process.stdout.write('\x1B[?1049l');
};
process.on('exit', restoreAltScreen);
process.on('SIGINT', () => { restoreAltScreen(); process.exit(130); });

render(<App />);
