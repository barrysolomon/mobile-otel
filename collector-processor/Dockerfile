# Multi-stage Dockerfile for otelcol-mobile
#
# Builds a custom OpenTelemetry Collector binary with the mobilepolicyprocessor
# embedded using the OpenTelemetry Collector Builder (ocb).
#
# Build context must be the collector-processor/ directory:
#   docker build -t otelcol-mobile:latest .
#
# Or from the repo root:
#   docker build -t otelcol-mobile:latest collector-processor/

# ─────────────────────────────────────────────────────────────────────────────
# Stage 1: Build — download ocb, build the custom collector binary
# ─────────────────────────────────────────────────────────────────────────────
FROM golang:1.21-alpine AS builder

# Install build tools
RUN apk add --no-cache curl git ca-certificates

# Download the OpenTelemetry Collector Builder (ocb) matching our component version
ARG OCB_VERSION=0.91.0
ARG TARGETOS=linux
ARG TARGETARCH=amd64
RUN curl -fsSL \
    "https://github.com/open-telemetry/opentelemetry-collector/releases/download/cmd/builder/v${OCB_VERSION}/ocb_${OCB_VERSION}_${TARGETOS}_${TARGETARCH}" \
    -o /usr/local/bin/ocb && \
    chmod +x /usr/local/bin/ocb

WORKDIR /build

# Copy the processor source and the builder config
COPY mobilepolicyprocessor/ ./mobilepolicyprocessor/
COPY builder-config.yaml ./

# Run ocb to generate the collector main module and then compile the binary.
# --output-path is where the final otelcol-mobile binary lands.
RUN ocb \
    --config builder-config.yaml \
    --output-path /out \
    --go $(which go)

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2: Runtime — minimal image with just the binary
# ─────────────────────────────────────────────────────────────────────────────
FROM alpine:3.19 AS runtime

RUN apk add --no-cache ca-certificates tzdata

# Non-root user for security
RUN addgroup -S otelcol && adduser -S otelcol -G otelcol

WORKDIR /app

COPY --from=builder /out/otelcol-mobile /app/otelcol-mobile

# Default config location — override by mounting /app/config.yaml
COPY default-config.yaml /app/config.yaml

RUN chown -R otelcol:otelcol /app
USER otelcol

# OTLP gRPC | OTLP HTTP | Collector metrics | Health check
EXPOSE 4317 4318 8888 13133

ENTRYPOINT ["/app/otelcol-mobile"]
CMD ["--config=/app/config.yaml"]
