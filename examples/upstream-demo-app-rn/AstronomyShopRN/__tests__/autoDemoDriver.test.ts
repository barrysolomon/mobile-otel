/**
 * RN-041 tests — AutoDemoDriver drives browse/cart/checkout traffic on a
 * timer. Mirrors the iOS AutoDemoDriver.swift behavior: 800ms cadence,
 * 5 phases per cycle (3× view+add → checkout → idle).
 *
 * Tests verify behavior against a mock ShopTelemetry recorder rather than
 * the real SDK — keeps them fast and deterministic.
 */

import {AutoDemoDriver, AUTO_DEMO_CADENCE_MS} from '../src/shop/AutoDemoDriver';
import type {TelemetryRecorder} from '../src/shop/AutoDemoDriver';

function makeRecorder(): TelemetryRecorder & {
  views: string[];
  adds: Array<{id: string; qty: number}>;
  checkouts: number;
} {
  const rec = {
    views: [] as string[],
    adds: [] as Array<{id: string; qty: number}>,
    checkouts: 0,
    viewProduct(id: string) {
      rec.views.push(id);
    },
    addToCart(id: string, qty: number) {
      rec.adds.push({id, qty});
    },
    async checkout() {
      rec.checkouts += 1;
    },
  };
  return rec;
}

describe('AutoDemoDriver', () => {
  beforeEach(() => {
    jest.useFakeTimers();
  });
  afterEach(() => {
    jest.useRealTimers();
  });

  it('does nothing until start() is called', () => {
    const rec = makeRecorder();
    new AutoDemoDriver(rec);
    jest.advanceTimersByTime(AUTO_DEMO_CADENCE_MS * 10);
    expect(rec.views).toHaveLength(0);
    expect(rec.adds).toHaveLength(0);
    expect(rec.checkouts).toBe(0);
  });

  it('advances one phase per tick at AUTO_DEMO_CADENCE_MS cadence', () => {
    const rec = makeRecorder();
    const driver = new AutoDemoDriver(rec);
    driver.start();

    // Tick 1: view+add product 0
    jest.advanceTimersByTime(AUTO_DEMO_CADENCE_MS);
    expect(rec.views).toHaveLength(1);
    expect(rec.adds).toHaveLength(1);

    // Tick 2: view+add product 1
    jest.advanceTimersByTime(AUTO_DEMO_CADENCE_MS);
    expect(rec.views).toHaveLength(2);
    expect(rec.adds).toHaveLength(2);

    // Tick 3: view+add product 2
    jest.advanceTimersByTime(AUTO_DEMO_CADENCE_MS);
    expect(rec.views).toHaveLength(3);
    expect(rec.adds).toHaveLength(3);
    expect(rec.checkouts).toBe(0);

    driver.stop();
  });

  it('fires a checkout after 3 browse-and-add phases', async () => {
    const rec = makeRecorder();
    const driver = new AutoDemoDriver(rec);
    driver.start();

    // 3 browse phases
    jest.advanceTimersByTime(AUTO_DEMO_CADENCE_MS * 3);
    // 4th tick is checkout
    jest.advanceTimersByTime(AUTO_DEMO_CADENCE_MS);
    await Promise.resolve();
    await Promise.resolve();

    expect(rec.checkouts).toBe(1);
    driver.stop();
  });

  it('has an idle phase before looping back to browse', async () => {
    const rec = makeRecorder();
    const driver = new AutoDemoDriver(rec);
    driver.start();

    // 3 browse + 1 checkout + 1 idle = 5 ticks
    jest.advanceTimersByTime(AUTO_DEMO_CADENCE_MS * 5);
    await Promise.resolve();
    const viewsAfterIdle = rec.views.length;
    const addsAfterIdle = rec.adds.length;

    // Next tick should resume browsing (starts cycle 2)
    jest.advanceTimersByTime(AUTO_DEMO_CADENCE_MS);
    expect(rec.views.length).toBe(viewsAfterIdle + 1);
    expect(rec.adds.length).toBe(addsAfterIdle + 1);

    driver.stop();
  });

  it('stop() prevents further ticks', () => {
    const rec = makeRecorder();
    const driver = new AutoDemoDriver(rec);
    driver.start();
    jest.advanceTimersByTime(AUTO_DEMO_CADENCE_MS * 2);
    driver.stop();
    const viewsAtStop = rec.views.length;

    jest.advanceTimersByTime(AUTO_DEMO_CADENCE_MS * 10);
    expect(rec.views.length).toBe(viewsAtStop);
  });

  it('rotates through the catalog (does not stick on product 0)', () => {
    const rec = makeRecorder();
    const driver = new AutoDemoDriver(rec);
    driver.start();

    // Two full cycles = 10 ticks = 6 browse phases total
    jest.advanceTimersByTime(AUTO_DEMO_CADENCE_MS * 10);

    const unique = new Set(rec.views);
    expect(unique.size).toBeGreaterThan(1);

    driver.stop();
  });

  it('start() is idempotent — second call does not double-schedule', () => {
    const rec = makeRecorder();
    const driver = new AutoDemoDriver(rec);
    driver.start();
    driver.start();
    jest.advanceTimersByTime(AUTO_DEMO_CADENCE_MS);
    expect(rec.views).toHaveLength(1);
    driver.stop();
  });
});
