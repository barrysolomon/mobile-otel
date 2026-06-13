import { NodeSDK } from "@opentelemetry/sdk-node";
import { getNodeAutoInstrumentations } from "@opentelemetry/auto-instrumentations-node";
import { OTLPTraceExporter } from "@opentelemetry/exporter-trace-otlp-proto";
import { Resource } from "@opentelemetry/resources";
import { ATTR_SERVICE_NAME, ATTR_SERVICE_VERSION } from "@opentelemetry/semantic-conventions";
import { ATTR_DEPLOYMENT_ENVIRONMENT_NAME } from "@opentelemetry/semantic-conventions/incubating";
import { W3CTraceContextPropagator } from "@opentelemetry/core";

// Dash0 ingress listens on :4317 (gRPC) and :4318 (HTTP/proto).
// Node.js OTLP/proto exporter uses HTTP, so rewrite to :4318.
const baseEndpoint = process.env.OTEL_EXPORTER_OTLP_ENDPOINT || "";
const httpEndpoint = baseEndpoint.replace(":4317", ":4318");

// Parse OTEL_EXPORTER_OTLP_HEADERS into a Record for the exporter
// Format: "Key1=Value1,Key2=Value2"
const rawHeaders = process.env.OTEL_EXPORTER_OTLP_HEADERS || "";
const headers: Record<string, string> = {};
if (rawHeaders) {
  for (const pair of rawHeaders.split(",")) {
    const eqIdx = pair.indexOf("=");
    if (eqIdx > 0) {
      headers[pair.slice(0, eqIdx).trim()] = pair.slice(eqIdx + 1).trim();
    }
  }
}

// Skip tracing entirely under vitest or when the endpoint is the
// .env.example placeholder. Without this, importing index.ts from a test
// boots the exporter against the literal host "your-collector": every test
// passes but the ENOTFOUND unhandled rejection fails the run (found by
// run-e2e.sh's backend-test step, 2026-06-12).
const isPlaceholder = httpEndpoint === "" || httpEndpoint.includes("your-collector");
const isTestRun = !!process.env.VITEST;
if (isPlaceholder || isTestRun) {
  if (!isTestRun) {
    console.warn("[tracing] OTEL_EXPORTER_OTLP_ENDPOINT not configured — backend tracing disabled");
  }
} else {
  startTracing();
}

function startTracing() {
const sdk = new NodeSDK({
  resource: new Resource({
    [ATTR_SERVICE_NAME]: process.env.OTEL_SERVICE_NAME || "otel-mobile-backend",
    [ATTR_SERVICE_VERSION]: "0.1.0",
    [ATTR_DEPLOYMENT_ENVIRONMENT_NAME]: "demo",
  }),
  traceExporter: new OTLPTraceExporter({
    url: httpEndpoint ? `${httpEndpoint}/v1/traces` : undefined,
    headers,
  }),
  textMapPropagator: new W3CTraceContextPropagator(),
  instrumentations: [getNodeAutoInstrumentations()],
});

sdk.start();
console.log("[tracing] OTel SDK started — exporting to", httpEndpoint || "default endpoint");
console.log("[tracing] Headers configured:", Object.keys(headers).join(", ") || "(none)");

process.on("SIGTERM", () => {
  sdk.shutdown().then(() => process.exit(0));
});
}
