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

    const slot = db.prepare(
      "SELECT available FROM slots WHERE id = ?",
    ).get(ctx.slotId) as { available: number } | undefined;

    if (!slot) {
      failSpan(span, "slot_not_found", "Slot not found during conflict check");
      throw new SchedulingError("Slot not found", "SLOT_NOT_FOUND", 404);
    }

    if (faults.conflicts.raceCondition) {
      span.setAttribute("scheduling.conflict.detected", true);
      await sleep(50);
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
