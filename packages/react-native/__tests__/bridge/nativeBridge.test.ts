/**
 * RN-011 behavioral tests for `NativeBridge`.
 *
 * Contract under test:
 *   - debounced batching: multiple emit() within DEBOUNCE_MS flush as ONE emitBatch call
 *   - insertion order preserved across the bridge
 *   - flush() forces an immediate batch send even if the debounce hasn't fired
 *   - emitBatch rejection triggers exponential-backoff retry (no event loss)
 *   - queue cap: dropping OLDEST events (not newest) when MAX_QUEUE exceeded
 *
 * The implementation lives at `src/bridge/NativeBridge.ts`.
 */

import { NativeBridge, DEBOUNCE_MS, MAX_QUEUE } from '../../src/bridge/NativeBridge';
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

type BatchSpy = jest.Mock<Promise<void>, [BridgePayload[]]>;

function makeMockNative(emitImpl?: BatchSpy): {
  native: NativeDash0MobileModule;
  emitBatch: BatchSpy;
  start: jest.Mock<Promise<void>, [StartConfig]>;
  flushWindow: jest.Mock<Promise<void>, [number]>;
  shutdown: jest.Mock<Promise<void>, []>;
} {
  const emitBatch: BatchSpy =
    emitImpl ??
    (jest.fn<Promise<void>, [BridgePayload[]]>(async () => {}) as BatchSpy);
  const start = jest.fn<Promise<void>, [StartConfig]>(async () => {});
  const flushWindow = jest.fn<Promise<void>, [number]>(async () => {});
  const shutdown = jest.fn<Promise<void>, []>(async () => {});
  return {
    native: { start, emitBatch, flushWindow, shutdown },
    emitBatch,
    start,
    flushWindow,
    shutdown,
  };
}

describe('NativeBridge — debounced batching', () => {
  beforeEach(() => {
    jest.useFakeTimers();
  });
  afterEach(() => {
    jest.useRealTimers();
  });

  it('batches multiple emit() calls within the debounce window into one emitBatch', async () => {
    const { native, emitBatch } = makeMockNative();
    const bridge = new NativeBridge(native);

    bridge.emit(makeLog('a'));
    bridge.emit(makeLog('b'));
    bridge.emit(makeLog('c'));

    expect(emitBatch).not.toHaveBeenCalled();

    jest.advanceTimersByTime(DEBOUNCE_MS + 1);
    await Promise.resolve();

    expect(emitBatch).toHaveBeenCalledTimes(1);
    const [batch] = emitBatch.mock.calls[0];
    expect(batch).toHaveLength(3);
    expect(batch.map(p => (p as LogPayload).name)).toEqual(['a', 'b', 'c']);
  });

  it('starts a fresh batch after a flush cycle', async () => {
    const { native, emitBatch } = makeMockNative();
    const bridge = new NativeBridge(native);

    bridge.emit(makeLog('first'));
    jest.advanceTimersByTime(DEBOUNCE_MS + 1);
    await Promise.resolve();

    bridge.emit(makeLog('second'));
    jest.advanceTimersByTime(DEBOUNCE_MS + 1);
    await Promise.resolve();

    expect(emitBatch).toHaveBeenCalledTimes(2);
    expect((emitBatch.mock.calls[0][0][0] as LogPayload).name).toBe('first');
    expect((emitBatch.mock.calls[1][0][0] as LogPayload).name).toBe('second');
  });
});

describe('NativeBridge — flush()', () => {
  beforeEach(() => {
    jest.useFakeTimers();
  });
  afterEach(() => {
    jest.useRealTimers();
  });

  it('forces pending events to send immediately', async () => {
    const { native, emitBatch } = makeMockNative();
    const bridge = new NativeBridge(native);

    bridge.emit(makeLog('a'));
    bridge.emit(makeLog('b'));
    expect(emitBatch).not.toHaveBeenCalled();

    await bridge.flush();

    expect(emitBatch).toHaveBeenCalledTimes(1);
    expect(emitBatch.mock.calls[0][0]).toHaveLength(2);
  });

  it('is a no-op when queue is empty', async () => {
    const { native, emitBatch } = makeMockNative();
    const bridge = new NativeBridge(native);
    await bridge.flush();
    expect(emitBatch).not.toHaveBeenCalled();
  });
});

describe('NativeBridge — retry on failure', () => {
  beforeEach(() => {
    jest.useFakeTimers();
  });
  afterEach(() => {
    jest.useRealTimers();
  });

  it('retries with exponential backoff when emitBatch rejects, re-sending the same events', async () => {
    let call = 0;
    const emitBatch = jest.fn<Promise<void>, [BridgePayload[]]>(async () => {
      call += 1;
      if (call < 3) throw new Error('bridge transient fail');
    });
    const { native } = makeMockNative(emitBatch);
    const bridge = new NativeBridge(native);

    bridge.emit(makeLog('a'));

    // flush() kicks off the retry chain. We must not await it here — under
    // fake timers the retry's internal setTimeout(delay) would deadlock. We
    // advance timers manually and drain microtasks between each step, then
    // await the flush promise at the end.
    const flushDone = bridge.flush();

    // First attempt runs synchronously after microtask drain.
    await Promise.resolve();
    await Promise.resolve();
    expect(emitBatch).toHaveBeenCalledTimes(1);

    // Backoff 1: 100ms
    jest.advanceTimersByTime(100);
    await Promise.resolve();
    await Promise.resolve();
    expect(emitBatch).toHaveBeenCalledTimes(2);

    // Backoff 2: 200ms
    jest.advanceTimersByTime(200);
    await Promise.resolve();
    await Promise.resolve();
    expect(emitBatch).toHaveBeenCalledTimes(3);

    await flushDone;

    for (const [batch] of emitBatch.mock.calls) {
      expect(batch).toHaveLength(1);
      expect((batch[0] as LogPayload).name).toBe('a');
    }
  });
});

describe('NativeBridge — queue backpressure', () => {
  beforeEach(() => {
    jest.useFakeTimers();
  });
  afterEach(() => {
    jest.useRealTimers();
  });

  it('drops the OLDEST events when queue exceeds MAX_QUEUE', async () => {
    const { native, emitBatch } = makeMockNative();
    const bridge = new NativeBridge(native);

    // Fill queue to MAX_QUEUE + 5.
    for (let i = 0; i < MAX_QUEUE + 5; i++) {
      bridge.emit(makeLog(`evt-${i}`));
    }

    await bridge.flush();

    expect(emitBatch).toHaveBeenCalledTimes(1);
    const [batch] = emitBatch.mock.calls[0];
    expect(batch).toHaveLength(MAX_QUEUE);

    // Oldest 5 dropped → first kept name is evt-5, last is evt-(MAX_QUEUE+4)
    expect((batch[0] as LogPayload).name).toBe('evt-5');
    expect((batch[batch.length - 1] as LogPayload).name).toBe(
      `evt-${MAX_QUEUE + 4}`,
    );
  });
});
