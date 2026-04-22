import type { BridgePayload, NativeDash0MobileModule } from './types';

export const DEBOUNCE_MS = 50;
export const MAX_QUEUE = 10_000;
const RETRY_BASE_MS = 100;
const RETRY_MAX_ATTEMPTS = 5;

export class NativeBridge {
  private readonly native: NativeDash0MobileModule;
  private queue: BridgePayload[] = [];
  private timer: ReturnType<typeof setTimeout> | null = null;
  private inFlight: Promise<void> | null = null;

  constructor(native: NativeDash0MobileModule) {
    this.native = native;
  }

  emit(payload: BridgePayload): void {
    this.queue.push(payload);
    if (this.queue.length > MAX_QUEUE) {
      this.queue.splice(0, this.queue.length - MAX_QUEUE);
    }
    if (this.timer === null) {
      this.timer = setTimeout(() => {
        this.timer = null;
        void this.drain();
      }, DEBOUNCE_MS);
    }
  }

  /**
   * Synchronous fast-path for FATAL-severity payloads. Cancels any pending
   * debounce, drains the queue + the new payload in a single
   * `native.emitBatch` call made SYNCHRONOUSLY on the current stack frame.
   *
   * Why not just `emit(); flush()`? Because `flush()` is `async`, and its
   * `await this.drain()` places the call to `native.emitBatch` on the
   * microtask queue. On a crash path (JS throw → ErrorUtils global handler
   * → previous(error, isFatal) → RN fatal reporter → process exit) the
   * handler's synchronous continuation wins the race against the
   * microtask — the payload never crosses the bridge.
   *
   * This method is intentionally fire-and-forget: per the RN bridge
   * contract, argument marshaling is synchronous, so the payload arrives
   * on the native side by the time `native.emitBatch(...)` returns. The
   * Promise resolution (native-side completion signal) is async and we
   * don't await it — we only need the payload to cross the bridge before
   * the process dies. `.catch(() => {})` prevents unhandledRejection
   * warnings if the native side fails.
   *
   * See docs/superpowers/specs/2026-04-22-rn-fatal-bridge-bypass-design.md.
   */
  emitSync(payload: BridgePayload): void {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    const batch = this.queue.length > 0 ? [...this.queue, payload] : [payload];
    this.queue = [];
    this.native.emitBatch(batch).catch(() => {
      // Swallow — on the crash path, the process is dying anyway and
      // there's no caller alive to observe the error. On non-crash paths
      // (if any caller uses emitSync for non-FATAL severity) the payload
      // is lost on failure, which matches the implicit "best-effort"
      // contract of the RN bridge under adverse conditions.
    });
  }

  async flush(): Promise<void> {
    if (this.timer !== null) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    await this.drain();
  }

  private async drain(): Promise<void> {
    if (this.queue.length === 0) return;
    const batch = this.queue;
    this.queue = [];
    this.inFlight = this.sendWithRetry(batch);
    try {
      await this.inFlight;
    } finally {
      this.inFlight = null;
    }
  }

  private async sendWithRetry(batch: BridgePayload[]): Promise<void> {
    for (let attempt = 0; attempt < RETRY_MAX_ATTEMPTS; attempt++) {
      try {
        await this.native.emitBatch(batch);
        return;
      } catch {
        if (attempt === RETRY_MAX_ATTEMPTS - 1) return;
        const delay = RETRY_BASE_MS * Math.pow(2, attempt);
        await new Promise<void>(resolve => setTimeout(resolve, delay));
      }
    }
  }
}
