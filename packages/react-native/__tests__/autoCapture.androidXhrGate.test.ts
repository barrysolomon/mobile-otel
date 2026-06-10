/**
 * Android gates the JS XMLHttpRequest shim OFF: on Android, network capture +
 * W3C traceparent injection are owned by the native OkHttp interceptor
 * (OTelNetworkInterceptor, installed pre-JS in Dash0MobilePackage). Installing
 * the JS XHR shim on top of it would double-count every request for non-Expo
 * apps and see ZERO traffic under Expo SDK 52+ (expo/fetch routes through
 * OkHttp, never the JS globals). This pins that gate via a Platform.OS mock.
 *
 * Companion to autoCapture.rnNetworkDedup.test.ts, which pins the RN-vs-web
 * dedup; this file pins the additional Android-only behavior.
 *
 * Uses the shared manual react-native mock (../__mocks__/react-native.ts) with
 * `Platform.OS = 'android'`. Centralizing the factory there avoids the flaky
 * cross-suite collision that an inline virtual mock suffered: when a sibling
 * suite (navigation.appstate) registered its own incompatible `react-native`
 * mock in the same jest worker, the inline `doMock` here was defeated and
 * `isAndroid()` saw a non-Android Platform, leaving the XHR shim wrongly
 * installed. With one shared factory, every `jest.mock('react-native')`
 * resolves to the same complete shape, so ordering can't break this gate.
 */

jest.mock('react-native', () => require('./helpers/reactNativeMock'));

import {
  Dash0Mobile,
  __setNativeForTesting,
  __resetForTesting,
} from '../src';
import type {
  BridgePayload,
  NativeDash0MobileModule,
} from '../src/bridge/types';

// Resolve the mock control from the SAME mocked module instance the SDK uses
// via `require('react-native')`.
const { resetReactNativeMock } =
  jest.requireMock('react-native') as typeof import('./helpers/reactNativeMock');

function makeFakeNative(): NativeDash0MobileModule & { emitted: BridgePayload[] } {
  const emitted: BridgePayload[] = [];
  return {
    emitted,
    async start() {},
    async emitBatch(payloads) {
      emitted.push(...payloads);
    },
    async flushWindow() {},
    async shutdown() {},
    async startJourney() { return 'mock-journey-id'; },
    async endJourney() {},
    async captureScreenshot() {},
    async captureWireframe() {},
  };
}

class StubXHR {
  open() {}
  send() {}
  addEventListener() {}
}

describe('Android JS XHR shim gate', () => {
  let originalFetch: typeof globalThis.fetch | undefined;
  let originalXHR: typeof globalThis.XMLHttpRequest | undefined;
  let originalNavigator: unknown;

  beforeEach(() => {
    // Pin Platform.OS = 'android' on the shared react-native mock so the gate
    // under test is exercised.
    resetReactNativeMock('android');
    originalFetch = globalThis.fetch;
    originalXHR = globalThis.XMLHttpRequest;
    originalNavigator = (globalThis as unknown as { navigator?: unknown }).navigator;
    (globalThis as unknown as { XMLHttpRequest: typeof StubXHR }).XMLHttpRequest = StubXHR;
    // Detected as React Native (so the RN branch is taken at all).
    (globalThis as unknown as { navigator: { product: string } }).navigator = {
      product: 'ReactNative',
    };
  });

  afterEach(() => {
    globalThis.fetch = originalFetch as typeof globalThis.fetch;
    if (originalXHR) {
      globalThis.XMLHttpRequest = originalXHR;
    } else {
      delete (globalThis as unknown as { XMLHttpRequest?: unknown }).XMLHttpRequest;
    }
    (globalThis as unknown as { navigator?: unknown }).navigator = originalNavigator;
  });

  it('on Android, network=true installs NEITHER the JS XHR shim NOR the fetch shim (native owns it)', async () => {
    __setNativeForTesting(makeFakeNative());

    const beforeFetch = globalThis.fetch;
    const beforeXHR = globalThis.XMLHttpRequest;

    try {
      await Dash0Mobile.start({
        serviceName: 'android-xhr-gate-test',
        endpoint: 'https://collector.example.com:4317',
      });

      // fetch is never wrapped on RN (RN fetch is XHR-backed), and on Android
      // the XHR shim is gated OFF — so BOTH globals must remain the originals.
      expect(globalThis.fetch).toBe(beforeFetch);
      expect(globalThis.XMLHttpRequest).toBe(beforeXHR);
    } finally {
      try {
        await Dash0Mobile.shutdown();
      } catch {
        // ignore
      }
      __resetForTesting();
    }
  });
});
