# Operations Guide

> **Note:** Gateway, Control Plane UI, and Kubernetes deployment content has moved to the
> [mobile-otel-control-plane](https://github.com/barrysolomon/mobile-otel-control-plane) repository.
> This document covers OTEL Collector operations and general infrastructure guidance.

Guide for deploying and operating the OTEL Collector for mobile observability.

## Table of Contents

1. [Production Architecture](#production-architecture)
2. [OTEL Collector Deployment](#otel-collector-deployment)
3. [Monitoring & Alerting](#monitoring--alerting)
4. [Scaling](#scaling)
5. [Backup & Recovery](#backup--recovery)
6. [Operational Runbooks](#operational-runbooks)
7. [Incident Response](#incident-response)

## Production Architecture

### High-Level Architecture

```
Mobile Devices ──── OTLP/gRPC ────► OTEL Collector ──┬──► Dash0
                                     (otelcol-mobile)  ├──► Loki
                                                       ├──► Prometheus
                                                       └──► Jaeger
```

The Android SDK exports directly to an OTEL Collector via OTLP/gRPC on port 4317.
The collector can be the custom `otelcol-mobile` build (with `mobilepolicyprocessor`)
or any standard OTEL Collector distribution.

### Deployment Options

| Option | Best For | Setup |
| ------ | -------- | ----- |
| Dash0 cloud | Production | Point SDK at `https://ingress.dash0.com:4317` |
| Docker (`otelcol-mobile`) | Development / self-hosted | `docker run -p 4317:4317 otelcol-mobile:latest` |
| Kubernetes | Production self-hosted | Deploy collector manifest to cluster |

## OTEL Collector Deployment

### Docker (Simplest)

```bash
cd collector-processor/
docker build -t otelcol-mobile:latest .
docker run -p 4317:4317 -p 4318:4318 -p 13133:13133 otelcol-mobile:latest
```

### Custom Configuration

Mount your own config to override the default:

```bash
docker run \
  -p 4317:4317 -p 4318:4318 \
  -v $(pwd)/my-collector-config.yaml:/app/config.yaml:ro \
  otelcol-mobile:latest
```

### Kubernetes

```yaml
# collector-prod.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: otel-collector
  namespace: mobile-observability
spec:
  replicas: 2
  selector:
    matchLabels:
      app: otel-collector
  template:
    metadata:
      labels:
        app: otel-collector
    spec:
      containers:
      - name: collector
        image: otelcol-mobile:latest
        resources:
          requests:
            cpu: 500m
            memory: 512Mi
          limits:
            cpu: 1000m
            memory: 1Gi
        ports:
        - containerPort: 4317  # OTLP gRPC
        - containerPort: 4318  # OTLP HTTP
        - containerPort: 13133 # Health check
        - containerPort: 8888  # Metrics
```

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

## Monitoring & Alerting

### Collector Health Check

```bash
curl http://localhost:13133/
# Returns 200 if healthy
```

### Key Metrics to Monitor

The OTEL Collector exposes Prometheus metrics on port 8888:

- `otelcol_receiver_accepted_log_records` -- logs received
- `otelcol_receiver_refused_log_records` -- logs refused
- `otelcol_exporter_sent_log_records` -- logs exported
- `otelcol_exporter_send_failed_log_records` -- export failures
- `otelcol_processor_batch_batch_send_size` -- batch sizes

### Alert Rules (Example)

```yaml
groups:
- name: collector
  rules:
  - alert: CollectorHighErrorRate
    expr: |
      rate(otelcol_exporter_send_failed_log_records[5m]) > 0
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "Collector export failures detected"

  - alert: CollectorDown
    expr: |
      up{job="otel-collector"} == 0
    for: 2m
    labels:
      severity: critical
    annotations:
      summary: "OTEL Collector is down"
```

## Scaling

### Horizontal Scaling

For high event volumes, run multiple collector replicas behind a load balancer:

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: collector-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: otel-collector
  minReplicas: 2
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
```

### Tuning

```yaml
# Increase batch size for higher throughput
processors:
  batch:
    timeout: 10s
    send_batch_size: 10000

# Add memory limiter to prevent OOM
processors:
  memory_limiter:
    limit_mib: 2048
    spike_limit_mib: 512
    check_interval: 1s
```

## Backup & Recovery

### Configuration Backup

```bash
# Back up collector config
kubectl get configmap -n mobile-observability otel-collector-config -o yaml > collector-config-backup.yaml

# Back up all secrets and configmaps
kubectl get configmap,secret -n mobile-observability -o yaml > config-backup.yaml
```

### SDK-Side Recovery

The Android SDK has built-in resilience:

- **Dual-tier buffer**: Events are stored in RAM (5000 events) and disk (50MB, 24h TTL)
- **Retry with backoff**: `RetryableExporter` retries failed exports with exponential backoff
- **Crash recovery**: `RecoveryTracker` detects crash/ANR/OOM recovery and flushes pre-crash buffer
- **Offline operation**: Events buffer locally when the collector is unreachable

## Operational Runbooks

### Runbook: Collector Not Receiving Events

**Investigation:**

```bash
# Check collector is running and healthy
curl http://localhost:13133/

# Check collector logs
docker logs <collector-container> 2>&1 | tail -100

# Verify OTLP port is listening
nc -zv localhost 4317
```

**Mitigation:**

- Restart collector
- Check collector config for valid receivers/exporters
- Verify network connectivity from SDK to collector

### Runbook: High Memory Usage

**Investigation:**

```bash
# Check collector metrics
curl http://localhost:8888/metrics | grep otelcol_process_memory

# Check for queue buildup
curl http://localhost:8888/metrics | grep queue
```

**Mitigation:**

- Add/tune `memory_limiter` processor
- Reduce batch size
- Scale horizontally

### Runbook: Export Failures

**Investigation:**

```bash
# Check exporter errors
docker logs <collector-container> 2>&1 | grep -i "error\|fail"

# Check backend connectivity
nc -zv <backend-host> <backend-port>
```

**Mitigation:**

- Verify backend endpoint and credentials
- Check TLS certificate validity
- Increase export timeout

## Incident Response

### Severity Levels

| Level | Response Time | Description |
| ----- | ------------- | ----------- |
| P0 - Critical | 15 min | Complete telemetry pipeline outage |
| P1 - High | 1 hour | Partial outage, high export failure rate |
| P2 - Medium | 4 hours | Degraded performance, increased latency |
| P3 - Low | Next business day | Minor issues, non-blocking |

### Incident Response Process

1. **Detection**: Alert fires or manual report
2. **Acknowledgment**: On-call engineer acknowledges
3. **Assessment**: Determine severity and impact
4. **Mitigation**: Apply temporary fixes (restart, scale, rollback)
5. **Resolution**: Permanent fix deployed
6. **Post-Mortem**: Document lessons learned

## Related Documentation

- [Collector Processor](../collector-processor/README.md) - Custom collector build
- [Export Modes](EXPORT_MODES.md) - SDK export mode details
- [Troubleshooting Guide](TROUBLESHOOTING_GUIDE.md) - Common issues
- [Control Plane](https://github.com/barrysolomon/mobile-otel-control-plane) - Gateway, UI, and k8s deployment (sister repo)
