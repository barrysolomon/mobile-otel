/**
 * Concurrency + retry-exhaustion tests for NativeBridge. These fill in gaps
 * left by `nativeBridge.test.ts` which covers happy-path batching, flush,
 * single retry cycle, and MAX_QUEUE eviction.
 *
 * Specifically: events emitted during an in-flight drain, retry exhaustion
 * (no crash), and re-triggering a fresh batch window after flush.
 */

import { NativeBridge, DEBOUNCE_MS } from '../../src/bridge/NativeBridge';
import type {
  BridgePayload,
  LogPayload,
  NativeDash0MobileModule,
  StartConfig,
} from '../../src/bridge/types';

const makeLog = (name: string): LogPayload => ({
  kind: 'log',
  name,
  severity: 9,
  attributes: {},
  timeUnixNano: '0',
});

function makeMockNative(
  emitImpl?: jest.Mock<Promise<void>, [BridgePayload[]]>,
): {
  native: NativeDash0MobileModule;
  emitBatch: jest.Mock<Promise<void>, [BridgePayload[]]>;
} {
  const emitBatch =
    emitImpl ??
    jest.fn<Promise<void>, [BridgePayload[]]>(async () => {});
  return {
    native: {
      start: jest.fn<Promise<void>, [StartConfig]>(async () => {}),
      emitBatch,
      flushWindow: jest.fn<Promise<void>, [number]>(async () => {}),
      shutdown: jest.fn<Promise<void>, []>(async () => {}),
      startJourney: jest.fn<Promise<string>, [string]>(async () => 'mock-journey-id'),
      endJourney: jest.fn<Promise<void>, [string]>(async () => {}),
      captureScreenshot: jest.fn<Promise<void>, [string]>(async () => {}),
      captureWireframe: jest.fn<Promise<void>, [string]>(async () => {}),
    },
    emitBatch,
  };
}

describe('NativeBridge — concurrency + retry exhaustion', () => {
  beforeEach(() => {
    jest.useFakeTimers();
  });
  afterEach(() => {
    jest.useRealTimers();
  });

  it('events emitted during an in-flight drain are held and sent in the next batch (no loss)', async () => {
    // Block the first emitBatch on a manual gate so we can emit more events
    // while it is still "in flight," then assert those later events are
    // captured in a subsequent batch rather than silently dropped.
    let release!: () => void;
    const gate = new Promise<void>(resolve => {
      release = resolve;
    });
    let callIndex = 0;
    const emitBatch = jest.fn<Promise<void>, [BridgePayload[]]>(async () => {
      const thisCall = callIndex++;
      if (thisCall === 0) await gate;
    });
    const { native } = makeMockNative(emitBatch);
    const bridge = new NativeBridge(native);

    // Enqueue 2 events and trigger drain via debounce.
    bridge.emit(makeLog('a'));
    bridge.emit(makeLog('b'));
    jest.advanceTimersByTime(DEBOUNCE_MS + 1);
    await Promise.resolve();
    await Promise.resolve();

    // First batch is now awaiting the gate.
    expect(emitBatch).toHaveBeenCalledTimes(1);
    expect(emitBatch.mock.calls[0][0]).toHaveLength(2);

    // Emit more while the first drain is in flight.
    bridge.emit(makeLog('c'));
    bridge.emit(makeLog('d'));

    // Release the first drain.
    release();
    await Promise.resolve();
    await Promise.resolve();

    // A new debounce window should cover the second batch.
    jest.advanceTimersByTime(DEBOUNCE_MS + 1);
    await Promise.resolve();
    await Promise.resolve();

    expect(emitBatch).toHaveBeenCalledTimes(2);
    const secondBatch = emitBatch.mock.calls[1][0] as LogPayload[];
    expect(secondBatch.map(p => p.name)).toEqual(['c', 'd']);
  });

  it('when all 5 retry attempts fail, the batch is dropped silently (no throw, no crash)', async () => {
    const emitBatch = jest.fn<Promise<void>, [BridgePayload[]]>(async () => {
      throw new Error('permanent bridge failure');
    });
    const { native } = makeMockNative(emitBatch);
    const bridge = new NativeBridge(native);

    bridge.emit(makeLog('dropped'));

    const flushPromise = bridge.flush();

    // Each attempt drains microtasks + advances the backoff timer.
    // Backoffs: 100, 200, 400, 800 ms (between 5 attempts).
    for (const delayMs of [100, 200, 400, 800]) {
      await Promise.resolve();
      await Promise.resolve();
      jest.advanceTimersByTime(delayMs);
    }

    await expect(flushPromise).resolves.toBeUndefined();
    expect(emitBatch).toHaveBeenCalledTimes(5);
  });

  it('after a flush() with no queued events, a subsequent emit() still schedules a debounce', async () => {
    const { native, emitBatch } = makeMockNative();
    const bridge = new NativeBridge(native);

    await bridge.flush();
    expect(emitBatch).not.toHaveBeenCalled();

    bridge.emit(makeLog('after-flush'));
    jest.advanceTimersByTime(DEBOUNCE_MS + 1);
    await Promise.resolve();

    expect(emitBatch).toHaveBeenCalledTimes(1);
    expect((emitBatch.mock.calls[0][0][0] as LogPayload).name).toBe(
      'after-flush',
    );
  });
});
