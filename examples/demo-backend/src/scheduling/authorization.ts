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
    if (faults.authorization.timeout > 0) {
      await sleep(faults.authorization.timeout);
      failSpan(span, "timeout", `Authorization timed out after ${faults.authorization.timeout}ms`);
      throw new SchedulingError(
        "Authorization service timed out",
        "AUTH_TIMEOUT",
        504,
      );
    }

    const baseDelay = randomBetween(200, 500);
    await sleep(baseDelay);

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
