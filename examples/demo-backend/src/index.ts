import "dotenv/config";
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
  app.use(healthRouter);
  app.use(adminRouter);
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
