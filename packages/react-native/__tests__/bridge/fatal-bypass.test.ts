/**
 * RN Gate 3 fix — FATAL-severity logs bypass the 50ms debounce.
 *
 * Contract under test:
 *   - Dash0Mobile.log(..., severity=21 FATAL) triggers an immediate
 *     bridge.flush() so the payload crosses the RN bridge before the
 *     next tick. Process-crash paths depend on this: if the JS handler
 *     chain continues into RN's redbox/fatal reporter, the process
 *     may die before any 50ms setTimeout fires.
 *   - Non-FATAL severities (INFO/WARN/ERROR) still go through the
 *     debounce as before — no regression on the hot path.
 *
 * See docs/superpowers/specs/2026-04-22-rn-fatal-bridge-bypass-design.md.
 */

import {
  Dash0Mobile,
  __setNativeForTesting,
  __resetForTesting,
} from '../../src';
import type {
  BridgePayload,
  NativeDash0MobileModule,
} from '../../src/bridge/types';

type BatchSpy = jest.Mock<Promise<void>, [BridgePayload[]]>;

function makeFakeNative(): {
  native: NativeDash0MobileModule;
  emitBatch: BatchSpy;
} {
  const emitBatch: BatchSpy = jest.fn<Promise<void>, [BridgePayload[]]>(
    async () => {},
  );
  const native: NativeDash0MobileModule = {
    async start() {},
    emitBatch,
    async flushWindow() {},
    async shutdown() {},
  };
  return { native, emitBatch };
}

const SEVERITY_INFO = 9;
const SEVERITY_ERROR = 17;
const SEVERITY_FATAL = 21;

describe('FATAL-severity bridge bypass', () => {
  beforeEach(() => {
    jest.useFakeTimers();
  });

  afterEach(async () => {
    jest.useRealTimers();
    try {
      await Dash0Mobile.shutdown();
    } catch {
      // ignore
    }
    __resetForTesting();
  });

  it('FATAL log triggers emitBatch on the current stack frame (no microtask boundary)', async () => {
    const { native, emitBatch } = makeFakeNative();
    __setNativeForTesting(native);

    await Dash0Mobile.start({
      serviceName: 'rn-fatal-test',
      endpoint: 'https://collector.example.com:4317',
      // Keep auto-instrumentation off — testing only the bridge bypass
      // contract, not any particular auto-capture path.
      autoCapture: { network: false, errors: false },
    });
    emitBatch.mockClear();

    Dash0Mobile.log('app.error', { 'exception.type': 'Error' }, SEVERITY_FATAL);

    // No `await` — asserting that emitBatch was called on the current
    // stack frame, BEFORE any microtask runs. This is the critical
    // property for crash-path survival: on a JS throw, the ErrorUtils
    // handler's continuation reaches RN's fatal reporter synchronously
    // and terminates the process before the microtask queue drains.
    // A previous version of this fix used `void bridge.flush()`, which
    // introduced an `await this.drain()` microtask boundary and lost
    // the payload on real crashes — device validation exposed it.
    expect(emitBatch).toHaveBeenCalledTimes(1);
    const batch = emitBatch.mock.calls[0][0];
    expect(batch).toHaveLength(1);
    expect(batch[0]).toMatchObject({
      kind: 'log',
      name: 'app.error',
      severity: SEVERITY_FATAL,
    });
  });

  it('FATAL log flushes any queued non-FATAL payloads alongside itself', async () => {
    const { native, emitBatch } = makeFakeNative();
    __setNativeForTesting(native);

    await Dash0Mobile.start({
      serviceName: 'rn-fatal-drain-test',
      endpoint: 'https://collector.example.com:4317',
      autoCapture: { network: false, errors: false },
    });
    emitBatch.mockClear();

    // Queue two non-FATAL logs (they sit in the JS queue behind the 50ms timer).
    Dash0Mobile.log('app.info.1', {}, SEVERITY_INFO);
    Dash0Mobile.log('app.warn', {}, 13);

    // Non-FATAL alone shouldn't have fired yet.
    expect(emitBatch).not.toHaveBeenCalled();

    // Now a FATAL arrives. It must drain everything synchronously —
    // otherwise the queued logs die with the process.
    Dash0Mobile.log('app.error', { 'exception.type': 'Error' }, SEVERITY_FATAL);

    expect(emitBatch).toHaveBeenCalledTimes(1);
    const batch = emitBatch.mock.calls[0][0];
    expect(batch).toHaveLength(3);
    expect(batch.map(p => (p as { name?: string }).name)).toEqual([
      'app.info.1',
      'app.warn',
      'app.error',
    ]);
  });

  it('non-FATAL log does NOT trigger emitBatch until the debounce fires', async () => {
    const { native, emitBatch } = makeFakeNative();
    __setNativeForTesting(native);

    await Dash0Mobile.start({
      serviceName: 'rn-non-fatal-test',
      endpoint: 'https://collector.example.com:4317',
      autoCapture: { network: false, errors: false },
    });
    emitBatch.mockClear();

    Dash0Mobile.log('app.info', {}, SEVERITY_INFO);
    Dash0Mobile.log('app.error', {}, SEVERITY_ERROR);

    // Before the debounce fires, nothing has crossed to native.
    await Promise.resolve();
    expect(emitBatch).not.toHaveBeenCalled();

    // After the debounce, both payloads drain in ONE batch.
    await jest.advanceTimersByTimeAsync(100);
    expect(emitBatch).toHaveBeenCalledTimes(1);
    const batch = emitBatch.mock.calls[0][0];
    expect(batch).toHaveLength(2);
    expect(batch[0]).toMatchObject({ name: 'app.info', severity: SEVERITY_INFO });
    expect(batch[1]).toMatchObject({ name: 'app.error', severity: SEVERITY_ERROR });
  });
});
