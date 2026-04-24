/**
 * Tests for the sub-ms timestamp path in Dash0Mobile's JS bridge.
 *
 * Regression coverage for BACKLOG.md "RN bridge: span durations show
 * 0ms for most child spans" — the previous `Date.now() * 1_000_000`
 * implementation collapsed nested start+end timestamps to identical
 * values because JS doubles can't hold Unix nanoseconds past
 * `Number.MAX_SAFE_INTEGER` (2^53). Spans that started and ended
 * within the same ms tick (and later: within the same ~128ns band
 * due to rounding) serialized with duration=0, breaking waterfall
 * rendering in Dash0.
 */
import {
    Dash0Mobile,
    __setNativeForTesting,
    __resetForTesting,
    __resetTimestampAnchorForTests__,
} from '../src';
import type {
    BridgePayload,
    LogPayload,
    NativeDash0MobileModule,
    SpanEndPayload,
    SpanStartPayload,
    StartConfig,
} from '../src/bridge/types';

type Mocks = {
    emitBatch: jest.Mock<Promise<void>, [BridgePayload[]]>;
};

function installMock(): Mocks {
    const start = jest.fn<Promise<void>, [StartConfig]>(async () => {});
    const emitBatch = jest.fn<Promise<void>, [BridgePayload[]]>(async () => {});
    const flushWindow = jest.fn<Promise<void>, [number]>(async () => {});
    const shutdown = jest.fn<Promise<void>, []>(async () => {});
    const native: NativeDash0MobileModule = {
        start,
        emitBatch,
        flushWindow,
        shutdown,
    };
    __setNativeForTesting(native);
    return { emitBatch };
}

async function flush(): Promise<void> {
    jest.advanceTimersByTime(60);
    await Promise.resolve();
    await Promise.resolve();
}

describe('timestamp precision', () => {
    beforeEach(() => {
        jest.useFakeTimers();
        __resetTimestampAnchorForTests__();
    });
    afterEach(() => {
        jest.useRealTimers();
        __resetForTesting();
    });

    it('returns a string that fits full Unix nanosecond precision (past 2^53)', async () => {
        const { emitBatch } = installMock();
        await Dash0Mobile.start({ serviceName: 's', endpoint: 'e' });
        Dash0Mobile.log('x');
        await flush();

        const batch = emitBatch.mock.calls[0][0];
        const log = batch[0] as LogPayload;
        const t = log.timeUnixNano;

        // Current time in nanoseconds has 19 digits (~1.8e18 in 2026).
        // MAX_SAFE_INTEGER is 16 digits. Casting through `Number` and back
        // would round. BigInt round-trip must preserve every digit.
        expect(t.length).toBeGreaterThanOrEqual(19);
        expect(BigInt(t).toString()).toBe(t);

        // Sanity: within a reasonable window of Date.now() in nanoseconds.
        // ±5 seconds of skew between the bridge and the test harness.
        const now = BigInt(Date.now()) * 1_000_000n;
        const delta = now > BigInt(t) ? now - BigInt(t) : BigInt(t) - now;
        expect(delta < 5n * 1_000_000_000n).toBe(true);
    });

    it('consecutive startSpan timestamps are non-decreasing', async () => {
        const { emitBatch } = installMock();
        await Dash0Mobile.start({ serviceName: 's', endpoint: 'e' });

        // Fire 50 spans in a tight synchronous loop. Pre-fix, most would
        // collapse to the same ms tick (Date.now inside a single event-loop
        // turn). Post-fix, performance.now gives sub-ms resolution.
        for (let i = 0; i < 50; i++) {
            Dash0Mobile.startSpan(`span.${i}`).end();
        }
        await flush();

        const payloads: BridgePayload[] = emitBatch.mock.calls.flatMap((c) => c[0]);
        const starts = payloads.filter((p) => (p as { kind: string }).kind === 'spanStart') as SpanStartPayload[];
        expect(starts.length).toBe(50);

        for (let i = 1; i < starts.length; i++) {
            const prev = BigInt(starts[i - 1].startTimeUnixNano);
            const curr = BigInt(starts[i].startTimeUnixNano);
            expect(curr >= prev).toBe(true);
        }
    });

    it('startSpan/end pair produces non-zero duration when ms tick elapses', async () => {
        // Advance fake time by 5ms between start and end. Pre-fix, this
        // would produce a 5ms duration; post-fix, the sub-ms resolution
        // comes through `performance.now()` which Jest's fake timers also
        // advance — so this mainly asserts that the start->end ordering
        // and the BigInt math are correct under a small non-zero interval.
        const { emitBatch } = installMock();
        await Dash0Mobile.start({ serviceName: 's', endpoint: 'e' });

        const h = Dash0Mobile.startSpan('immediate');
        jest.advanceTimersByTime(5);
        h.end();
        await flush();

        const payloads: BridgePayload[] = emitBatch.mock.calls.flatMap((c) => c[0]);
        const start = payloads.find((p) => (p as { kind: string }).kind === 'spanStart') as SpanStartPayload;
        const end = payloads.find((p) => (p as { kind: string }).kind === 'spanEnd') as SpanEndPayload;
        expect(start).toBeDefined();
        expect(end).toBeDefined();

        const startNs = BigInt(start.startTimeUnixNano);
        const endNs = BigInt(end.endTimeUnixNano);
        expect(endNs > startNs).toBe(true);
        // 5ms in nanoseconds is 5_000_000.
        expect(endNs - startNs).toBeGreaterThanOrEqual(5_000_000n);
    });

    it('anchor is established once and shared across emits', async () => {
        const { emitBatch } = installMock();
        await Dash0Mobile.start({ serviceName: 's', endpoint: 'e' });

        // Two successive emits must reference the same epoch anchor +
        // monotonic perf delta — their difference is elapsed time, not a
        // jump to a later Date.now() tick.
        Dash0Mobile.log('first');
        Dash0Mobile.log('second');
        await flush();

        const payloads: BridgePayload[] = emitBatch.mock.calls.flatMap((c) => c[0]);
        const logs = payloads.filter((p) => (p as { kind: string }).kind === 'log') as LogPayload[];
        expect(logs.length).toBe(2);

        const t1 = BigInt(logs[0].timeUnixNano);
        const t2 = BigInt(logs[1].timeUnixNano);
        expect(t2 >= t1).toBe(true);
        // Sub-second gap — a re-captured anchor could produce a much larger
        // gap if the second read landed on a new Date.now() tick boundary.
        expect(t2 - t1 < 1_000_000_000n).toBe(true);
    });

    it('falls back gracefully when global.performance is missing', async () => {
        const g = globalThis as { performance?: unknown };
        const saved = g.performance;
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        (g as any).performance = undefined;
        __resetTimestampAnchorForTests__();

        try {
            const { emitBatch } = installMock();
            await Dash0Mobile.start({ serviceName: 's', endpoint: 'e' });
            Dash0Mobile.log('no-perf');
            await flush();

            const batch = emitBatch.mock.calls[0][0];
            const log = batch[0] as LogPayload;
            expect(typeof log.timeUnixNano).toBe('string');
            expect(BigInt(log.timeUnixNano).toString()).toBe(log.timeUnixNano);
            expect(log.timeUnixNano.length).toBeGreaterThanOrEqual(19);
        } finally {
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            (g as any).performance = saved;
        }
    });
});
