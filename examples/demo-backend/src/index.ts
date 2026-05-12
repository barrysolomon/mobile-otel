import "dotenv/config";
import "./tracing.js";
import express from "express";
import { fileURLToPath } from "url";
import { pathToFileURL } from "url";
import { corsMiddleware } from "./middleware/cors.js";
import { simulateMiddleware } from "./middleware/simulate.js";
import { getDb } from "./db/connection.js";
import { createSchema } from "./db/schema.js";
import { seedData } from "./db/seed.js";
import healthRouter from "./routes/health.js";
import adminRouter from "./routes/admin.js";
import doctorsRouter from "./routes/doctors.js";
import slotsRouter from "./routes/slots.js";
import appointmentsRouter from "./routes/appointments.js";

export function createApp() {
  const app = express();
  app.use(corsMiddleware);
  app.use(express.json());
  // Log incoming requests with trace context for debugging
  app.use((req, _res, next) => {
    const tp = req.headers["traceparent"] || "(none)";
    console.log(`[req] ${req.method} ${req.path} traceparent=${tp}`);
    next();
  });
  app.use(healthRouter);
  app.use(adminRouter);
  // Debug helper: any path under /api/force-500/* returns HTTP 503. Used
  // by Schedulr's DebugToolbar HTTP 500 button (and Android's equivalent)
  // to exercise the SDK's http.error emission path through a real
  // 5xx response — the only reliable way to verify that
  // OTelURLProtocol / OTelNetworkInterceptor emit `event.name=http.error`
  // logs end-to-end on a simulator/emulator.
  app.get(/^\/api\/force-500(\/.*)?$/, (_req, res) => {
    res.status(503).json({ error: "Service Unavailable (forced for demo)" });
  });
  app.use(simulateMiddleware);
  app.use(doctorsRouter);
  app.use(slotsRouter);
  app.use(appointmentsRouter);
  return app;
}

const isMainModule = import.meta.url === pathToFileURL(process.argv[1] ?? "").href;
if (isMainModule) {
  const port = process.env.PORT || 3001;
  const db = getDb();
  createSchema(db);
  seedData(db);
  const app = createApp();
  app.listen(port, () => {
    console.log(`Demo backend listening on :${port}`);
  });
}
