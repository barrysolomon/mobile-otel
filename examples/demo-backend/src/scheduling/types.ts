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
