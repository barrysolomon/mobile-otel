# Backend Scheduling Instrumentation — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enrich the demo backend's POST /api/appointments with 5 custom OTel span operations and per-operation fault injection, producing a rich trace waterfall in Dash0.

**Architecture:** Each scheduling operation is a separate module under `src/scheduling/` with a shared tracer. The existing POST handler is refactored to call operations sequentially (availability → conflicts → authorization → book → notify). Fault injection extends the existing `simulate.ts` state with an `operations` nested object, controlled via the same admin API.

**Tech Stack:** TypeScript, Express, `@opentelemetry/api` (trace), better-sqlite3, Vitest + Supertest for tests.

**Spec:** `docs/superpowers/specs/2026-04-13-backend-scheduling-instrumentation-design.md`

---

## File Structure

| Action | Path | Responsibility |
|--------|------|---------------|
| Create | `src/scheduling/tracer.ts` | Shared tracer instance + attribute builder |
| Create | `src/scheduling/types.ts` | BookingContext interface + SchedulingError class |
| Create | `src/scheduling/availability.ts` | Parallel provider queries with timeout/error simulation |
| Create | `src/scheduling/conflicts.ts` | Optimistic lock check with race condition simulation |
| Create | `src/scheduling/authorization.ts` | External auth simulation with timeout/deny |
| Create | `src/scheduling/notifications.ts` | Fan-out to 3 channels with partial failure |
| Modify | `src/middleware/simulate.ts` | Add `operations` to state with defaults |
| Modify | `src/routes/admin.ts` | Deep-merge for `operations` |
| Modify | `src/routes/appointments.ts` | Wire scheduling operations into POST handler |
| Create | `tests/scheduling.test.ts` | All scheduling operation tests |
| Modify | `tests/simulate.test.ts` | Add operations state tests |

---

## Task 1: Extend Simulation State

**Files:**
- Modify: `examples/demo-backend/src/middleware/simulate.ts`
- Modify: `examples/demo-backend/src/routes/admin.ts`
- Modify: `examples/demo-backend/tests/simulate.test.ts`

- [ ] **Step 1: Write tests for extended simulation state**

Append to `tests/simulate.test.ts`, inside the outer `describe("Simulation")` block, after the existing test suites:

```typescript
  describe("Operations state", () => {
    it("GET returns default operations state", async () => {
      const res = await request(app).get("/api/admin/simulate");
      expect(res.body.operations).toEqual({
        availability: { timeout: 0, errorRate: 0 },
        conflicts: { raceCondition: false },
        authorization: { timeout: 0, deny: false },
        notifications: { partialFailure: false, slowChannel: null },
      });
    });

    it("POST deep-merges operations without resetting others", async () => {
      await request(app)
        .post("/api/admin/simulate")
        .send({ operations: { authorization: { deny: true } } });
      const res = await request(app)
        .post("/api/admin/simulate")
        .send({ operations: { conflicts: { raceCondition: true } } });
      expect(res.body.operations.authorization.deny).toBe(true);
      expect(res.body.operations.conflicts.raceCondition).toBe(true);
      expect(res.body.operations.availability.timeout).toBe(0);
    });

    it("POST deep-merges within an operation without resetting sibling fields", async () => {
      await request(app)
        .post("/api/admin/simulate")
        .send({ operations: { availability: { timeout: 3000 } } });
      const res = await request(app)
        .post("/api/admin/simulate")
        .send({ operations: { availability: { errorRate: 0.5 } } });
      expect(res.body.operations.availability.timeout).toBe(3000);
      expect(res.body.operations.availability.errorRate).toBe(0.5);
    });

    it("DELETE resets operations to defaults", async () => {
      await request(app)
        .post("/api/admin/simulate")
        .send({ operations: { authorization: { deny: true } } });
      await request(app).delete("/api/admin/simulate");
      const res = await request(app).get("/api/admin/simulate");
      expect(res.body.operations.authorization.deny).toBe(false);
    });

    it("existing global simulate still works alongside operations", async () => {
      await request(app)
        .post("/api/admin/simulate")
        .send({ error: true, operations: { authorization: { deny: true } } });
      // Global error fires first (middleware), scheduling never runs
      const res = await request(app).get("/api/doctors");
      expect(res.status).toBe(503);
    });
  });
```

- [ ] **Step 2: Run tests to verify they fail**

Run:
```bash
cd examples/demo-backend && npm test 2>&1 | tail -20
```

Expected: New tests fail — `operations` not in response.

- [ ] **Step 3: Extend simulate.ts with operations state**

Replace the entire file `src/middleware/simulate.ts`:

```typescript
export interface OperationFaults {
  availability: { timeout: number; errorRate: number };
  conflicts: { raceCondition: boolean };
  authorization: { timeout: number; deny: boolean };
  notifications: { partialFailure: boolean; slowChannel: string | null };
}

export interface SimulationState {
  error: boolean;
  latency: number;
  crash: boolean;
  operations: OperationFaults;
}

function defaultOperations(): OperationFaults {
  return {
    availability: { timeout: 0, errorRate: 0 },
    conflicts: { raceCondition: false },
    authorization: { timeout: 0, deny: false },
    notifications: { partialFailure: false, slowChannel: null },
  };
}

let state: SimulationState = {
  error: false, latency: 0, crash: false,
  operations: defaultOperations(),
};

export function getSimulationState(): SimulationState {
  return JSON.parse(JSON.stringify(state));
}

export function setSimulationState(update: Partial<SimulationState>): SimulationState {
  if (update.operations) {
    for (const [op, faults] of Object.entries(update.operations)) {
      const key = op as keyof OperationFaults;
      if (state.operations[key]) {
        state.operations[key] = { ...state.operations[key], ...faults } as any;
      }
    }
  }
  if (update.error !== undefined) state.error = update.error;
  if (update.latency !== undefined) state.latency = update.latency;
  if (update.crash !== undefined) state.crash = update.crash;
  return getSimulationState();
}

export function resetSimulation(): void {
  state = {
    error: false, latency: 0, crash: false,
    operations: defaultOperations(),
  };
}

export function simulateMiddleware(req: any, res: any, next: any): void {
  if (req.path.startsWith("/api/admin") || req.path === "/health") {
    next();
    return;
  }

  const shouldError = req.headers["x-simulate-error"] === "true" || state.error;
  const latencyMs = parseInt(req.headers["x-simulate-latency"] as string) || state.latency;
  const shouldCrash = req.headers["x-simulate-crash"] === "true" || state.crash;

  if (shouldCrash) {
    console.log("Simulated crash — exiting process");
    process.exit(1);
  }

  const proceed = () => {
    if (shouldError) {
      res.status(503).json({ error: "Simulated server error", code: "SIMULATED_ERROR" });
      return;
    }
    next();
  };

  if (latencyMs > 0) {
    setTimeout(proceed, latencyMs);
  } else {
    proceed();
  }
}
```

- [ ] **Step 4: Update admin.ts — no change needed**

The `setSimulationState` function now handles deep-merge internally. The admin route calls `setSimulationState(req.body)` which already works. Verify by reading `src/routes/admin.ts` — it should still work as-is.

- [ ] **Step 5: Run tests to verify they pass**

Run:
```bash
cd examples/demo-backend && npm test 2>&1 | tail -20
```

Expected: All tests pass including new operations tests.

- [ ] **Step 6: Commit**

```bash
cd examples/demo-backend && git add src/middleware/simulate.ts tests/simulate.test.ts
git commit -m "feat(demo-backend): extend simulation state with per-operation faults"
```

---

## Task 2: Shared Tracer + Types

**Files:**
- Create: `examples/demo-backend/src/scheduling/tracer.ts`
- Create: `examples/demo-backend/src/scheduling/types.ts`

- [ ] **Step 1: Create types.ts**

File: `src/scheduling/types.ts`

```typescript
export interface BookingContext {
  doctorId: string;
  doctorName: string;
  patient: string;
  slotId: string;
  slotDate: string;
  slotTime: string;
}

export class SchedulingError extends Error {
  constructor(
    message: string,
    public readonly code: string,
    public readonly statusCode: number,
  ) {
    super(message);
    this.name = "SchedulingError";
  }
}
```

- [ ] **Step 2: Create tracer.ts**

File: `src/scheduling/tracer.ts`

```typescript
import { trace, Span, SpanStatusCode } from "@opentelemetry/api";
import type { BookingContext } from "./types.js";

export const schedulingTracer = trace.getTracer(
  "io.opentelemetry.demo.scheduling",
  "1.0.0",
);

export function schedulingAttributes(ctx: BookingContext, operation: string) {
  return {
    "scheduling.doctor.id": ctx.doctorId,
    "scheduling.doctor.name": ctx.doctorName,
    "scheduling.patient": ctx.patient,
    "scheduling.slot.id": ctx.slotId,
    "scheduling.slot.date": ctx.slotDate,
    "scheduling.slot.time": ctx.slotTime,
    "scheduling.operation": operation,
  };
}

export function failSpan(span: Span, errorType: string, message: string): void {
  span.setStatus({ code: SpanStatusCode.ERROR, message });
  span.setAttribute("error.type", errorType);
  span.recordException(new Error(message));
}

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export function randomBetween(min: number, max: number): number {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}
```

- [ ] **Step 3: Verify TypeScript compiles**

Run:
```bash
cd examples/demo-backend && npx tsc --noEmit 2>&1 | tail -5
```

Expected: No errors (or only pre-existing ones).

- [ ] **Step 4: Commit**

```bash
cd examples/demo-backend && git add src/scheduling/types.ts src/scheduling/tracer.ts
git commit -m "feat(demo-backend): add scheduling tracer, types, and span helpers"
```

---

## Task 3: Availability Check

**Files:**
- Create: `examples/demo-backend/src/scheduling/availability.ts`

- [ ] **Step 1: Create availability.ts**

```typescript
import { context, trace } from "@opentelemetry/api";
import { getDb } from "../db/connection.js";
import type { OperationFaults } from "../middleware/simulate.js";
import type { BookingContext } from "./types.js";
import { SchedulingError } from "./types.js";
import { schedulingTracer, schedulingAttributes, failSpan, sleep, randomBetween } from "./tracer.js";

export async function checkAvailability(
  ctx: BookingContext,
  faults: OperationFaults,
): Promise<void> {
  const parentSpan = schedulingTracer.startSpan("scheduling.check_availability", {
    attributes: {
      ...schedulingAttributes(ctx, "check_availability"),
    },
  });

  const parentContext = trace.setSpan(context.active(), parentSpan);

  try {
    // Find providers: requested doctor + one other in same specialty
    const db = getDb();
    const requestedDoctor = db.prepare(
      "SELECT id, name, specialty FROM doctors WHERE id = ?",
    ).get(ctx.doctorId) as { id: string; name: string; specialty: string };

    const otherDoctor = db.prepare(
      "SELECT id, name FROM doctors WHERE specialty = ? AND id != ? LIMIT 1",
    ).get(requestedDoctor.specialty, ctx.doctorId) as { id: string; name: string } | undefined;

    const providers = [
      { id: requestedDoctor.id, name: requestedDoctor.name, isPrimary: true },
    ];
    if (otherDoctor) {
      providers.push({ id: otherDoctor.id, name: otherDoctor.name, isPrimary: false });
    }

    parentSpan.setAttribute("scheduling.provider.count", providers.length);

    // Query providers in parallel
    const results = await Promise.allSettled(
      providers.map((provider) =>
        context.with(parentContext, () =>
          queryProvider(provider, ctx, faults),
        ),
      ),
    );

    const succeeded = results.filter((r) => r.status === "fulfilled");
    const totalSlots = succeeded.reduce(
      (sum, r) => sum + ((r as PromiseFulfilledResult<number>).value || 0),
      0,
    );

    parentSpan.setAttribute("scheduling.available_slots", totalSlots);

    if (succeeded.length === 0) {
      failSpan(parentSpan, "all_providers_failed", "All availability providers failed");
      throw new SchedulingError(
        "All availability providers failed",
        "AVAILABILITY_FAILED",
        503,
      );
    }
  } finally {
    parentSpan.end();
  }
}

async function queryProvider(
  provider: { id: string; name: string; isPrimary: boolean },
  ctx: BookingContext,
  faults: OperationFaults,
): Promise<number> {
  const span = schedulingTracer.startSpan("scheduling.provider.query", {
    attributes: {
      "scheduling.provider.name": provider.name,
      "scheduling.operation": "provider_query",
    },
  });

  try {
    const baseDelay = randomBetween(20, 80);

    // Fault: timeout on secondary provider
    if (!provider.isPrimary && faults.availability.timeout > 0) {
      await sleep(faults.availability.timeout);
      failSpan(span, "timeout", `Provider ${provider.name} timed out after ${faults.availability.timeout}ms`);
      throw new Error("Provider timeout");
    }

    // Fault: random error rate
    if (faults.availability.errorRate > 0 && Math.random() < faults.availability.errorRate) {
      await sleep(baseDelay);
      failSpan(span, "provider_error", `Provider ${provider.name} returned an error`);
      throw new Error("Provider error");
    }

    await sleep(baseDelay);

    // Real query
    const db = getDb();
    const slots = db.prepare(
      "SELECT COUNT(*) as count FROM slots WHERE doctor_id = ? AND available = 1",
    ).get(provider.id) as { count: number };

    span.setAttribute("scheduling.provider.slots_found", slots.count);
    return slots.count;
  } catch (err) {
    if (!span.isRecording()) throw err;
    throw err;
  } finally {
    span.end();
  }
}
```

- [ ] **Step 2: Verify TypeScript compiles**

Run:
```bash
cd examples/demo-backend && npx tsc --noEmit 2>&1 | tail -5
```

- [ ] **Step 3: Commit**

```bash
cd examples/demo-backend && git add src/scheduling/availability.ts
git commit -m "feat(demo-backend): add availability check with parallel provider queries"
```

---

## Task 4: Conflict Resolution

**Files:**
- Create: `examples/demo-backend/src/scheduling/conflicts.ts`

- [ ] **Step 1: Create conflicts.ts**

```typescript
import { getDb } from "../db/connection.js";
import type { OperationFaults } from "../middleware/simulate.js";
import type { BookingContext } from "./types.js";
import { SchedulingError } from "./types.js";
import { schedulingTracer, schedulingAttributes, failSpan, sleep, randomBetween } from "./tracer.js";

export async function resolveConflicts(
  ctx: BookingContext,
  faults: OperationFaults,
): Promise<void> {
  const span = schedulingTracer.startSpan("scheduling.resolve_conflicts", {
    attributes: schedulingAttributes(ctx, "resolve_conflicts"),
  });

  try {
    const db = getDb();
    const baseDelay = randomBetween(10, 30);
    await sleep(baseDelay);

    // Check slot availability
    const slot = db.prepare(
      "SELECT available FROM slots WHERE id = ?",
    ).get(ctx.slotId) as { available: number } | undefined;

    if (!slot) {
      failSpan(span, "slot_not_found", "Slot not found during conflict check");
      throw new SchedulingError("Slot not found", "SLOT_NOT_FOUND", 404);
    }

    // Fault: deterministic race condition
    if (faults.conflicts.raceCondition) {
      span.setAttribute("scheduling.conflict.detected", true);
      // First check "fails" — simulate optimistic lock conflict
      await sleep(50); // retry backoff
      // Second check always succeeds (deterministic)
      span.setAttribute("scheduling.conflict.retries", 1);
      return;
    }

    if (!slot.available) {
      span.setAttribute("scheduling.conflict.detected", true);
      failSpan(span, "slot_unavailable", "Slot was booked by another user");
      throw new SchedulingError("Slot is already booked", "SLOT_UNAVAILABLE", 409);
    }

    span.setAttribute("scheduling.conflict.detected", false);
    span.setAttribute("scheduling.conflict.retries", 0);
  } finally {
    span.end();
  }
}
```

- [ ] **Step 2: Commit**

```bash
cd examples/demo-backend && git add src/scheduling/conflicts.ts
git commit -m "feat(demo-backend): add conflict resolution with race condition simulation"
```

---

## Task 5: Authorization Verification

**Files:**
- Create: `examples/demo-backend/src/scheduling/authorization.ts`

- [ ] **Step 1: Create authorization.ts**

```typescript
import type { OperationFaults } from "../middleware/simulate.js";
import type { BookingContext } from "./types.js";
import { SchedulingError } from "./types.js";
import { schedulingTracer, schedulingAttributes, failSpan, sleep, randomBetween } from "./tracer.js";

export async function verifyAuthorization(
  ctx: BookingContext,
  faults: OperationFaults,
): Promise<void> {
  const span = schedulingTracer.startSpan("scheduling.verify_authorization", {
    attributes: {
      ...schedulingAttributes(ctx, "verify_authorization"),
      "scheduling.auth.provider": "internal",
    },
  });

  try {
    // Fault: timeout
    if (faults.authorization.timeout > 0) {
      await sleep(faults.authorization.timeout);
      failSpan(span, "timeout", `Authorization timed out after ${faults.authorization.timeout}ms`);
      throw new SchedulingError(
        "Authorization service timed out",
        "AUTH_TIMEOUT",
        504,
      );
    }

    // Simulate external API call (200-500ms)
    const baseDelay = randomBetween(200, 500);
    await sleep(baseDelay);

    // Fault: deny
    if (faults.authorization.deny) {
      span.setAttribute("scheduling.auth.status", "denied");
      failSpan(span, "authorization_denied", "Patient authorization denied");
      throw new SchedulingError(
        "Patient authorization denied",
        "AUTH_DENIED",
        403,
      );
    }

    span.setAttribute("scheduling.auth.status", "approved");
  } finally {
    span.end();
  }
}
```

- [ ] **Step 2: Commit**

```bash
cd examples/demo-backend && git add src/scheduling/authorization.ts
git commit -m "feat(demo-backend): add authorization verification with timeout and deny simulation"
```

---

## Task 6: Notification Dispatch

**Files:**
- Create: `examples/demo-backend/src/scheduling/notifications.ts`

- [ ] **Step 1: Create notifications.ts**

```typescript
import { context, trace } from "@opentelemetry/api";
import type { OperationFaults } from "../middleware/simulate.js";
import type { BookingContext } from "./types.js";
import { schedulingTracer, schedulingAttributes, failSpan, sleep, randomBetween } from "./tracer.js";

const CHANNELS = ["email", "sms", "push"] as const;

export async function dispatchNotifications(
  ctx: BookingContext,
  faults: OperationFaults,
): Promise<void> {
  const parentSpan = schedulingTracer.startSpan("scheduling.dispatch_notifications", {
    attributes: {
      ...schedulingAttributes(ctx, "dispatch_notifications"),
      "scheduling.notifications.channels": CHANNELS.length,
    },
  });

  const parentContext = trace.setSpan(context.active(), parentSpan);

  try {
    // Pick one random channel to fail if partialFailure is enabled
    const failChannel = faults.notifications.partialFailure
      ? CHANNELS[Math.floor(Math.random() * CHANNELS.length)]
      : null;

    const results = await Promise.allSettled(
      CHANNELS.map((channel) =>
        context.with(parentContext, () =>
          notifyChannel(channel, ctx, faults, failChannel),
        ),
      ),
    );

    const succeeded = results.filter((r) => r.status === "fulfilled").length;
    parentSpan.setAttribute("scheduling.notifications.succeeded", succeeded);
  } finally {
    parentSpan.end();
  }
}

async function notifyChannel(
  channel: (typeof CHANNELS)[number],
  ctx: BookingContext,
  faults: OperationFaults,
  failChannel: string | null,
): Promise<void> {
  const span = schedulingTracer.startSpan("scheduling.notify", {
    attributes: {
      "scheduling.notify.channel": channel,
      "scheduling.operation": "notify",
    },
  });

  try {
    let delay = randomBetween(10, 30);

    // Fault: slow channel
    if (faults.notifications.slowChannel === channel) {
      delay += 2000;
    }

    await sleep(delay);

    // Fault: partial failure
    if (channel === failChannel) {
      failSpan(span, "notification_failed", `Failed to send ${channel} notification`);
      span.setAttribute("scheduling.notify.status", "failed");
      throw new Error(`${channel} notification failed`);
    }

    span.setAttribute("scheduling.notify.status", "sent");
  } finally {
    span.end();
  }
}
```

- [ ] **Step 2: Commit**

```bash
cd examples/demo-backend && git add src/scheduling/notifications.ts
git commit -m "feat(demo-backend): add notification dispatch with partial failure and slow channel"
```

---

## Task 7: Wire Scheduling Into POST Handler

**Files:**
- Modify: `examples/demo-backend/src/routes/appointments.ts`

- [ ] **Step 1: Rewrite the POST handler**

Replace the entire file `src/routes/appointments.ts`:

```typescript
import { Router } from "express";
import { v4 as uuid } from "uuid";
import { getDb } from "../db/connection.js";
import { getSimulationState } from "../middleware/simulate.js";
import { schedulingTracer, schedulingAttributes, sleep, randomBetween } from "../scheduling/tracer.js";
import type { BookingContext } from "../scheduling/types.js";
import { SchedulingError } from "../scheduling/types.js";
import { checkAvailability } from "../scheduling/availability.js";
import { resolveConflicts } from "../scheduling/conflicts.js";
import { verifyAuthorization } from "../scheduling/authorization.js";
import { dispatchNotifications } from "../scheduling/notifications.js";

const router = Router();

router.get("/api/appointments", (_req, res) => {
  const appointments = getDb()
    .prepare("SELECT * FROM appointments ORDER BY created_at DESC")
    .all();
  res.json(appointments);
});

router.get("/api/appointments/:id", (req, res) => {
  const appointment = getDb()
    .prepare("SELECT * FROM appointments WHERE id = ?")
    .get(req.params.id);
  if (!appointment) {
    res.status(404).json({ error: "Appointment not found", code: "NOT_FOUND" });
    return;
  }
  res.json(appointment);
});

router.post("/api/appointments", async (req, res) => {
  const { doctor_id, slot_id, patient, reason } = req.body;

  // 1. Validate required fields (unchanged)
  if (!doctor_id || !slot_id || !patient || !reason) {
    res.status(400).json({
      error: "doctor_id, slot_id, patient, and reason are required",
      code: "MISSING_FIELDS",
    });
    return;
  }

  const db = getDb();

  // 2. Look up doctor and slot (unchanged validation)
  const doctor = db.prepare("SELECT id, name FROM doctors WHERE id = ?").get(doctor_id) as
    | { id: string; name: string }
    | undefined;
  if (!doctor) {
    res.status(404).json({ error: "Doctor not found", code: "DOCTOR_NOT_FOUND" });
    return;
  }

  const slot = db.prepare("SELECT * FROM slots WHERE id = ?").get(slot_id) as
    | { id: string; date: string; time: string; available: number }
    | undefined;
  if (!slot) {
    res.status(404).json({ error: "Slot not found", code: "SLOT_NOT_FOUND" });
    return;
  }

  const bookingCtx: BookingContext = {
    doctorId: doctor_id,
    doctorName: doctor.name,
    patient,
    slotId: slot_id,
    slotDate: slot.date,
    slotTime: slot.time,
  };

  const { operations } = getSimulationState();

  // 3. Scheduling operations
  try {
    await checkAvailability(bookingCtx, operations);
    await resolveConflicts(bookingCtx, operations);
    await verifyAuthorization(bookingCtx, operations);
  } catch (err) {
    if (err instanceof SchedulingError) {
      res.status(err.statusCode).json({ error: err.message, code: err.code });
      return;
    }
    res.status(500).json({ error: "Internal scheduling error", code: "SCHEDULING_ERROR" });
    return;
  }

  // 4. Book the slot (wrapped in scheduling.book_slot span)
  const bookSpan = schedulingTracer.startSpan("scheduling.book_slot", {
    attributes: schedulingAttributes(bookingCtx, "book_slot"),
  });

  const id = uuid();
  const now = new Date().toISOString();

  try {
    // Slot may have been taken between conflict check and booking (real race)
    if (!slot.available) {
      res.status(409).json({ error: "Slot is already booked", code: "SLOT_UNAVAILABLE" });
      return;
    }

    const bookAppointment = db.transaction(() => {
      db.prepare(
        "INSERT INTO appointments (id, doctor_id, slot_id, patient, reason, status, created_at) VALUES (?, ?, ?, ?, ?, 'confirmed', ?)",
      ).run(id, doctor_id, slot_id, patient, reason, now);
      db.prepare("UPDATE slots SET available = 0 WHERE id = ?").run(slot_id);
    });

    await sleep(randomBetween(5, 20)); // Realistic DB timing
    bookAppointment();

    bookSpan.setAttribute("scheduling.booking.id", id);
    bookSpan.setAttribute("scheduling.booking.status", "confirmed");
  } finally {
    bookSpan.end();
  }

  // 5. Non-blocking notifications — send response first, await after
  const notificationPromise = dispatchNotifications(bookingCtx, operations);

  const appointment = db.prepare("SELECT * FROM appointments WHERE id = ?").get(id);
  res.status(201).json(appointment);

  await notificationPromise.catch(() => {});
});

router.delete("/api/appointments/:id", (req, res) => {
  const db = getDb();
  const appointment = db
    .prepare("SELECT * FROM appointments WHERE id = ?")
    .get(req.params.id) as { slot_id: string } | undefined;

  if (!appointment) {
    res.status(404).json({ error: "Appointment not found", code: "NOT_FOUND" });
    return;
  }

  const cancelAppointment = db.transaction(() => {
    db.prepare("UPDATE appointments SET status = 'cancelled' WHERE id = ?").run(req.params.id);
    db.prepare("UPDATE slots SET available = 1 WHERE id = ?").run(appointment.slot_id);
  });

  cancelAppointment();
  res.json({ status: "cancelled" });
});

export default router;
```

- [ ] **Step 2: Run existing tests to check for regressions**

Run:
```bash
cd examples/demo-backend && npm test 2>&1 | tail -20
```

Expected: All existing appointment tests still pass. The POST handler now runs async scheduling operations but the test DB is in-memory and simulation defaults are happy path, so behavior is identical.

- [ ] **Step 3: Commit**

```bash
cd examples/demo-backend && git add src/routes/appointments.ts
git commit -m "feat(demo-backend): wire scheduling operations into POST /api/appointments"
```

---

## Task 8: Scheduling Integration Tests

**Files:**
- Create: `examples/demo-backend/tests/scheduling.test.ts`

- [ ] **Step 1: Write all scheduling tests**

File: `tests/scheduling.test.ts`

```typescript
import { describe, it, expect, beforeEach } from "vitest";
import request from "supertest";
import { createApp } from "../src/index.js";
import Database from "better-sqlite3";
import { createSchema } from "../src/db/schema.js";
import { seedData } from "../src/db/seed.js";
import { setDb } from "../src/db/connection.js";
import { resetSimulation, setSimulationState } from "../src/middleware/simulate.js";

describe("Scheduling Operations", () => {
  let app: ReturnType<typeof createApp>;
  let db: Database.Database;
  let doctorId: string;
  let slotId: string;

  beforeEach(() => {
    db = new Database(":memory:");
    db.pragma("foreign_keys = ON");
    createSchema(db);
    seedData(db);
    setDb(db);
    resetSimulation();
    app = createApp();

    const doc = db.prepare("SELECT id FROM doctors LIMIT 1").get() as { id: string };
    doctorId = doc.id;
    const slot = db.prepare("SELECT id FROM slots WHERE doctor_id = ? AND available = 1 LIMIT 1").get(doctorId) as { id: string };
    slotId = slot.id;
  });

  const bookPayload = () => ({
    doctor_id: doctorId,
    slot_id: slotId,
    patient: "Test Patient",
    reason: "Scheduling test",
  });

  describe("Happy path", () => {
    it("books an appointment through all scheduling operations", async () => {
      const res = await request(app)
        .post("/api/appointments")
        .send(bookPayload());
      expect(res.status).toBe(201);
      expect(res.body.status).toBe("confirmed");
      expect(res.body.patient).toBe("Test Patient");
    });
  });

  describe("Availability faults", () => {
    it("succeeds when one provider times out (partial availability)", async () => {
      setSimulationState({
        operations: { availability: { timeout: 100, errorRate: 0 } } as any,
      });
      const res = await request(app)
        .post("/api/appointments")
        .send(bookPayload());
      // Primary provider succeeds, secondary times out — booking proceeds
      expect(res.status).toBe(201);
    });

    it("returns 503 when all providers fail", async () => {
      setSimulationState({
        operations: { availability: { timeout: 0, errorRate: 1.0 } } as any,
      });
      const res = await request(app)
        .post("/api/appointments")
        .send(bookPayload());
      expect(res.status).toBe(503);
      expect(res.body.code).toBe("AVAILABILITY_FAILED");
    });
  });

  describe("Conflict resolution faults", () => {
    it("retries on race condition and succeeds", async () => {
      setSimulationState({
        operations: { conflicts: { raceCondition: true } } as any,
      });
      const res = await request(app)
        .post("/api/appointments")
        .send(bookPayload());
      // Race condition detected → retry → succeed
      expect(res.status).toBe(201);
    });
  });

  describe("Authorization faults", () => {
    it("returns 403 when authorization denied", async () => {
      setSimulationState({
        operations: { authorization: { timeout: 0, deny: true } } as any,
      });
      const res = await request(app)
        .post("/api/appointments")
        .send(bookPayload());
      expect(res.status).toBe(403);
      expect(res.body.code).toBe("AUTH_DENIED");
      // No appointment created
      const appts = db.prepare("SELECT COUNT(*) as count FROM appointments").get() as { count: number };
      expect(appts.count).toBe(0);
    });

    it("returns 504 when authorization times out", async () => {
      setSimulationState({
        operations: { authorization: { timeout: 100, deny: false } } as any,
      });
      const res = await request(app)
        .post("/api/appointments")
        .send(bookPayload());
      expect(res.status).toBe(504);
      expect(res.body.code).toBe("AUTH_TIMEOUT");
    });
  });

  describe("Notification faults", () => {
    it("booking succeeds despite partial notification failure", async () => {
      setSimulationState({
        operations: { notifications: { partialFailure: true, slowChannel: null } } as any,
      });
      const res = await request(app)
        .post("/api/appointments")
        .send(bookPayload());
      // Notifications are non-blocking — booking still succeeds
      expect(res.status).toBe(201);
      expect(res.body.status).toBe("confirmed");
    });

    it("booking succeeds with slow notification channel", async () => {
      setSimulationState({
        operations: { notifications: { partialFailure: false, slowChannel: "sms" } } as any,
      });
      const res = await request(app)
        .post("/api/appointments")
        .send(bookPayload());
      expect(res.status).toBe(201);
    });
  });

  describe("Existing validation unchanged", () => {
    it("still returns 400 for missing fields", async () => {
      const res = await request(app)
        .post("/api/appointments")
        .send({ doctor_id: doctorId });
      expect(res.status).toBe(400);
      expect(res.body.code).toBe("MISSING_FIELDS");
    });

    it("still returns 404 for nonexistent doctor", async () => {
      const res = await request(app)
        .post("/api/appointments")
        .send({ ...bookPayload(), doctor_id: "nonexistent" });
      expect(res.status).toBe(404);
      expect(res.body.code).toBe("DOCTOR_NOT_FOUND");
    });
  });
});
```

- [ ] **Step 2: Run all tests**

Run:
```bash
cd examples/demo-backend && npm test 2>&1 | tail -25
```

Expected: All tests pass — both new scheduling tests and existing tests.

- [ ] **Step 3: Commit**

```bash
cd examples/demo-backend && git add tests/scheduling.test.ts
git commit -m "test(demo-backend): add scheduling operation integration tests with fault injection"
```

---

## Task 9: Full Verification

**Files:** None — verification only.

- [ ] **Step 1: Run full test suite**

Run:
```bash
cd examples/demo-backend && npm test 2>&1
```

Expected: All tests pass (62+ existing + 9 new scheduling + 5 new simulate = ~76 total).

- [ ] **Step 2: Start backend and test manually with curl**

Run:
```bash
cd examples/demo-backend && npm run dev &
sleep 2

# Happy path — should see scheduling spans in console
curl -s http://localhost:3001/api/doctors | jq '.[0].id' -r

DOCTOR_ID=$(curl -s http://localhost:3001/api/doctors | jq -r '.[0].id')
SLOT_ID=$(curl -s "http://localhost:3001/api/slots?doctor_id=$DOCTOR_ID" | jq -r '.[0].id')

curl -s -X POST http://localhost:3001/api/appointments \
  -H "Content-Type: application/json" \
  -d "{\"doctor_id\":\"$DOCTOR_ID\",\"slot_id\":\"$SLOT_ID\",\"patient\":\"Demo User\",\"reason\":\"Test booking\"}" | jq .

# Auth denied
curl -s -X POST http://localhost:3001/api/admin/simulate \
  -H "Content-Type: application/json" \
  -d '{"operations":{"authorization":{"deny":true}}}' | jq .

SLOT_ID2=$(curl -s "http://localhost:3001/api/slots?doctor_id=$DOCTOR_ID" | jq -r '.[1].id')
curl -s -X POST http://localhost:3001/api/appointments \
  -H "Content-Type: application/json" \
  -d "{\"doctor_id\":\"$DOCTOR_ID\",\"slot_id\":\"$SLOT_ID2\",\"patient\":\"Denied User\",\"reason\":\"Should fail\"}" | jq .

# Reset and stop
curl -s -X DELETE http://localhost:3001/api/admin/simulate | jq .
kill %1 2>/dev/null
```

Expected: First booking returns 201. Second booking (with auth deny) returns 403 with `AUTH_DENIED`.

- [ ] **Step 3: TypeScript check**

Run:
```bash
cd examples/demo-backend && npx tsc --noEmit 2>&1 | tail -5
```

Expected: No errors.

---

## Summary

| Task | What it delivers | Tests |
|------|-----------------|-------|
| 1 | Extended simulation state with per-operation faults | 5 |
| 2 | Shared tracer, types, span helpers | — |
| 3 | Availability check with parallel provider queries | — |
| 4 | Conflict resolution with race condition simulation | — |
| 5 | Authorization verification with timeout/deny | — |
| 6 | Notification dispatch with partial failure | — |
| 7 | Wired POST handler with all operations | — |
| 8 | Integration tests for all scheduling scenarios | 9 |
| 9 | Full verification (manual + automated) | — |

**Total: ~14 new tests, 9 commits, 6 new files + 3 modified files.**
