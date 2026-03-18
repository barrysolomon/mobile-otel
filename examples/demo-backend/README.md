# Demo Backend

Express.js/TypeScript backend for the OpenTelemetry Mobile Demo App (**Schedulr**). Provides a real appointment booking API with full OTel instrumentation for end-to-end distributed tracing.

## Quick Start

```bash
cp .env.example .env        # Configure OTel collector endpoint
npm install
npm run dev                  # Start with hot-reload on http://localhost:3001
```

The Android demo app connects to `http://10.0.2.2:3001` (emulator bridge to host localhost).

## API Endpoints

### Business Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/doctors` | List all doctors |
| GET | `/api/slots?doctor_id=X&date=Y` | Available slots (date is optional) |
| POST | `/api/appointments` | Book appointment (validates doctor, slot, marks slot unavailable) |
| GET | `/api/appointments` | List all appointments |
| GET | `/api/appointments/:id` | Get single appointment |
| DELETE | `/api/appointments/:id` | Cancel appointment (frees slot) |

### Admin / Simulation

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Health check |
| GET | `/api/admin/simulate` | Get simulation state |
| POST | `/api/admin/simulate` | Set simulation state (`{ error, latency, crash }`) |
| DELETE | `/api/admin/simulate` | Reset simulation |

### Error Responses

All errors return JSON with `error` and `code` fields:

```json
{ "error": "Slot is no longer available", "code": "SLOT_UNAVAILABLE" }
```

Codes: `MISSING_FIELDS`, `MISSING_DOCTOR_ID`, `DOCTOR_NOT_FOUND`, `SLOT_NOT_FOUND`, `SLOT_UNAVAILABLE`, `NOT_FOUND`, `SIMULATED_ERROR`

## OpenTelemetry

The backend is fully instrumented with OpenTelemetry:

- **Auto-instrumentation**: Express, HTTP, SQLite via `@opentelemetry/auto-instrumentations-node`
- **W3C trace propagation**: `traceparent` headers from the mobile app create child spans in the backend
- **OTLP export**: Traces sent to your collector via OTLP/proto (HTTP)

### Trace Flow

```
Mobile App (OTelNetworkInterceptor)
  → HTTP request with traceparent header
    → Demo Backend (auto-instrumentation creates child span)
      → SQLite queries (child spans)
    → Response
  → Both export to Dash0 via OTLP
```

## Simulation Middleware

Inject faults for testing the mobile app's error handling:

```bash
# Force 503 errors on all business endpoints
curl -X POST localhost:3001/api/admin/simulate -H 'Content-Type: application/json' -d '{"error": true}'

# Add 2s latency
curl -X POST localhost:3001/api/admin/simulate -H 'Content-Type: application/json' -d '{"latency": 2000}'

# Per-request override via headers
curl -H 'X-Simulate-Error: true' localhost:3001/api/appointments

# Reset
curl -X DELETE localhost:3001/api/admin/simulate
```

## Docker

```bash
docker-compose up --build    # Runs on port 3001
```

## Testing

```bash
npm test                     # 29 tests via vitest
npm run test:watch           # Watch mode
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `3001` | Server port |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | — | Collector endpoint (e.g. `https://ingress.us1.aws.dash0.com:4317`) |
| `OTEL_EXPORTER_OTLP_HEADERS` | — | Auth headers (e.g. `Authorization=Bearer xxx,Dash0-Dataset=otel-mobile`) |
| `OTEL_SERVICE_NAME` | `otel-mobile-backend` | Service identity in traces |

## Data Model

- **doctors**: 4 seeded doctors (Sarah Chen, Marcus Webb, Elena Rossi, James Park)
- **slots**: 200 time slots (5 days x 10 slots per doctor), marked unavailable after booking
- **appointments**: Created via POST, linked to doctor + slot via foreign keys
