# Backend Scheduling Instrumentation — Design Spec

> **Date:** 2026-04-13
> **Status:** Approved
> **Purpose:** Enrich demo backend booking flow with custom OTel spans and injectable faults

---

## Problem

The demo backend (`examples/demo-backend/`) currently only has auto-instrumented spans from `@opentelemetry/auto-instrumentations-node` — one HTTP span per request with SQLite query children. The booking flow (`POST /api/appointments`) looks flat in Dash0: a single `HTTP POST` span with a `better-sqlite3` child. No visibility into business logic, no interesting failure modes, no story to tell in a demo.

## Goal

Enrich `POST /api/appointments` with 5 custom span operations that mirror a real scheduling system. Each operation has injectable faults via the existing simulate API. The span tree tells a story: parallel provider queries, race conditions, external auth calls, fan-out notifications — all patterns that are invisible without distributed tracing.

---

## Span Tree

```
POST /api/appointments (auto-instrumented HTTP span)
├── scheduling.check_availability        50-150ms
│   ├── scheduling.provider.query [Dr. Chen]
│   └── scheduling.provider.query [Dr. Webb]
├── scheduling.resolve_conflicts         10-30ms (or retry loop)
├── scheduling.verify_authorization      200-500ms (simulated external call)
├── scheduling.book_slot                 (actual DB write — existing logic)
└── scheduling.dispatch_notifications    fire-and-forget
    ├── scheduling.notify [email]
    ├── scheduling.notify [sms]
    └── scheduling.notify [push]
```

### Span Details

| Span Name | Parent | Duration | Attributes | Error Scenarios |
|-----------|--------|----------|------------|-----------------|
| `scheduling.check_availability` | HTTP span | 50-150ms | `scheduling.provider.count`, `scheduling.available_slots` | Provider timeout, partial failure |
| `scheduling.provider.query` | check_availability | 20-80ms each | `scheduling.provider.name`, `scheduling.provider.slots_found` | Timeout (configurable ms), error (configurable rate 0-1) |
| `scheduling.resolve_conflicts` | HTTP span | 10-30ms | `scheduling.conflict.detected` (bool), `scheduling.conflict.retries` (int) | Race condition → retry once → succeed |
| `scheduling.verify_authorization` | HTTP span | 200-500ms | `scheduling.auth.status` ("approved"/"denied"), `scheduling.auth.provider` ("internal") | Timeout (configurable ms), denial |
| `scheduling.book_slot` | HTTP span | 5-20ms | `scheduling.booking.id`, `scheduling.booking.status` | Inherits existing DB errors |
| `scheduling.dispatch_notifications` | HTTP span | 10-50ms | `scheduling.notifications.channels` (int), `scheduling.notifications.succeeded` (int) | Partial failure, slow channel |
| `scheduling.notify` | dispatch_notifications | 10-30ms each | `scheduling.notify.channel` ("email"/"sms"/"push"), `scheduling.notify.status` | Individual channel failure |

### Common Attributes (on every scheduling span)

- `scheduling.doctor.id` — UUID of the doctor
- `scheduling.doctor.name` — Doctor's display name
- `scheduling.patient` — Patient name
- `scheduling.slot.id` — UUID of the slot
- `scheduling.slot.date` — Date string (YYYY-MM-DD)
- `scheduling.slot.time` — Time string (HH:MM)
- `scheduling.operation` — Operation name (e.g., "check_availability")

---

## Fault Injection

### Extended SimulationState

```typescript
interface SimulationState {
  // Existing global toggles (unchanged)
  error: boolean;
  latency: number;
  crash: boolean;

  // New per-operation faults
  operations: {
    availability:  { timeout: number; errorRate: number };
    conflicts:     { raceCondition: boolean };
    authorization: { timeout: number; deny: boolean };
    notifications: { partialFailure: boolean; slowChannel: string | null };
  };
}
```

### Default State (happy path)

```json
{
  "error": false,
  "latency": 0,
  "crash": false,
  "operations": {
    "availability":  { "timeout": 0, "errorRate": 0 },
    "conflicts":     { "raceCondition": false },
    "authorization": { "timeout": 0, "deny": false },
    "notifications": { "partialFailure": false, "slowChannel": null }
  }
}
```

### Fault Behaviors

| Fault | Trigger | Span Effect | HTTP Effect |
|-------|---------|-------------|-------------|
| `availability.timeout` (ms) | Provider query exceeds this duration | `scheduling.provider.query` span: `otel.status = ERROR`, `error.type = "timeout"` | Booking proceeds with partial availability (one provider succeeded) |
| `availability.errorRate` (0-1) | Random per provider query | `scheduling.provider.query` span: `otel.status = ERROR`, `error.type = "provider_error"` | Booking proceeds if at least one provider succeeded |
| `conflicts.raceCondition` | First booking attempt | `scheduling.resolve_conflicts` span: `scheduling.conflict.detected = true`, `scheduling.conflict.retries = 1` | Booking succeeds on retry (202 instead of 201) |
| `authorization.timeout` (ms) | Auth call exceeds this duration | `scheduling.verify_authorization` span: `otel.status = ERROR`, `error.type = "timeout"` | HTTP 504 Gateway Timeout |
| `authorization.deny` | Auth returns denied | `scheduling.verify_authorization` span: `scheduling.auth.status = "denied"` | HTTP 403 Forbidden |
| `notifications.partialFailure` | One random channel fails | Failed `scheduling.notify` span: `otel.status = ERROR` | HTTP 201 (booking succeeded, notification failure is non-blocking) |
| `notifications.slowChannel` | Named channel adds 2s | Slow `scheduling.notify` span: duration ~2s | HTTP 201 (notifications are non-blocking) |

### API Control

Existing endpoint, same pattern:

```bash
# Set a single fault
curl -X POST http://localhost:3001/api/admin/simulate \
  -H "Content-Type: application/json" \
  -d '{"operations": {"authorization": {"timeout": 5000}}}'

# Set multiple faults
curl -X POST http://localhost:3001/api/admin/simulate \
  -H "Content-Type: application/json" \
  -d '{"operations": {"availability": {"errorRate": 0.5}, "conflicts": {"raceCondition": true}}}'

# Reset everything
curl -X DELETE http://localhost:3001/api/admin/simulate
```

The admin route deep-merges `operations` into current state so you can set one fault without resetting others.

### Fault Priority

Global faults (`error`, `latency`, `crash`) are evaluated by the `simulate.ts` middleware *before* the route handler runs. If a global fault fires (e.g., `error=true` returns 503), scheduling operations are skipped entirely. Per-operation faults only apply when the request reaches the route handler.

---

## Implementation

### File Structure

```
src/
├── scheduling/
│   ├── tracer.ts              — Shared tracer instance + attribute builder
│   ├── availability.ts        — Parallel provider queries
│   ├── conflicts.ts           — Optimistic lock + retry
│   ├── authorization.ts       — External auth simulation
│   └── notifications.ts       — Fan-out to 3 channels
├── middleware/
│   └── simulate.ts            — Extended with operations state
├── routes/
│   ├── admin.ts               — Deep-merge for operations
│   └── appointments.ts        — Wire scheduling into POST
```

### tracer.ts

Exports a singleton `Tracer` and a helper to build common scheduling attributes:

```typescript
import { trace } from "@opentelemetry/api";

export const schedulingTracer = trace.getTracer(
  "io.opentelemetry.demo.scheduling",
  "1.0.0"
);

export function schedulingAttributes(ctx: {
  doctorId: string;
  doctorName: string;
  patient: string;
  slotId: string;
  slotDate: string;
  slotTime: string;
  operation: string;
}) {
  return {
    "scheduling.doctor.id": ctx.doctorId,
    "scheduling.doctor.name": ctx.doctorName,
    "scheduling.patient": ctx.patient,
    "scheduling.slot.id": ctx.slotId,
    "scheduling.slot.date": ctx.slotDate,
    "scheduling.slot.time": ctx.slotTime,
    "scheduling.operation": ctx.operation,
  };
}
```

### availability.ts — checkAvailability()

1. Start `scheduling.check_availability` span
2. For each provider (currently: the requested doctor + one other doctor in same specialty), start parallel `scheduling.provider.query` child spans
3. Each provider query does a simulated delay (20-80ms random)
4. If `operations.availability.timeout > 0`, the *secondary* provider query sleeps for that duration, then errors with timeout. The primary provider always uses normal timing. Timeout alone cannot fail availability — the primary always succeeds.
5. If `operations.availability.errorRate > 0`, each provider query has that probability of failing. To fail availability entirely, set `errorRate: 1.0` (both providers get 100% error rate).
6. Parent span records `scheduling.provider.count` and `scheduling.available_slots`
7. Returns successfully if at least one provider returned results; throws if all failed

### conflicts.ts — resolveConflicts()

1. Start `scheduling.resolve_conflicts` span
2. Check if the slot is still available (SELECT with `available = 1`)
3. If `operations.conflicts.raceCondition` is true: the race condition is **deterministic** — first check returns `scheduling.conflict.detected = true`, sleep 50ms (simulating retry backoff), second check always succeeds. This ensures the demo reliably shows the retry pattern. Set `scheduling.conflict.retries = 1`.
4. If slot genuinely unavailable (not a simulated race): error span, throw (HTTP 409)
5. Otherwise: `scheduling.conflict.detected = false`, return

### authorization.ts — verifyAuthorization()

1. Start `scheduling.verify_authorization` span
2. Simulate external API call with `setTimeout` (200-500ms random base delay)
3. If `operations.authorization.timeout > 0`, sleep for that duration instead, then error with timeout (span ERROR, throw for HTTP 504)
4. If `operations.authorization.deny`, return denied after normal delay (span records `scheduling.auth.status = "denied"`, throw for HTTP 403)
5. Otherwise: `scheduling.auth.status = "approved"`, return

### notifications.ts — dispatchNotifications()

1. Start `scheduling.dispatch_notifications` span
2. Fire 3 parallel `scheduling.notify` child spans (email, sms, push)
3. Each has a base delay of 10-30ms random
4. If `operations.notifications.partialFailure`, one random channel errors (span ERROR, but parent continues)
5. If `operations.notifications.slowChannel` is set, that channel adds 2000ms delay
6. Parent span waits for all 3 to settle, records `scheduling.notifications.channels = 3` and `scheduling.notifications.succeeded = N`
7. Never throws — notifications are non-blocking. The booking already succeeded.

### bookSlot() — in appointments.ts

Wraps the existing DB transaction (UPDATE `slots.available=0` + INSERT appointment) in a `scheduling.book_slot` span. Attributes: `scheduling.booking.id` (the new appointment UUID), `scheduling.booking.status` ("confirmed"). This is the existing logic extracted into a function with a span wrapper — no behavioral change.

### appointments.ts — Modified POST Handler

```typescript
// Before: validate → check slot → insert (transaction)
// After:
router.post("/", async (req, res) => {
  // 1. Validate request body (unchanged)
  // 2. Look up doctor and slot (unchanged)

  // 3. NEW: Scheduling operations
  try {
    await checkAvailability(bookingContext, simulationState);
    await resolveConflicts(bookingContext, simulationState);
    await verifyAuthorization(bookingContext, simulationState);
  } catch (err) {
    // Each operation throws typed errors → mapped to HTTP status
    return res.status(err.statusCode).json({ error: err.message, code: err.code });
  }

  // 4. Book the slot (existing DB logic, wrapped in scheduling.book_slot span)
  const appointment = bookSlot(bookingContext);

  // 5. NEW: Non-blocking notifications — send response first, then await
  //    so notification spans have valid parent context
  const notificationPromise = dispatchNotifications(bookingContext, simulationState);
  res.status(201).json(appointment);
  await notificationPromise.catch(() => {});
});
```

**Why send-then-await:** `res.json()` flushes the HTTP response immediately — the client gets 201 without waiting for notifications. But by `await`ing the promise *after* sending the response, the notification child spans complete within the active span context. Without this, the auto-instrumented HTTP span would end before notification spans finish, orphaning them from the trace.

### simulate.ts — Extended State

Add `operations` with defaults. `getSimulationState()` returns the full object. `resetSimulation()` resets operations to defaults. Existing global `error`/`latency`/`crash` behavior unchanged.

### admin.ts — Deep Merge

`POST /api/admin/simulate` accepts partial `operations` object and deep-merges:

```typescript
if (body.operations) {
  for (const [op, faults] of Object.entries(body.operations)) {
    state.operations[op] = { ...state.operations[op], ...faults };
  }
}
```

---

## Testing Strategy

Extend existing vitest suite in `examples/demo-backend/`:

| Test | What it validates |
|------|-------------------|
| `happy path booking creates all spans` | POST returns 201, all 5 operation spans emitted in correct parent-child order |
| `availability timeout degrades gracefully` | One provider errors, booking still succeeds with partial results |
| `availability all providers fail returns error` | Both providers error, POST returns 503 |
| `race condition retries and succeeds` | conflict.detected=true, retries=1, POST returns 201 |
| `authorization denied returns 403` | auth.status="denied", POST returns 403, no booking created |
| `authorization timeout returns 504` | auth span has ERROR status, POST returns 504 |
| `partial notification failure is non-blocking` | POST returns 201, one notify span has ERROR, others succeeded |
| `slow notification channel completes` | POST returns 201, slow channel span duration > 2s |
| `simulation state deep-merges operations` | POST to admin merges without resetting other faults |
| `simulation reset clears operations` | DELETE to admin resets operations to defaults |
| `existing global simulate still works` | error=true still returns 503 before scheduling runs |

---

## Out of Scope

- Demo Control Center menu integration (API-only fault control)
- Changes to GET endpoints (only POST /api/appointments enriched)
- Real external API calls (all simulated with realistic timing)
- Metrics or logs (spans only — auto-instrumentation already handles HTTP logs)
- The Innovapptive miner's app (separate future project, Galaxy Quest beryllium sphere references pending)
