# otelcol-mobile — Custom OpenTelemetry Collector

A custom OpenTelemetry Collector binary (`otelcol-mobile`) that bundles the
`mobilepolicyprocessor` alongside the standard OTLP receiver, batch processor,
memory limiter, and OTLP/debug exporters.

The `mobilepolicyprocessor` evaluates each incoming log record against a set of
policies and annotates matching records with `policy.matched=true` and `policy.id=<id>`.
This enables server-side classification of mobile events (freeze, crash, low battery, etc.)
without shipping custom code to the app.

---

## Quick Start

### Prerequisites

- Docker 20+
- `curl` (for the integration test)

### 1. Build the image

```bash
cd collector-processor/
docker build -t otelcol-mobile:latest .
```

This uses a multi-stage build:
1. **Stage 1 (builder)** — Downloads the OTel Collector Builder (`ocb` v0.91.0), uses it to compile a custom collector binary with `mobilepolicyprocessor` embedded.
2. **Stage 2 (runtime)** — Copies the binary into a minimal Alpine image, adds a non-root user (`otelcol`), and bakes in the default config.

Expected output: `otelcol-mobile:latest` ~50MB.

### 2. Run with the default config

```bash
docker run -p 4317:4317 -p 4318:4318 -p 13133:13133 otelcol-mobile:latest
```

The default config (`default-config.yaml`) includes two example policies:
- **ui-freeze-alert** — matches `event.name=ui.freeze` AND `duration_ms>2000`
- **crash-recovery** — matches `event.name=app.start` AND `recovery_type=crash`

Both annotate matched records and forward everything to the debug exporter (stdout).

### 3. Point your Android SDK at the collector

In your app's `MobileConfig`:

```kotlin
val config = MobileConfig(
    serviceName = "my-app",
    serviceVersion = "1.0.0",
    collectorEndpoint = "http://<collector-ip>:4317"  // gRPC
    // or: collectorEndpoint = "http://<collector-ip>:4318" for HTTP
)
OTelMobile.start(application, config)
```

Or from the demo app's **Profile → OTel SDK Configuration** screen — set the endpoint field.

---

## Production Configuration

Mount your own config file to override the default:

```bash
docker run \
  -p 4317:4317 -p 4318:4318 \
  -v $(pwd)/my-collector-config.yaml:/app/config.yaml:ro \
  otelcol-mobile:latest
```

### Full policy config reference

```yaml
processors:
  mobilepolicy:
    policies:
      - id: "my-policy-id"          # required, unique
        enabled: true               # set false to disable without removing
        match:
          logical_operator: and     # "and" | "or"
          attributes:
            event.name:
              equals: "ui.freeze"   # exact string match
            duration_ms:
              gt: 2000.0            # greater than (numeric)
              # also: lt, gte, lte
            error.type:
              contains: "NPE"       # substring match
            stack_trace:
              regex: "BookFragment" # regex match
        actions:
          - type: annotate          # adds policy.matched=true, policy.id=<id>
```

Supported match operators:

| Operator | Type | Description |
|----------|------|-------------|
| `equals` | string | Exact match |
| `contains` | string | Substring match |
| `regex` | string | Regular expression |
| `gt` | number | Greater than |
| `lt` | number | Less than |
| `gte` | number | Greater than or equal |
| `lte` | number | Less than or equal |

Logical operators: `and` (all conditions must match), `or` (any condition matches).

### Forwarding to Dash0 (or any OTLP backend)

```yaml
exporters:
  otlp:
    endpoint: "https://ingress.eu-west-1.aws.dash0.com:4317"
    headers:
      Authorization: "Bearer <your-dash0-token>"

service:
  pipelines:
    logs:
      receivers: [otlp]
      processors: [memory_limiter, batch, mobilepolicy]
      exporters: [otlp]
```

---

## Running with docker-compose

See the [mobile-otel-control-plane](https://github.com/barrysolomon/mobile-otel-control-plane) repository
for docker-compose and Kubernetes deployment manifests.

---

## Tests

### Unit tests (processor logic)

These run entirely in Go — no Docker required:

```bash
cd collector-processor/mobilepolicyprocessor
go mod tidy
go test -v -race ./...
```

Expected: ~32 tests across 3 files, all passing.

What is covered:
- **`factory_test.go`** — Factory creation, type string, default config, start/shutdown lifecycle, `MutatesData=true` capability
- **`processor_test.go`** — All match operators (`equals`, `gt`, `lt`, `gte`, `lte`, `contains`, `regex`), `and`/`or` logic, multi-policy evaluation, disabled policy skip, attribute annotation
- **`config_test.go`** — Config validation: missing ID, invalid logical operator, empty attributes, missing action type

### Integration tests (image smoke test)

Tests the full container: build → start → send OTLP records → verify annotations.

Requires Docker:

```bash
cd collector-processor/
./integration_test/integration_test.sh
```

This:
1. Builds `otelcol-mobile:latest` from the local source
2. Starts the container with `integration_test/test-config.yaml`
3. Sends three OTLP HTTP log records via `curl`
4. Checks the debug exporter output for `policy.matched`
5. Verifies the health check endpoint is reachable
6. Prints PASS/FAIL per test case

Options:
```bash
./integration_test/integration_test.sh --no-build         # skip docker build
./integration_test/integration_test.sh --image my-tag     # use a different image tag
./integration_test/integration_test.sh --verbose          # always print container logs
```

Test cases:

| # | Input | Expected |
|---|-------|----------|
| 1 | `event.name=ui.freeze, duration_ms=3500` | `policy.matched` in debug output |
| 2 | `event.name=ui.freeze, duration_ms=500` | Record logged, no policy annotation |
| 3 | `battery_level=15` | `low-battery-policy` triggers |
| 4 | Health check | HTTP 200 from `:13133` |
| 5 | Container user | Runs as non-root `otelcol` |

---

## How the Build Works (for contributors)

The `builder-config.yaml` is an [OCB](https://github.com/open-telemetry/opentelemetry-collector/tree/main/cmd/builder)
spec that lists exactly which receiver/processor/exporter components to include.
The Dockerfile downloads the `ocb` binary, runs it against this spec to generate
a self-contained Go module and `main.go`, then compiles the binary.

This means:
- No forking the upstream collector repo
- The processor can be updated independently (just bump the `path:` dependency)
- To add a new component (e.g., Prometheus exporter), add one line to `builder-config.yaml`

---

## Versioning

| Component | Version |
|-----------|---------|
| OTel Collector | 0.91.0 |
| `mobilepolicyprocessor` | 0.0.0 (local) |
| Go | 1.21 |
| Alpine runtime | 3.19 |

To upgrade the collector version: update `otelcol_version` in `builder-config.yaml`
and the `ARG OCB_VERSION` in `Dockerfile`, then rebuild.
