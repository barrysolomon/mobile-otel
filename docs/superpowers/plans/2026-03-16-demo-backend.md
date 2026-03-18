# Demo Backend Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Node.js/Express/SQLite backend for the appointment scheduling demo app, producing distributed traces (Kotlin mobile → Node.js backend → SQLite) visible end-to-end in Dash0.

**Architecture:** Express app with OTel auto-instrumentation, SQLite via better-sqlite3, simulation middleware for controllable failures. The mobile app's OkHttp interceptor injects W3C traceparent headers; the backend auto-extracts them for end-to-end trace continuity.

**Tech Stack:** Node.js 20, TypeScript, Express, better-sqlite3, @opentelemetry/sdk-node, tsx, nodemon, Docker

**Spec:** `docs/superpowers/specs/2026-03-16-demo-backend-design.md`

---

## File Structure

| File | Responsibility |
|------|---------------|
| `examples/demo-backend/package.json` | Dependencies, scripts (dev, start, seed) |
| `examples/demo-backend/tsconfig.json` | TypeScript strict config |
| `examples/demo-backend/.env.example` | Template for OTLP endpoint, auth, port |
| `examples/demo-backend/.gitignore` | node_modules, .env, *.db |
| `examples/demo-backend/src/tracing.ts` | OTel SDK init — must be imported first |
| `examples/demo-backend/src/index.ts` | Express app, middleware wiring, server start |
| `examples/demo-backend/src/db/schema.ts` | Create tables, PRAGMA foreign_keys |
| `examples/demo-backend/src/db/seed.ts` | Insert doctors + slots |
| `examples/demo-backend/src/db/connection.ts` | Single DB connection export |
| `examples/demo-backend/src/routes/health.ts` | GET /health |
| `examples/demo-backend/src/routes/doctors.ts` | GET /api/doctors |
| `examples/demo-backend/src/routes/slots.ts` | GET /api/slots |
| `examples/demo-backend/src/routes/appointments.ts` | CRUD /api/appointments |
| `examples/demo-backend/src/routes/admin.ts` | Simulation control endpoints |
| `examples/demo-backend/src/middleware/simulate.ts` | Error/latency/crash simulation |
| `examples/demo-backend/src/middleware/cors.ts` | CORS middleware |
| `examples/demo-backend/Dockerfile` | Multi-stage Node.js build |
| `examples/demo-backend/docker-compose.yml` | Single service, port 3001 |
| `examples/demo-backend/.dockerignore` | Exclude node_modules, .env, *.db |
| `examples/demo-backend/tests/health.test.ts` | Health endpoint test |
| `examples/demo-backend/tests/doctors.test.ts` | Doctors endpoint test |
| `examples/demo-backend/tests/slots.test.ts` | Slots endpoint test |
| `examples/demo-backend/tests/appointments.test.ts` | Appointments CRUD tests |
| `examples/demo-backend/tests/simulate.test.ts` | Simulation middleware tests |
| `examples/demo-backend/tests/helpers.ts` | Test setup: in-memory DB, app factory |

---

## Chunk 1: Project Scaffold & Database

### Task 1: Initialize project

**Files:**
- Create: `examples/demo-backend/package.json`
- Create: `examples/demo-backend/tsconfig.json`
- Create: `examples/demo-backend/.env.example`
- Create: `examples/demo-backend/.gitignore`

- [ ] **Step 1: Create package.json**

```json
{
  "name": "demo-backend",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "nodemon --exec 'tsx --import ./src/tracing.ts src/index.ts'",
    "start": "node --import ./src/tracing.js src/index.js",
    "build": "tsc",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "better-sqlite3": "^11.0.0",
    "cors": "^2.8.5",
    "dotenv": "^16.4.0",
    "express": "^4.21.0",
    "uuid": "^10.0.0",
    "@opentelemetry/sdk-node": "^0.57.0",
    "@opentelemetry/auto-instrumentations-node": "^0.56.0",
    "@opentelemetry/exporter-trace-otlp-grpc": "^0.57.0",
    "@opentelemetry/resources": "^1.30.0",
    "@opentelemetry/semantic-conventions": "^1.30.0"
  },
  "devDependencies": {
    "@types/better-sqlite3": "^7.6.0",
    "@types/cors": "^2.8.0",
    "@types/express": "^5.0.0",
    "@types/uuid": "^10.0.0",
    "nodemon": "^3.1.0",
    "tsx": "^4.19.0",
    "typescript": "^5.7.0",
    "vitest": "^3.0.0",
    "supertest": "^7.0.0",
    "@types/supertest": "^6.0.0"
  }
}
```

- [ ] **Step 2: Create tsconfig.json**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "Node16",
    "moduleResolution": "Node16",
    "outDir": "dist",
    "rootDir": "src",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "forceConsistentCasingInFileNames": true,
    "resolveJsonModule": true,
    "declaration": true
  },
  "include": ["src/**/*"],
  "exclude": ["node_modules", "dist", "tests"]
}
```

- [ ] **Step 3: Create .env.example**

```
# Backend port
PORT=3001

# OTel OTLP exporter config
OTEL_EXPORTER_OTLP_ENDPOINT=https://your-collector:4317
OTEL_EXPORTER_OTLP_HEADERS=Authorization=Bearer YOUR_AUTH_TOKEN,Dash0-Dataset=otel-mobile

# OTel service identity
OTEL_SERVICE_NAME=appointment-backend
```

- [ ] **Step 4: Create .gitignore**

```
node_modules/
dist/
.env
*.db
```

- [ ] **Step 5: Run npm install**

Run: `cd examples/demo-backend && npm install`
Expected: `node_modules/` created, no errors

- [ ] **Step 6: Commit**

```bash
git add examples/demo-backend/package.json examples/demo-backend/tsconfig.json examples/demo-backend/.env.example examples/demo-backend/.gitignore examples/demo-backend/package-lock.json
git commit -m "feat(demo-backend): initialize Node.js project scaffold"
```

---

### Task 2: Database connection, schema, and seed data

**Files:**
- Create: `examples/demo-backend/src/db/connection.ts`
- Create: `examples/demo-backend/src/db/schema.ts`
- Create: `examples/demo-backend/src/db/seed.ts`

- [ ] **Step 1: Write test for database setup**

Create `examples/demo-backend/tests/helpers.ts`:

```typescript
import Database from "better-sqlite3";
import { createSchema } from "../src/db/schema.js";
import { seedData } from "../src/db/seed.js";

export function createTestDb(): Database.Database {
  const db = new Database(":memory:");
  db.pragma("foreign_keys = ON");
  createSchema(db);
  seedData(db);
  return db;
}
```

Create `examples/demo-backend/tests/db.test.ts`:

```typescript
import { describe, it, expect } from "vitest";
import { createTestDb } from "./helpers.js";

describe("database", () => {
  it("creates tables and seeds doctors", () => {
    const db = createTestDb();
    const doctors = db.prepare("SELECT * FROM doctors").all();
    expect(doctors).toHaveLength(4);
  });

  it("seeds slots for each doctor", () => {
    const db = createTestDb();
    const slots = db.prepare("SELECT * FROM slots").all();
    expect(slots.length).toBeGreaterThanOrEqual(40);
  });

  it("starts with no appointments", () => {
    const db = createTestDb();
    const appointments = db.prepare("SELECT * FROM appointments").all();
    expect(appointments).toHaveLength(0);
  });

  it("enforces foreign keys", () => {
    const db = createTestDb();
    expect(() => {
      db.prepare("INSERT INTO slots (id, doctor_id, date, time, available) VALUES (?, ?, ?, ?, ?)").run(
        "bad-slot", "nonexistent-doctor", "2026-03-20", "09:00", 1
      );
    }).toThrow();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd examples/demo-backend && npx vitest run tests/db.test.ts`
Expected: FAIL — modules not found

- [ ] **Step 3: Create connection.ts**

```typescript
import Database from "better-sqlite3";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

let db: Database.Database;

export function getDb(): Database.Database {
  if (!db) {
    const dbPath = path.join(__dirname, "..", "..", "appointments.db");
    db = new Database(dbPath);
    db.pragma("journal_mode = WAL");
    db.pragma("foreign_keys = ON");
  }
  return db;
}

export function setDb(instance: Database.Database): void {
  db = instance;
}
```

- [ ] **Step 4: Create schema.ts**

```typescript
import type Database from "better-sqlite3";

export function createSchema(db: Database.Database): void {
  db.exec(`
    CREATE TABLE IF NOT EXISTS doctors (
      id          TEXT PRIMARY KEY,
      name        TEXT NOT NULL,
      specialty   TEXT NOT NULL,
      location    TEXT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS slots (
      id          TEXT PRIMARY KEY,
      doctor_id   TEXT NOT NULL REFERENCES doctors(id),
      date        TEXT NOT NULL,
      time        TEXT NOT NULL,
      available   INTEGER NOT NULL DEFAULT 1
    );

    CREATE TABLE IF NOT EXISTS appointments (
      id          TEXT PRIMARY KEY,
      doctor_id   TEXT NOT NULL REFERENCES doctors(id),
      slot_id     TEXT NOT NULL REFERENCES slots(id),
      patient     TEXT NOT NULL,
      reason      TEXT NOT NULL,
      status      TEXT NOT NULL DEFAULT 'confirmed',
      created_at  TEXT NOT NULL
    );
  `);
}
```

- [ ] **Step 5: Create seed.ts**

```typescript
import type Database from "better-sqlite3";
import { v4 as uuid } from "uuid";

const DOCTORS = [
  { name: "Dr. Sarah Chen", specialty: "General Practice", location: "Building A, Suite 100" },
  { name: "Dr. Marcus Webb", specialty: "General Practice", location: "Building A, Suite 102" },
  { name: "Dr. Elena Rossi", specialty: "Dermatology", location: "Building B, Suite 200" },
  { name: "Dr. James Park", specialty: "Dermatology", location: "Building B, Suite 204" },
];

export function seedData(db: Database.Database): void {
  const existingDoctors = db.prepare("SELECT COUNT(*) as count FROM doctors").get() as { count: number };
  if (existingDoctors.count > 0) return;

  const insertDoctor = db.prepare("INSERT INTO doctors (id, name, specialty, location) VALUES (?, ?, ?, ?)");
  const insertSlot = db.prepare("INSERT INTO slots (id, doctor_id, date, time, available) VALUES (?, ?, ?, ?, 1)");

  const times = ["09:00", "09:30", "10:00", "10:30", "11:00", "11:30", "14:00", "14:30", "15:00", "15:30"];

  const seedAll = db.transaction(() => {
    for (const doc of DOCTORS) {
      const doctorId = uuid();
      insertDoctor.run(doctorId, doc.name, doc.specialty, doc.location);

      const today = new Date();
      for (let dayOffset = 0; dayOffset < 5; dayOffset++) {
        const date = new Date(today);
        date.setDate(today.getDate() + dayOffset);
        const dateStr = date.toISOString().split("T")[0];

        for (const time of times) {
          insertSlot.run(uuid(), doctorId, dateStr, time, 1);
        }
      }
    }
  });

  seedAll();
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd examples/demo-backend && npx vitest run tests/db.test.ts`
Expected: 4 tests PASS

- [ ] **Step 7: Commit**

```bash
git add examples/demo-backend/src/db/ examples/demo-backend/tests/
git commit -m "feat(demo-backend): add SQLite schema, seed data, and DB tests"
```

---

## Chunk 2: Express App, Health, and OTel Tracing

### Task 3: OTel tracing setup

**Files:**
- Create: `examples/demo-backend/src/tracing.ts`

- [ ] **Step 1: Create tracing.ts**

```typescript
import { NodeSDK } from "@opentelemetry/sdk-node";
import { getNodeAutoInstrumentations } from "@opentelemetry/auto-instrumentations-node";
import { OTLPTraceExporter } from "@opentelemetry/exporter-trace-otlp-grpc";
import { Resource } from "@opentelemetry/resources";
import { ATTR_SERVICE_NAME, ATTR_SERVICE_VERSION, ATTR_DEPLOYMENT_ENVIRONMENT_NAME } from "@opentelemetry/semantic-conventions";

const sdk = new NodeSDK({
  resource: new Resource({
    [ATTR_SERVICE_NAME]: process.env.OTEL_SERVICE_NAME || "appointment-backend",
    [ATTR_SERVICE_VERSION]: "0.1.0",
    [ATTR_DEPLOYMENT_ENVIRONMENT_NAME]: "demo",
  }),
  traceExporter: new OTLPTraceExporter(),
  instrumentations: [getNodeAutoInstrumentations()],
});

sdk.start();

process.on("SIGTERM", () => {
  sdk.shutdown().then(() => process.exit(0));
});
```

- [ ] **Step 2: Verify tracing module loads without errors**

Run: `cd examples/demo-backend && npx tsx -e "import './src/tracing.ts'; console.log('OTel SDK initialized'); process.exit(0)"`
Expected: "OTel SDK initialized" with no errors (may show warnings about missing OTLP endpoint, which is fine)

- [ ] **Step 3: Commit**

```bash
git add examples/demo-backend/src/tracing.ts
git commit -m "feat(demo-backend): add OTel SDK tracing setup"
```

---

### Task 4: CORS middleware

**Files:**
- Create: `examples/demo-backend/src/middleware/cors.ts`

- [ ] **Step 1: Create cors.ts**

```typescript
import cors from "cors";

export const corsMiddleware = cors({
  origin: "*",
  methods: ["GET", "POST", "DELETE", "OPTIONS"],
  allowedHeaders: ["Content-Type", "Authorization", "X-Simulate-Error", "X-Simulate-Latency", "X-Simulate-Crash"],
});
```

- [ ] **Step 2: Commit**

```bash
git add examples/demo-backend/src/middleware/cors.ts
git commit -m "feat(demo-backend): add CORS middleware"
```

---

### Task 5: Health endpoint and Express app

**Files:**
- Create: `examples/demo-backend/src/routes/health.ts`
- Create: `examples/demo-backend/src/index.ts`
- Create: `examples/demo-backend/tests/health.test.ts`

- [ ] **Step 1: Write failing test**

Create `examples/demo-backend/tests/health.test.ts`:

```typescript
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import request from "supertest";
import { createApp } from "../src/index.js";
import Database from "better-sqlite3";
import { createSchema } from "../src/db/schema.js";
import { seedData } from "../src/db/seed.js";
import { setDb } from "../src/db/connection.js";

describe("GET /health", () => {
  let app: ReturnType<typeof createApp>;

  beforeAll(() => {
    const db = new Database(":memory:");
    db.pragma("foreign_keys = ON");
    createSchema(db);
    seedData(db);
    setDb(db);
    app = createApp();
  });

  it("returns ok", async () => {
    const res = await request(app).get("/health");
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ status: "ok" });
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd examples/demo-backend && npx vitest run tests/health.test.ts`
Expected: FAIL — createApp not found

- [ ] **Step 3: Create health route**

```typescript
import { Router } from "express";

const router = Router();

router.get("/health", (_req, res) => {
  res.json({ status: "ok" });
});

export default router;
```

- [ ] **Step 4: Create index.ts**

```typescript
import "dotenv/config";
import express from "express";
import { corsMiddleware } from "./middleware/cors.js";
import { getDb } from "./db/connection.js";
import { createSchema } from "./db/schema.js";
import { seedData } from "./db/seed.js";
import healthRouter from "./routes/health.js";

export function createApp() {
  const app = express();
  app.use(corsMiddleware);
  app.use(express.json());
  app.use(healthRouter);
  return app;
}

import { fileURLToPath } from "url";
import { pathToFileURL } from "url";

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
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd examples/demo-backend && npx vitest run tests/health.test.ts`
Expected: PASS

- [ ] **Step 6: Verify server starts manually**

Run: `cd examples/demo-backend && npx tsx src/index.ts &`
Run: `curl http://localhost:3001/health`
Expected: `{"status":"ok"}`
Kill the server after.

- [ ] **Step 7: Commit**

```bash
git add examples/demo-backend/src/routes/health.ts examples/demo-backend/src/index.ts examples/demo-backend/tests/health.test.ts
git commit -m "feat(demo-backend): add Express app with health endpoint"
```

---

## Chunk 3: Business Endpoints — Doctors, Slots, Appointments

### Task 6: GET /api/doctors

**Files:**
- Create: `examples/demo-backend/src/routes/doctors.ts`
- Create: `examples/demo-backend/tests/doctors.test.ts`
- Modify: `examples/demo-backend/src/index.ts` (add route)

- [ ] **Step 1: Write failing test**

```typescript
import { describe, it, expect, beforeAll } from "vitest";
import request from "supertest";
import { createApp } from "../src/index.js";
import Database from "better-sqlite3";
import { createSchema } from "../src/db/schema.js";
import { seedData } from "../src/db/seed.js";
import { setDb } from "../src/db/connection.js";

describe("GET /api/doctors", () => {
  let app: ReturnType<typeof createApp>;

  beforeAll(() => {
    const db = new Database(":memory:");
    db.pragma("foreign_keys = ON");
    createSchema(db);
    seedData(db);
    setDb(db);
    app = createApp();
  });

  it("returns all 4 doctors", async () => {
    const res = await request(app).get("/api/doctors");
    expect(res.status).toBe(200);
    expect(res.body).toHaveLength(4);
    expect(res.body[0]).toHaveProperty("name");
    expect(res.body[0]).toHaveProperty("specialty");
    expect(res.body[0]).toHaveProperty("location");
  });
});
```

- [ ] **Step 2: Run test, verify failure**

Run: `cd examples/demo-backend && npx vitest run tests/doctors.test.ts`
Expected: FAIL — 404

- [ ] **Step 3: Implement doctors route**

```typescript
import { Router } from "express";
import { getDb } from "../db/connection.js";

const router = Router();

router.get("/api/doctors", (_req, res) => {
  const doctors = getDb().prepare("SELECT * FROM doctors").all();
  res.json(doctors);
});

export default router;
```

- [ ] **Step 4: Wire route into index.ts**

Add import and `app.use(doctorsRouter)` in `createApp()`.

- [ ] **Step 5: Run test, verify pass**

Run: `cd examples/demo-backend && npx vitest run tests/doctors.test.ts`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add examples/demo-backend/src/routes/doctors.ts examples/demo-backend/tests/doctors.test.ts examples/demo-backend/src/index.ts
git commit -m "feat(demo-backend): add GET /api/doctors endpoint"
```

---

### Task 7: GET /api/slots

**Files:**
- Create: `examples/demo-backend/src/routes/slots.ts`
- Create: `examples/demo-backend/tests/slots.test.ts`
- Modify: `examples/demo-backend/src/index.ts`

- [ ] **Step 1: Write failing test**

```typescript
import { describe, it, expect, beforeAll } from "vitest";
import request from "supertest";
import { createApp } from "../src/index.js";
import Database from "better-sqlite3";
import { createSchema } from "../src/db/schema.js";
import { seedData } from "../src/db/seed.js";
import { setDb } from "../src/db/connection.js";

describe("GET /api/slots", () => {
  let app: ReturnType<typeof createApp>;
  let doctorId: string;

  beforeAll(() => {
    const db = new Database(":memory:");
    db.pragma("foreign_keys = ON");
    createSchema(db);
    seedData(db);
    setDb(db);
    app = createApp();
    const doc = db.prepare("SELECT id FROM doctors LIMIT 1").get() as { id: string };
    doctorId = doc.id;
  });

  it("returns 400 without doctor_id", async () => {
    const res = await request(app).get("/api/slots");
    expect(res.status).toBe(400);
    expect(res.body).toHaveProperty("error");
  });

  it("returns slots for a doctor", async () => {
    const res = await request(app).get(`/api/slots?doctor_id=${doctorId}`);
    expect(res.status).toBe(200);
    expect(res.body.length).toBeGreaterThan(0);
    expect(res.body[0]).toHaveProperty("date");
    expect(res.body[0]).toHaveProperty("time");
    expect(res.body[0]).toHaveProperty("available");
  });

  it("filters by date", async () => {
    const today = new Date().toISOString().split("T")[0];
    const res = await request(app).get(`/api/slots?doctor_id=${doctorId}&date=${today}`);
    expect(res.status).toBe(200);
    for (const slot of res.body) {
      expect(slot.date).toBe(today);
    }
  });
});
```

- [ ] **Step 2: Run test, verify failure**

Run: `cd examples/demo-backend && npx vitest run tests/slots.test.ts`

- [ ] **Step 3: Implement slots route**

```typescript
import { Router } from "express";
import { getDb } from "../db/connection.js";

const router = Router();

router.get("/api/slots", (req, res) => {
  const { doctor_id, date } = req.query;

  if (!doctor_id) {
    res.status(400).json({ error: "doctor_id query parameter is required", code: "MISSING_DOCTOR_ID" });
    return;
  }

  let sql = "SELECT * FROM slots WHERE doctor_id = ? AND available = 1";
  const params: string[] = [doctor_id as string];

  if (date) {
    sql += " AND date = ?";
    params.push(date as string);
  }

  sql += " ORDER BY date, time";

  const slots = getDb().prepare(sql).all(...params);
  res.json(slots);
});

export default router;
```

- [ ] **Step 4: Wire route into index.ts**

- [ ] **Step 5: Run test, verify pass**

Run: `cd examples/demo-backend && npx vitest run tests/slots.test.ts`

- [ ] **Step 6: Commit**

```bash
git add examples/demo-backend/src/routes/slots.ts examples/demo-backend/tests/slots.test.ts examples/demo-backend/src/index.ts
git commit -m "feat(demo-backend): add GET /api/slots endpoint"
```

---

### Task 8: Appointments CRUD

**Files:**
- Create: `examples/demo-backend/src/routes/appointments.ts`
- Create: `examples/demo-backend/tests/appointments.test.ts`
- Modify: `examples/demo-backend/src/index.ts`

- [ ] **Step 1: Write failing tests**

```typescript
import { describe, it, expect, beforeEach } from "vitest";
import request from "supertest";
import { createApp } from "../src/index.js";
import Database from "better-sqlite3";
import { createSchema } from "../src/db/schema.js";
import { seedData } from "../src/db/seed.js";
import { setDb } from "../src/db/connection.js";

describe("Appointments API", () => {
  let app: ReturnType<typeof createApp>;
  let db: Database.Database;
  let doctorId: string;
  let slotId: string;

  beforeEach(() => {
    db = new Database(":memory:");
    db.pragma("foreign_keys = ON");
    createSchema(db);
    seedData(db);
    setDb(db);
    app = createApp();
    const doc = db.prepare("SELECT id FROM doctors LIMIT 1").get() as { id: string };
    doctorId = doc.id;
    const slot = db.prepare("SELECT id FROM slots WHERE doctor_id = ? LIMIT 1").get(doctorId) as { id: string };
    slotId = slot.id;
  });

  describe("POST /api/appointments", () => {
    it("books an appointment", async () => {
      const res = await request(app)
        .post("/api/appointments")
        .send({ doctor_id: doctorId, slot_id: slotId, patient: "John Doe", reason: "Checkup" });
      expect(res.status).toBe(201);
      expect(res.body).toHaveProperty("id");
      expect(res.body.status).toBe("confirmed");
    });

    it("returns 400 with MISSING_FIELDS code for missing fields", async () => {
      const res = await request(app)
        .post("/api/appointments")
        .send({ doctor_id: doctorId });
      expect(res.status).toBe(400);
      expect(res.body.code).toBe("MISSING_FIELDS");
    });

    it("returns 404 with DOCTOR_NOT_FOUND for nonexistent doctor", async () => {
      const res = await request(app)
        .post("/api/appointments")
        .send({ doctor_id: "nonexistent", slot_id: slotId, patient: "John Doe", reason: "Checkup" });
      expect(res.status).toBe(404);
      expect(res.body.code).toBe("DOCTOR_NOT_FOUND");
    });

    it("returns 404 with SLOT_NOT_FOUND for nonexistent slot", async () => {
      const res = await request(app)
        .post("/api/appointments")
        .send({ doctor_id: doctorId, slot_id: "nonexistent", patient: "John Doe", reason: "Checkup" });
      expect(res.status).toBe(404);
      expect(res.body.code).toBe("SLOT_NOT_FOUND");
    });

    it("returns 409 with SLOT_UNAVAILABLE for already-booked slot", async () => {
      await request(app)
        .post("/api/appointments")
        .send({ doctor_id: doctorId, slot_id: slotId, patient: "John Doe", reason: "Checkup" });
      const res = await request(app)
        .post("/api/appointments")
        .send({ doctor_id: doctorId, slot_id: slotId, patient: "Jane Doe", reason: "Followup" });
      expect(res.status).toBe(409);
      expect(res.body.code).toBe("SLOT_UNAVAILABLE");
    });

    it("marks slot as unavailable after booking", async () => {
      await request(app)
        .post("/api/appointments")
        .send({ doctor_id: doctorId, slot_id: slotId, patient: "John Doe", reason: "Checkup" });
      const slot = db.prepare("SELECT available FROM slots WHERE id = ?").get(slotId) as { available: number };
      expect(slot.available).toBe(0);
    });
  });

  describe("GET /api/appointments", () => {
    it("returns all appointments", async () => {
      await request(app)
        .post("/api/appointments")
        .send({ doctor_id: doctorId, slot_id: slotId, patient: "John Doe", reason: "Checkup" });
      const res = await request(app).get("/api/appointments");
      expect(res.status).toBe(200);
      expect(res.body).toHaveLength(1);
    });
  });

  describe("GET /api/appointments/:id", () => {
    it("returns a single appointment", async () => {
      const created = await request(app)
        .post("/api/appointments")
        .send({ doctor_id: doctorId, slot_id: slotId, patient: "John Doe", reason: "Checkup" });
      const res = await request(app).get(`/api/appointments/${created.body.id}`);
      expect(res.status).toBe(200);
      expect(res.body.patient).toBe("John Doe");
    });

    it("returns 404 with NOT_FOUND code for nonexistent appointment", async () => {
      const res = await request(app).get("/api/appointments/nonexistent");
      expect(res.status).toBe(404);
      expect(res.body.code).toBe("NOT_FOUND");
    });
  });

  describe("DELETE /api/appointments/:id", () => {
    it("cancels an appointment and frees the slot", async () => {
      const created = await request(app)
        .post("/api/appointments")
        .send({ doctor_id: doctorId, slot_id: slotId, patient: "John Doe", reason: "Checkup" });
      const res = await request(app).delete(`/api/appointments/${created.body.id}`);
      expect(res.status).toBe(200);
      expect(res.body.status).toBe("cancelled");

      const slot = db.prepare("SELECT available FROM slots WHERE id = ?").get(slotId) as { available: number };
      expect(slot.available).toBe(1);
    });

    it("returns 404 with NOT_FOUND code for nonexistent appointment", async () => {
      const res = await request(app).delete("/api/appointments/nonexistent");
      expect(res.status).toBe(404);
      expect(res.body.code).toBe("NOT_FOUND");
    });
  });
});
```

- [ ] **Step 2: Run test, verify failure**

Run: `cd examples/demo-backend && npx vitest run tests/appointments.test.ts`

- [ ] **Step 3: Implement appointments route**

```typescript
import { Router } from "express";
import { v4 as uuid } from "uuid";
import { getDb } from "../db/connection.js";

const router = Router();

router.get("/api/appointments", (_req, res) => {
  const appointments = getDb().prepare("SELECT * FROM appointments ORDER BY created_at DESC").all();
  res.json(appointments);
});

router.get("/api/appointments/:id", (req, res) => {
  const appointment = getDb().prepare("SELECT * FROM appointments WHERE id = ?").get(req.params.id);
  if (!appointment) {
    res.status(404).json({ error: "Appointment not found", code: "NOT_FOUND" });
    return;
  }
  res.json(appointment);
});

router.post("/api/appointments", (req, res) => {
  const { doctor_id, slot_id, patient, reason } = req.body;

  if (!doctor_id || !slot_id || !patient || !reason) {
    res.status(400).json({ error: "doctor_id, slot_id, patient, and reason are required", code: "MISSING_FIELDS" });
    return;
  }

  const db = getDb();

  const doctor = db.prepare("SELECT id FROM doctors WHERE id = ?").get(doctor_id);
  if (!doctor) {
    res.status(404).json({ error: "Doctor not found", code: "DOCTOR_NOT_FOUND" });
    return;
  }

  const slot = db.prepare("SELECT * FROM slots WHERE id = ?").get(slot_id) as { available: number } | undefined;
  if (!slot) {
    res.status(404).json({ error: "Slot not found", code: "SLOT_NOT_FOUND" });
    return;
  }
  if (!slot.available) {
    res.status(409).json({ error: "Slot is already booked", code: "SLOT_UNAVAILABLE" });
    return;
  }

  const id = uuid();
  const now = new Date().toISOString();

  const bookAppointment = db.transaction(() => {
    db.prepare("INSERT INTO appointments (id, doctor_id, slot_id, patient, reason, status, created_at) VALUES (?, ?, ?, ?, ?, 'confirmed', ?)").run(id, doctor_id, slot_id, patient, reason, now);
    db.prepare("UPDATE slots SET available = 0 WHERE id = ?").run(slot_id);
  });

  bookAppointment();

  const appointment = db.prepare("SELECT * FROM appointments WHERE id = ?").get(id);
  res.status(201).json(appointment);
});

router.delete("/api/appointments/:id", (req, res) => {
  const db = getDb();
  const appointment = db.prepare("SELECT * FROM appointments WHERE id = ?").get(req.params.id) as { slot_id: string } | undefined;

  if (!appointment) {
    res.status(404).json({ error: "Appointment not found", code: "NOT_FOUND" });
    return;
  }

  const cancelAppointment = db.transaction(() => {
    db.prepare("UPDATE appointments SET status = 'cancelled' WHERE id = ?").run(req.params.id);
    db.prepare("UPDATE slots SET available = 1 WHERE id = ?").run(appointment.slot_id);
  });

  cancelAppointment();

  res.json({ status: "cancelled" });
});

export default router;
```

- [ ] **Step 4: Wire route into index.ts**

- [ ] **Step 5: Run test, verify pass**

Run: `cd examples/demo-backend && npx vitest run tests/appointments.test.ts`
Expected: All 7 tests PASS

- [ ] **Step 6: Commit**

```bash
git add examples/demo-backend/src/routes/appointments.ts examples/demo-backend/tests/appointments.test.ts examples/demo-backend/src/index.ts
git commit -m "feat(demo-backend): add appointments CRUD endpoints"
```

---

## Chunk 4: Simulation System & Admin Endpoints

### Task 9: Simulation middleware

**Files:**
- Create: `examples/demo-backend/src/middleware/simulate.ts`
- Create: `examples/demo-backend/tests/simulate.test.ts`

- [ ] **Step 1: Write failing test**

```typescript
import { describe, it, expect, beforeEach } from "vitest";
import request from "supertest";
import { createApp } from "../src/index.js";
import Database from "better-sqlite3";
import { createSchema } from "../src/db/schema.js";
import { seedData } from "../src/db/seed.js";
import { setDb } from "../src/db/connection.js";
import { resetSimulation } from "../src/middleware/simulate.js";

describe("Simulation", () => {
  let app: ReturnType<typeof createApp>;

  beforeEach(() => {
    const db = new Database(":memory:");
    db.pragma("foreign_keys = ON");
    createSchema(db);
    seedData(db);
    setDb(db);
    resetSimulation();
    app = createApp();
  });

  describe("Admin endpoints", () => {
    it("GET /api/admin/simulate returns default state", async () => {
      const res = await request(app).get("/api/admin/simulate");
      expect(res.status).toBe(200);
      expect(res.body).toEqual({ error: false, latency: 0, crash: false });
    });

    it("POST /api/admin/simulate sets state", async () => {
      const res = await request(app)
        .post("/api/admin/simulate")
        .send({ error: true, latency: 500 });
      expect(res.status).toBe(200);
      expect(res.body.error).toBe(true);
      expect(res.body.latency).toBe(500);
    });

    it("DELETE /api/admin/simulate resets state", async () => {
      await request(app).post("/api/admin/simulate").send({ error: true });
      await request(app).delete("/api/admin/simulate");
      const res = await request(app).get("/api/admin/simulate");
      expect(res.body).toEqual({ error: false, latency: 0, crash: false });
    });
  });

  describe("Error simulation", () => {
    it("returns 503 on business endpoints when error enabled", async () => {
      await request(app).post("/api/admin/simulate").send({ error: true });
      const res = await request(app).get("/api/doctors");
      expect(res.status).toBe(503);
      expect(res.body.error).toBe("Simulated server error");
    });

    it("does not affect admin endpoints", async () => {
      await request(app).post("/api/admin/simulate").send({ error: true });
      const res = await request(app).get("/api/admin/simulate");
      expect(res.status).toBe(200);
    });

    it("does not affect health endpoint", async () => {
      await request(app).post("/api/admin/simulate").send({ error: true });
      const res = await request(app).get("/health");
      expect(res.status).toBe(200);
    });
  });

  describe("Latency simulation", () => {
    it("delays response when latency is set", async () => {
      await request(app).post("/api/admin/simulate").send({ latency: 200 });
      const start = Date.now();
      const res = await request(app).get("/api/doctors");
      const elapsed = Date.now() - start;
      expect(res.status).toBe(200);
      expect(elapsed).toBeGreaterThanOrEqual(180); // allow small timing variance
    });

    it("X-Simulate-Latency header delays response", async () => {
      const start = Date.now();
      const res = await request(app).get("/api/doctors").set("X-Simulate-Latency", "200");
      const elapsed = Date.now() - start;
      expect(res.status).toBe(200);
      expect(elapsed).toBeGreaterThanOrEqual(180);
    });
  });

  describe("Per-request header override", () => {
    it("X-Simulate-Error forces 503", async () => {
      const res = await request(app).get("/api/doctors").set("X-Simulate-Error", "true");
      expect(res.status).toBe(503);
    });
  });

  // Note: Crash simulation (process.exit) cannot be tested in vitest.
  // Verify manually: curl with X-Simulate-Crash header, observe nodemon/Docker restart.
});
```

- [ ] **Step 2: Run test, verify failure**

Run: `cd examples/demo-backend && npx vitest run tests/simulate.test.ts`

- [ ] **Step 3: Implement simulate middleware**

```typescript
interface SimulationState {
  error: boolean;
  latency: number;
  crash: boolean;
}

let state: SimulationState = { error: false, latency: 0, crash: false };

export function getSimulationState(): SimulationState {
  return { ...state };
}

export function setSimulationState(update: Partial<SimulationState>): SimulationState {
  state = { ...state, ...update };
  return { ...state };
}

export function resetSimulation(): void {
  state = { error: false, latency: 0, crash: false };
}

export function simulateMiddleware(req: any, res: any, next: any): void {
  // Skip admin and health endpoints
  if (req.path.startsWith("/api/admin") || req.path === "/health") {
    next();
    return;
  }

  // Check per-request header overrides first, then server state
  const shouldError = req.headers["x-simulate-error"] === "true" || state.error;
  const latencyMs = parseInt(req.headers["x-simulate-latency"] as string) || state.latency;
  const shouldCrash = req.headers["x-simulate-crash"] === "true" || state.crash;

  if (shouldCrash) {
    console.log("Simulated crash — exiting process");
    process.exit(1);
  }

  const proceed = () => {
    if (shouldError) {
      res.status(503).json({ error: "Simulated server error", code: "SIMULATED_ERROR" });
      return;
    }
    next();
  };

  if (latencyMs > 0) {
    setTimeout(proceed, latencyMs);
  } else {
    proceed();
  }
}
```

- [ ] **Step 4: Create admin route**

Create `examples/demo-backend/src/routes/admin.ts`:

```typescript
import { Router } from "express";
import { getSimulationState, setSimulationState, resetSimulation } from "../middleware/simulate.js";

const router = Router();

router.get("/api/admin/simulate", (_req, res) => {
  res.json(getSimulationState());
});

router.post("/api/admin/simulate", (req, res) => {
  const updated = setSimulationState(req.body);
  res.json(updated);
});

router.delete("/api/admin/simulate", (_req, res) => {
  resetSimulation();
  res.json(getSimulationState());
});

export default router;
```

- [ ] **Step 5: Wire middleware and admin route into index.ts**

In `createApp()`:
- Add `app.use(simulateMiddleware)` **after** `express.json()` but **before** business routes
- Add `app.use(adminRouter)` **before** `simulateMiddleware` so admin is never affected

Actually, the cleaner pattern: mount admin router first, then simulation middleware, then business routes. The middleware skips `/api/admin` and `/health` paths explicitly.

```typescript
// In createApp():
app.use(healthRouter);
app.use(adminRouter);
app.use(simulateMiddleware);
app.use(doctorsRouter);
app.use(slotsRouter);
app.use(appointmentsRouter);
```

- [ ] **Step 6: Run test, verify pass**

Run: `cd examples/demo-backend && npx vitest run tests/simulate.test.ts`
Expected: All tests PASS

- [ ] **Step 7: Run full test suite**

Run: `cd examples/demo-backend && npx vitest run`
Expected: All tests pass across all files

- [ ] **Step 8: Commit**

```bash
git add examples/demo-backend/src/middleware/simulate.ts examples/demo-backend/src/routes/admin.ts examples/demo-backend/tests/simulate.test.ts examples/demo-backend/src/index.ts
git commit -m "feat(demo-backend): add simulation middleware and admin endpoints"
```

---

## Chunk 5: Docker & Deployment

### Task 10: Dockerfile, docker-compose, and .dockerignore

**Files:**
- Create: `examples/demo-backend/Dockerfile`
- Create: `examples/demo-backend/docker-compose.yml`
- Create: `examples/demo-backend/.dockerignore`

- [ ] **Step 1: Create Dockerfile**

Uses `tsx` for simplicity — fast enough for a demo backend, avoids a separate build step.

```dockerfile
FROM node:20-alpine AS builder
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:20-alpine
WORKDIR /app
COPY --from=builder /app/node_modules ./node_modules
COPY package.json tsconfig.json ./
COPY src/ ./src/
EXPOSE 3001
CMD ["npx", "tsx", "--import", "./src/tracing.ts", "src/index.ts"]
```

- [ ] **Step 2: Create docker-compose.yml**

```yaml
services:
  demo-backend:
    build: .
    ports:
      - "3001:3001"
    env_file: .env
    restart: unless-stopped
```

- [ ] **Step 3: Create .dockerignore**

```
node_modules
.env
*.db
dist
.git
```

- [ ] **Step 4: Test Docker build**

Run: `cd examples/demo-backend && docker build -t demo-backend .`
Expected: Build succeeds

- [ ] **Step 5: Test Docker run**

Run: `cd examples/demo-backend && cp .env.example .env && docker compose up -d`
Run: `curl http://localhost:3001/health`
Expected: `{"status":"ok"}`
Run: `docker compose down`

- [ ] **Step 6: Commit**

```bash
git add examples/demo-backend/Dockerfile examples/demo-backend/docker-compose.yml examples/demo-backend/.dockerignore
git commit -m "feat(demo-backend): add Docker and docker-compose support"
```

---

## Chunk 6: Mobile App Integration

### Task 11: Add DELETE method to SchedulingApiClient

**Files:**
- Modify: `examples/demo-app/android/src/main/java/io/opentelemetry/android/demo/data/api/ApiService.kt`

- [ ] **Step 1: Add delete method**

Add to `SchedulingApiClient`, matching the existing `get()`/`post()` code style:

```kotlin
fun delete(url: String): String {
    val request = Request.Builder()
        .url(url)
        .delete()
        .build()
    val response = client.newCall(request).execute()
    val body = response.body?.string() ?: ""
    if (!response.isSuccessful) {
        throw HttpException(response.code, body)
    }
    return body
}
```

- [ ] **Step 2: Commit**

```bash
git add examples/demo-app/android/src/main/java/io/opentelemetry/android/demo/data/api/ApiService.kt
git commit -m "feat(demo-app): add DELETE method to SchedulingApiClient"
```

---

### Task 12: Update AppointmentRepository to use backend

**Files:**
- Modify: `examples/demo-app/android/src/main/java/io/opentelemetry/android/demo/data/AppointmentRepository.kt`

- [ ] **Step 1: Add backend base URL constant**

Add at the top of `AppointmentRepository`:

```kotlin
private const val BACKEND_BASE_URL = "http://10.0.2.2:3001"
```

- [ ] **Step 2: Add JSON parsing helpers and doctor name cache**

Add these private helpers to `AppointmentRepository`. The backend returns `doctor_id` (UUID) but the mobile model uses `provider` (doctor name), so we cache the doctor list.

```kotlin
private var doctorCache: Map<String, String> = emptyMap() // doctor_id -> name

private suspend fun ensureDoctorCache(context: Context) {
    if (doctorCache.isNotEmpty()) return
    try {
        val client = SchedulingApiClient.getInstance(context)
        val json = JSONArray(client.get("$BACKEND_BASE_URL/api/doctors"))
        doctorCache = (0 until json.length()).associate { i ->
            val doc = json.getJSONObject(i)
            doc.getString("id") to doc.getString("name")
        }
    } catch (_: Exception) {
        // If we can't reach backend, cache stays empty — fallback to mock data
    }
}

private fun parseAppointments(json: String): List<Appointment> {
    val arr = JSONArray(json)
    return (0 until arr.length()).map { i ->
        val obj = arr.getJSONObject(i)
        val doctorId = obj.getString("doctor_id")
        val createdAt = obj.getString("created_at")
        Appointment(
            id = obj.getString("id"),
            title = obj.getString("reason"),
            provider = doctorCache[doctorId] ?: "Unknown Doctor",
            dateMs = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .parse(createdAt)?.time ?: System.currentTimeMillis(),
            timeSlot = "", // populated from slot if needed
            type = AppointmentType.CHECKUP, // backend doesn't have type — default
            status = if (obj.getString("status") == "confirmed")
                AppointmentStatus.CONFIRMED else AppointmentStatus.CANCELLED,
            notes = obj.getString("reason")
        )
    }
}
```

Note: Add `import org.json.JSONArray` and `import org.json.JSONObject` at the top. These are part of the Android SDK (no extra dependency needed).

- [ ] **Step 3: Update fetchAppointments to call backend**

Replace the `jsonplaceholder.typicode.com` and `httpbin.org` calls with:

```kotlin
suspend fun fetchAppointments(context: Context): List<Appointment> {
    val otelCtx = OtelContext.current()
    return withContext(Dispatchers.IO) {
        val otelScope = otelCtx.makeCurrent()
        try {
            ensureDoctorCache(context)
            val client = SchedulingApiClient.getInstance(context)
            val json = client.get("$BACKEND_BASE_URL/api/appointments")
            val backendAppointments = parseAppointments(json)
            allAppointments.clear()
            allAppointments.addAll(backendAppointments)
            backendAppointments
        } catch (e: Exception) {
            // Backend unreachable — return mock data (useful demo scenario)
            getMockAppointments()
        } finally {
            otelScope.close()
        }
    }
}
```

- [ ] **Step 4: Update bookAppointment to call backend**

Replace the fake POST with a real one. The backend expects `{doctor_id, slot_id, patient, reason}`. Since the mobile app's booking form uses `provider` (name) and `timeSlot` (string), we need to look up IDs:

```kotlin
suspend fun bookAppointment(
    context: Context,
    provider: String,
    dateMs: Long,
    timeSlot: String,
    type: AppointmentType,
    notes: String
): Appointment {
    val otelCtx = OtelContext.current()
    // Duplicate check (existing logic — keep as-is)
    checkForDuplicate(provider, dateMs, timeSlot)

    return withContext(Dispatchers.IO) {
        val otelScope = otelCtx.makeCurrent()
        try {
            ensureDoctorCache(context)
            val client = SchedulingApiClient.getInstance(context)

            // Find doctor_id from name
            val doctorId = doctorCache.entries.find { it.value == provider }?.key
                ?: throw ApiException(404, "Doctor not found")

            // Find available slot_id for this doctor + date + time
            val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date(dateMs))
            val slotsJson = JSONArray(client.get(
                "$BACKEND_BASE_URL/api/slots?doctor_id=$doctorId&date=$dateStr"
            ))
            // Match time slot (convert "09:00" format to "9:00 AM" for comparison)
            val slotId = findMatchingSlotId(slotsJson, timeSlot)
                ?: throw ApiException(404, "No available slot for $timeSlot")

            val body = JSONObject().apply {
                put("doctor_id", doctorId)
                put("slot_id", slotId)
                put("patient", "Demo User")
                put("reason", notes.ifBlank { type.label })
            }

            val responseJson = client.post("$BACKEND_BASE_URL/api/appointments", body.toString())
            val result = JSONObject(responseJson)

            val appointment = Appointment(
                id = result.getString("id"),
                title = type.label,
                provider = provider,
                dateMs = dateMs,
                timeSlot = timeSlot,
                type = type,
                status = AppointmentStatus.CONFIRMED,
                notes = notes
            )
            allAppointments.add(appointment)
            appointment
        } catch (e: HttpException) {
            throw ApiException(e.code, e.message ?: "Booking failed")
        } finally {
            otelScope.close()
        }
    }
}

private fun findMatchingSlotId(slotsJson: JSONArray, timeSlot: String): String? {
    for (i in 0 until slotsJson.length()) {
        val slot = slotsJson.getJSONObject(i)
        val slotTime = slot.getString("time") // "09:00" format
        // Convert 24h "09:00" to "9:00 AM" for comparison with mobile's timeSlot format
        val hour = slotTime.split(":")[0].toInt()
        val minute = slotTime.split(":")[1]
        val amPm = if (hour < 12) "AM" else "PM"
        val displayHour = if (hour == 0) 12 else if (hour > 12) hour - 12 else hour
        val formatted = "$displayHour:$minute $amPm"
        if (formatted == timeSlot) return slot.getString("id")
    }
    return null
}
```

- [ ] **Step 5: Verify existing unit tests still pass**

Run: `cd examples/demo-app && ./gradlew :android:testDebugUnitTest`
Expected: Tests pass (mock data fallback preserves existing behavior)

- [ ] **Step 6: Commit**

```bash
git add examples/demo-app/android/src/main/java/io/opentelemetry/android/demo/data/AppointmentRepository.kt
git commit -m "feat(demo-app): connect AppointmentRepository to backend with mock fallback"
```

---

### Task 13: End-to-end smoke test

- [ ] **Step 1: Start the backend**

Run: `cd examples/demo-backend && npm run dev`

- [ ] **Step 2: Start an emulator**

Run: `nohup emulator -avd Pixel_7 -no-snapshot-save > /tmp/emu.log 2>&1 &`
Wait for boot.

- [ ] **Step 3: Install and launch demo app**

Run: `cd examples/demo-app && ./gradlew installDebug`
Run: `adb shell am start -n io.opentelemetry.android.demo/.SchedulingActivity`

- [ ] **Step 4: Verify distributed traces in Dash0**

- Navigate through the app: view doctors, select a slot, book an appointment
- Open Dash0, filter to dataset `otel-mobile`
- Verify traces show the full waterfall: `ui.tap` → `HTTP POST` → `express.handler` → `sqlite.query`
- Verify the mobile and backend spans share the same trace ID

- [ ] **Step 5: Test simulation**

Run: `curl -X POST localhost:3001/api/admin/simulate -d '{"error": true}' -H 'Content-Type: application/json'`
- Trigger an action in the mobile app
- Verify 503 error trace appears in Dash0
Run: `curl -X DELETE localhost:3001/api/admin/simulate`

- [ ] **Step 6: Final commit with any fixes**

```bash
git add -A
git commit -m "feat: complete demo backend with end-to-end trace verification"
```

---

## Deferred: Kubernetes Manifests

K8s deployment manifests (`deployment.yaml`, `service.yaml`, `configmap.yaml`, `secret.yaml`) are specified in the design doc but deferred to a follow-up task. They will live in the sister repo `mobile-otel-control-plane/k8s/demo-backend/` and use the same Docker image built from this plan's Dockerfile.
