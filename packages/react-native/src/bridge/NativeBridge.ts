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
