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

    if (!provider.isPrimary && faults.availability.timeout > 0) {
      await sleep(faults.availability.timeout);
      failSpan(span, "timeout", `Provider ${provider.name} timed out after ${faults.availability.timeout}ms`);
      throw new Error("Provider timeout");
    }

    if (faults.availability.errorRate > 0 && Math.random() < faults.availability.errorRate) {
      await sleep(baseDelay);
      failSpan(span, "provider_error", `Provider ${provider.name} returned an error`);
      throw new Error("Provider error");
    }

    await sleep(baseDelay);

    const db = getDb();
    const slots = db.prepare(
      "SELECT COUNT(*) as count FROM slots WHERE doctor_id = ? AND available = 1",
    ).get(provider.id) as { count: number };

    span.setAttribute("scheduling.provider.slots_found", slots.count);
    return slots.count;
  } finally {
    span.end();
  }
}
