/**
 * AutoDemoDriver — drives synthetic shop traffic when DASH0_AUTO_DEMO=1.
 *
 * Mirrors examples/upstream-demo-app-ios/AstronomyShop/AutoDemoDriver.swift:
 *   - Cadence: 800ms per tick
 *   - Cycle: 3 browse+add phases → 1 checkout → 1 idle → repeat
 *   - Rotates through the catalog so the demo is never boring
 *
 * Parameterized over `TelemetryRecorder` so unit tests can verify call
 * sequencing without hitting the real SDK. Production code wires a thin
 * adapter to Dash0Mobile.* / ShopTelemetry.*.
 */

import {CATALOG} from './Product';

export const AUTO_DEMO_CADENCE_MS = 800;

export interface TelemetryRecorder {
  viewProduct(id: string): void;
  addToCart(id: string, qty: number): void;
  checkout(): Promise<void>;
}

type Phase =
  | {kind: 'browse'; productIndex: number}
  | {kind: 'checkout'}
  | {kind: 'idle'};

function buildCycle(startIndex: number): Phase[] {
  const catalogSize = CATALOG.length;
  return [
    {kind: 'browse', productIndex: startIndex % catalogSize},
    {kind: 'browse', productIndex: (startIndex + 1) % catalogSize},
    {kind: 'browse', productIndex: (startIndex + 2) % catalogSize},
    {kind: 'checkout'},
    {kind: 'idle'},
  ];
}

export class AutoDemoDriver {
  private readonly recorder: TelemetryRecorder;
  private timer: ReturnType<typeof setInterval> | null = null;
  private cycle: Phase[] = buildCycle(0);
  private cycleStart = 0;
  private step = 0;

  constructor(recorder: TelemetryRecorder) {
    this.recorder = recorder;
  }

  start(): void {
    if (this.timer !== null) return; // idempotent
    this.timer = setInterval(() => {
      void this.tick();
    }, AUTO_DEMO_CADENCE_MS);
  }

  stop(): void {
    if (this.timer !== null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  private async tick(): Promise<void> {
    const phase = this.cycle[this.step];
    this.step += 1;
    if (this.step >= this.cycle.length) {
      // advance cycle start by 2 so we rotate through the catalog
      this.cycleStart = (this.cycleStart + 2) % CATALOG.length;
      this.cycle = buildCycle(this.cycleStart);
      this.step = 0;
    }

    switch (phase.kind) {
      case 'browse': {
        const product = CATALOG[phase.productIndex];
        this.recorder.viewProduct(product.id);
        this.recorder.addToCart(product.id, 1);
        return;
      }
      case 'checkout':
        await this.recorder.checkout();
        return;
      case 'idle':
        return;
    }
  }
}
