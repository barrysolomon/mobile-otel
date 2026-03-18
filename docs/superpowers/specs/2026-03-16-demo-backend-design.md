# Demo Backend Design

**Date:** 2026-03-16
**Status:** Approved
**Location:** `examples/demo-backend/`

## Purpose

A Node.js backend service for the appointment scheduling demo app. Provides realistic API endpoints that the mobile app calls, producing distributed traces (Kotlin mobile → Node.js backend → SQLite) visible end-to-end in Dash0. Demonstrates OTel's polyglot, vendor-neutral story.

## Architecture

```
┌─────────────┐       OTLP/gRPC        ┌────────────────┐
│  Demo App   │ ─────────────────────── │  Dash0 / OTEL  │
│  (Kotlin)   │                         │   Collector    │
│             │       HTTP/JSON         │                │
│             │ ──────────────────┐     └────────────────┘
└─────────────┘                  │              ▲
                                 ▼              │ OTLP/gRPC
                          ┌──────────────┐      │
                          │ Demo Backend │ ─────┘
                          │  (Node.js)   │
                          │  Express     │
                          │  SQLite      │
                          │  OTel JS SDK │
                          └──────────────┘
```

**Context propagation:** The mobile app's `OTelNetworkInterceptor` (OkHttp interceptor) injects W3C `traceparent` headers. The backend's OTel Node.js SDK auto-extracts them, so backend spans are children of the mobile HTTP span.

## Mobile App Integration

**Base URL switch:** The demo app currently uses `jsonplaceholder.typicode.com` and `httpbin.org` as mock backends. As part of this work, the demo app's `AppointmentRepository` and `SchedulingApiClient` will be updated to point to `http://10.0.2.2:3001` (Android emulator loopback to host machine). A fallback to in-memory mock data will be retained when the backend is unreachable — this itself is a useful demo scenario (showing mobile resilience and error traces).

**Data model reconciliation:** The mobile app's current model (`provider` string, `type` enum, `notes`, `dateMs` long) will be adapted to match the backend schema. The backend is the source of truth. The mobile app will map: `doctor_id` ↔ `provider`, `reason` ↔ `notes`, `slot` (date+time) ↔ `dateMs`. The `DELETE` HTTP method will be added to `SchedulingApiClient` to support appointment cancellation.

**Error response format:** All error responses use a consistent JSON shape:

```json
{"error": "Human-readable message", "code": "SLOT_UNAVAILABLE"}
```

HTTP status codes: 400 (validation), 404 (not found), 409 (conflict, e.g. slot already booked), 503 (simulated error). The mobile app will parse this shape for error display.

## Tech Stack

| Component | Choice | Why |
|-----------|--------|-----|
| Runtime | Node.js + TypeScript | Shows polyglot OTel (Kotlin + JS in one trace) |
| Framework | Express | Simple, mature, OTel auto-instrumentation support |
| Database | SQLite via `better-sqlite3` | Zero-config, produces DB spans in traces |
| OTel | `@opentelemetry/sdk-node` + auto-instrumentations | Zero-code instrumentation for Express, HTTP, SQLite |
| Dev | `tsx` | TypeScript execution with auto-reload |
| Process manager | `nodemon` | Auto-restarts on crash (for `crash` simulation) |

## Project Structure

```
examples/demo-backend/
├── package.json
├── tsconfig.json
├── .env.example
├── Dockerfile
├── docker-compose.yml
├── .dockerignore
├── src/
│   ├── index.ts              # Express app, middleware, server start
│   ├── tracing.ts            # OTel SDK setup (must import before everything)
│   ├── routes/
│   │   ├── appointments.ts   # CRUD for appointments
│   │   ├── doctors.ts        # List doctors
│   │   ├── slots.ts          # Available time slots
│   │   ├── health.ts         # Health check
│   │   └── admin.ts          # Simulation controls
│   ├── db/
│   │   ├── schema.ts         # SQLite table definitions
│   │   └── seed.ts           # Seed data (doctors, slots)
│   └── middleware/
│       ├── simulate.ts       # Error/latency simulation
│       └── cors.ts           # CORS for mobile + future control plane UI
```

## Data Model

```sql
doctors (
  id          TEXT PRIMARY KEY,
  name        TEXT,
  specialty   TEXT,
  location    TEXT
)

slots (
  id          TEXT PRIMARY KEY,
  doctor_id   TEXT REFERENCES doctors(id),
  date        TEXT,       -- "2026-03-20"
  time        TEXT,       -- "09:00"
  available   INTEGER     -- 1/0
)

appointments (
  id          TEXT PRIMARY KEY,
  doctor_id   TEXT REFERENCES doctors(id),
  slot_id     TEXT REFERENCES slots(id),
  patient     TEXT,       -- name from the mobile app form
  reason      TEXT,
  status      TEXT,       -- "confirmed", "cancelled"
  created_at  TEXT
)
```

**SQLite configuration:** `PRAGMA foreign_keys = ON` must be set on connection open so foreign key constraints are enforced.

**Transaction safety:** `POST /api/appointments` wraps the INSERT + UPDATE in a `better-sqlite3` transaction (`db.transaction(...)`) to ensure atomicity. This produces a single parent DB span with two child query spans in the trace waterfall.

**Input validation:**

- `POST /api/appointments`: 400 if `doctor_id`, `slot_id`, `patient`, or `reason` missing. 404 if doctor or slot doesn't exist. 409 if slot already booked.
- `GET/DELETE /api/appointments/:id`: 404 if appointment doesn't exist.
- `GET /api/slots`: 400 if `doctor_id` query param missing.

**Seed data:** 4 doctors across 2 specialties (General Practice, Dermatology), each with ~10 slots spread over the next 5 days.

## API Endpoints

### Business Endpoints

| Method | Path | Request | Response | DB Operation |
|--------|------|---------|----------|-------------|
| GET | `/api/doctors` | — | `Doctor[]` | SELECT all |
| GET | `/api/slots?doctor_id=X&date=Y` | query params | `Slot[]` | SELECT with filters |
| POST | `/api/appointments` | `{doctor_id, slot_id, patient, reason}` | `Appointment` | INSERT + UPDATE slot.available=0 |
| GET | `/api/appointments` | — | `Appointment[]` | SELECT all |
| GET | `/api/appointments/:id` | — | `Appointment` | SELECT by id |
| DELETE | `/api/appointments/:id` | — | `{status: "cancelled"}` | UPDATE status + slot.available=1 |
| GET | `/health` | — | `{status: "ok"}` | — |

### Admin Endpoints (Simulation Controls)

| Method | Path | Request | Response |
|--------|------|---------|----------|
| GET | `/api/admin/simulate` | — | Current simulation state |
| POST | `/api/admin/simulate` | `{error: true, latency: 2000, crash: false}` | Updated state |
| DELETE | `/api/admin/simulate` | — | Resets all simulation to off |

## Simulation System

**Server-side state** (in-memory, resets on restart):

| Field | Type | Default | Effect |
|-------|------|---------|--------|
| `error` | boolean | false | All business endpoints return 503 |
| `latency` | number (ms) | 0 | Artificial delay before response |
| `crash` | boolean | false | Process exits mid-request (restarted by `nodemon`) |

**How it works:** The simulation middleware checks server-side state on every request to business endpoints. Admin endpoints are never affected.

**Per-request override:** Headers `X-Simulate-Error`, `X-Simulate-Latency`, `X-Simulate-Crash` still work for targeted testing, but the admin toggle is the primary demo mechanism.

**Demo flow:**
1. App is running, traces are clean in Dash0
2. `curl -X POST localhost:3001/api/admin/simulate -d '{"error": true}'`
3. Next mobile app actions hit 503s — error traces appear in Dash0
4. `curl -X DELETE localhost:3001/api/admin/simulate` — back to normal

## OTel Configuration

**Service identity:**
- Service name: `appointment-backend`
- Resource attributes: `service.version`, `deployment.environment=demo`

**Auto-instrumentations:** Express routes, HTTP client/server, better-sqlite3. Zero manual span creation needed — all traces come from auto-instrumentation.

**Export:** OTLP/gRPC to same Dash0 endpoint as the mobile app. Config via `.env`:

```
OTEL_EXPORTER_OTLP_ENDPOINT=https://your-collector:4317
OTEL_EXPORTER_OTLP_HEADERS=Authorization=Bearer <token>,Dash0-Dataset=otel-mobile
PORT=3001
```

Note: `Dash0-Dataset` is passed as an OTLP header (same pattern as the mobile app's `otel-config.json`). There is no separate `DASH0_DATASET` env var — it's part of `OTEL_EXPORTER_OTLP_HEADERS`.

## Running

### Local (no Docker)

```bash
cd examples/demo-backend
npm install
cp .env.example .env   # fill in Dash0 creds
npm run dev             # starts on :3001 with auto-reload + auto-restart on crash
```

### Docker

```bash
cd examples/demo-backend
cp .env.example .env   # fill in Dash0 creds
docker compose up       # builds and starts on :3001
```

**Files added to `examples/demo-backend/`:**

```
Dockerfile              # Multi-stage: npm ci → node runtime, ~50MB image
docker-compose.yml      # Single service, .env passthrough, port 3001
.dockerignore           # node_modules, .env, *.db
```

**Dockerfile approach:** Multi-stage build. Stage 1 (`node:20-alpine`) runs `npm ci --production`. Stage 2 copies built `node_modules` and `src/` into a clean `node:20-alpine` runtime. The SQLite database file is created at startup inside the container (ephemeral by default, volume-mountable for persistence).

**docker-compose.yml:**

```yaml
services:
  demo-backend:
    build: .
    ports:
      - "3001:3001"
    env_file: .env
    restart: unless-stopped   # handles crash simulation
```

`restart: unless-stopped` replaces `nodemon` for crash simulation in Docker — when the process exits, Docker restarts the container automatically.

### Kubernetes

K8s manifests live in the sister repo alongside the control plane: `mobile-otel-control-plane/k8s/demo-backend/`.

```
k8s/demo-backend/
├── deployment.yaml     # 1 replica, resource limits, readiness probe on /health
├── service.yaml        # ClusterIP on port 3001
├── configmap.yaml      # Non-secret env vars (PORT, service name)
└── secret.yaml         # OTLP endpoint + auth token (template, not committed)
```

**Key K8s details:**

- **Image:** Published to a container registry (e.g., `ghcr.io/barrysolomon/demo-backend:latest`). Built from the same Dockerfile.
- **Readiness probe:** `GET /health` every 10s. Ensures traffic only routes to healthy pods.
- **Crash simulation:** `restart: Always` policy handles process exits. The pod restarts automatically and the restart count is visible in `kubectl get pods` — another observable signal.
- **Mobile app connectivity:** The emulator connects via the cluster's external IP or ingress. The mobile app's base URL switches from `10.0.2.2:3001` (local Docker) to the cluster endpoint.
- **Namespace:** Deployed in the same namespace as the control plane gateway and UI, sharing the OTEL Collector sidecar/daemonset configuration.

## Future: Control Plane UI Hosting

Designed for but not yet implementing static file serving of the control plane UI React build. When ready, add a `GET /` route that serves the built React app from a `public/` directory. No architectural changes needed.

## Trace Waterfall (What You See in Dash0)

A single "Book Appointment" action produces this trace:

```
ui.tap "Book Now"                          [mobile]
  └─ HTTP POST /api/appointments           [mobile → backend]
       └─ express.handler POST /api/app... [backend]
            ├─ sqlite.query INSERT INTO... [backend]
            └─ sqlite.query UPDATE slots.. [backend]
```

All spans share one trace ID. The polyglot story: Kotlin started it, Node.js continued it, Dash0 shows it all.
