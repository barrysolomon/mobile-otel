/**
 * Shared `react-native` mock factory, opted into explicitly via
 *   jest.mock('react-native', () => require('../helpers/reactNativeMock'));
 * (note the relative depth from each suite).
 *
 * Why this exists: two suites need to mock `react-native` with different
 * concerns —
 *   - autoCapture.androidXhrGate.test.ts needs `Platform.OS === 'android'`
 *     so the Android XHR-shim gate is taken.
 *   - instr/navigation.appstate.test.ts needs `AppState.addEventListener`
 *     to capture the registered change listener.
 * Each suite previously registered its OWN inline virtual `jest.mock`/`doMock`
 * with a partial, incompatible shape. Because jest's virtual-mock registry is
 * shared across files within a worker, whichever factory won the registry
 * defeated the other suite — a real flaky failure that only surfaced once both
 * suites were merged into one jest project. Routing both `jest.mock` calls
 * through this single factory means they always resolve to the SAME complete
 * shape, so the suites can no longer collide regardless of worker/file order.
 *
 * It is deliberately NOT a top-level `__mocks__/react-native` auto-mock: that
 * would be applied to EVERY suite that requires `react-native` (e.g.
 * autoCapture.rnNetworkDedup.test.ts, which depends on the real-module-absent
 * behavior). Explicit opt-in keeps the blast radius to exactly the two suites
 * that need it.
 *
 * Tests drive the mock through the exported `__rnMockState` holder; obtain it
 * from the SAME mocked module instance the SDK resolves via
 * `jest.requireMock('react-native')`, never a direct relative import (that
 * would be a different module instance and would not observe the listener).
 */

export const __rnMockState: {
  platformOS: string;
  appStateListener: ((state: string) => void) | null;
  appStateRemove: jest.Mock;
} = {
  platformOS: 'android',
  appStateListener: null,
  appStateRemove: jest.fn(),
};

export function resetReactNativeMock(os = 'android'): void {
  __rnMockState.platformOS = os;
  __rnMockState.appStateListener = null;
  __rnMockState.appStateRemove = jest.fn();
}

export const Platform = {
  get OS(): string {
    return __rnMockState.platformOS;
  },
};

export const AppState = {
  addEventListener(_type: string, listener: (state: string) => void) {
    __rnMockState.appStateListener = listener;
    return { remove: () => __rnMockState.appStateRemove() };
  },
};

// Minimal NativeModules surface so resolveNative()'s lookup is well-defined
// when this mock is active (tests inject their native via
// __setNativeForTesting, so this stays empty).
export const NativeModules: Record<string, unknown> = {};
