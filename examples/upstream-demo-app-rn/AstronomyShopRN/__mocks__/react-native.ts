/**
 * Minimal RN shim for Jest. We only need enough surface for the demo's unit
 * tests to run — real UI testing is left for Detox E2E (RN-050).
 *
 * By remapping `react-native` to this file via jest.moduleNameMapper, we
 * avoid pulling in the full RN Flow-typed source tree.
 */

class FakeEmitter {
  private listeners = new Map<string, Array<(...args: unknown[]) => void>>();
  addListener(event: string, listener: (...args: unknown[]) => void) {
    const arr = this.listeners.get(event) ?? [];
    arr.push(listener);
    this.listeners.set(event, arr);
    return {remove: () => {}};
  }
  emit(event: string, ...args: unknown[]) {
    for (const l of this.listeners.get(event) ?? []) l(...args);
  }
}

export const AppState = {
  currentState: 'active' as 'active' | 'background' | 'inactive',
  addEventListener: (event: string, _listener: (...args: unknown[]) => void) => ({
    remove: () => {},
  }),
};

export const Platform = {
  OS: 'ios' as 'ios' | 'android',
  select: <T>(spec: {ios?: T; android?: T; default?: T}): T | undefined =>
    spec.ios ?? spec.default,
};

export const NativeModules: Record<string, unknown> = {};

export const DeviceEventEmitter = new FakeEmitter();

export const NativeEventEmitter = FakeEmitter;

export default {AppState, Platform, NativeModules, DeviceEventEmitter, NativeEventEmitter};
