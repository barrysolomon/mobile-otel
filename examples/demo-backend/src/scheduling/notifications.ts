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

    if (faults.notifications.slowChannel === channel) {
      delay += 2000;
    }

    await sleep(delay);

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
