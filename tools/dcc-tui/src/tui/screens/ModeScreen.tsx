import React from 'react';
import { ListPicker } from '../lib/ListPicker.js';
import { back, type ScreenProps, type ExportMode } from '../types.js';

const items: Array<{ value: ExportMode; label: string; hint: string }> = [
  { value: 'CONTINUOUS',  label: 'CONTINUOUS',  hint: 'periodic flush; events stream out as they happen (highest data volume)' },
  { value: 'CONDITIONAL', label: 'CONDITIONAL', hint: 'silent buffer; export only when a policy trigger matches (battery-efficient default)' },
  { value: 'HYBRID',      label: 'HYBRID',      hint: 'periodic heartbeat + on-trigger flush; spans co-exported alongside logs' },
];

export function ModeScreen({ state, setState }: ScreenProps) {
  return (
    <ListPicker
      title="Export Mode"
      items={items}
      selected={state.runConfig.mode}
      onSelect={(value) =>
        setState({ ...state, runConfig: { ...state.runConfig, mode: value }, status: { text: `mode set to ${value}`, tone: 'ok' } })
      }
      onBack={() => setState(back(state))}
    />
  );
}
