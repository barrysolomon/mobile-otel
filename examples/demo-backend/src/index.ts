import "dotenv/config";
import express from "express";
import { fileURLToPath } from "url";
import { pathToFileURL } from "url";
import { corsMiddleware } from "./middleware/cors.js";
import { getDb } from "./db/connection.js";
import { createSchema } from "./db/schema.js";
import { seedData } from "./db/seed.js";
import healthRouter from "./routes/health.js";
import doctorsRouter from "./routes/doctors.js";

export function createApp() {
  const app = express();
  app.use(corsMiddleware);
  app.use(express.json());
  app.use(healthRouter);
  app.use(doctorsRouter);
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
