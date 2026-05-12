import React from 'react';
import { ListPicker } from '../lib/ListPicker.js';
import { back, type ScreenProps, type ExportTarget } from '../types.js';

const items: Array<{ value: ExportTarget; label: string; hint: string }> = [
  { value: 'dash0',  label: 'Dash0',           hint: 'production ingress (otel-config.json must be filled in)' },
  { value: 'local',  label: 'Local Collector', hint: 'docker compose up; ports 14317/14318' },
  { value: 'custom', label: 'Custom Endpoint', hint: 'set via Options → custom endpoint field' },
];

export function TargetScreen({ state, setState }: ScreenProps) {
  return (
    <ListPicker
      title="Export Target"
      items={items}
      selected={state.runConfig.target}
      onSelect={(value) =>
        setState({ ...state, runConfig: { ...state.runConfig, target: value }, status: { text: `target set to ${value}`, tone: 'ok' } })
      }
      onBack={() => setState(back(state))}
    />
  );
}
