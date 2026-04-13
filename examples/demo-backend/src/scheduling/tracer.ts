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
