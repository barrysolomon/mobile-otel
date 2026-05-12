import React from 'react';
import { ListPicker } from '../lib/ListPicker.js';
import { back, type ScreenProps, type Platform } from '../types.js';

const items: Array<{ value: Platform; label: string; hint: string }> = [
  { value: 'android',    label: 'Android native', hint: 'otel-android-mobile via demo-app on emulators' },
  { value: 'ios',        label: 'iOS native',     hint: 'otel-ios-mobile via Schedulr.xcodeproj on simulators' },
  { value: 'rn-android', label: 'RN Android',     hint: 'packages/react-native bridged into upstream-demo-app-rn (Android)' },
  { value: 'rn-ios',     label: 'RN iOS',         hint: 'packages/react-native bridged into upstream-demo-app-rn (iOS)' },
];

export function PlatformScreen({ state, setState }: ScreenProps) {
  return (
    <ListPicker
      title="Platform"
      items={items}
      selected={state.runConfig.platform}
      onSelect={(value) =>
        setState({ ...state, runConfig: { ...state.runConfig, platform: value }, status: { text: `platform set to ${value}`, tone: 'ok' } })
      }
      onBack={() => setState(back(state))}
    />
  );
}
