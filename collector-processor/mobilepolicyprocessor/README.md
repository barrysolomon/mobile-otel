# Mobile Policy Processor

[![Apache License][license-image]][license-url]

OpenTelemetry Collector processor for evaluating mobile-specific policies and annotating telemetry data based on runtime conditions.

## Description

The Mobile Policy Processor evaluates incoming log records against configurable policies and executes actions when conditions match. This enables:

- Conditional enrichment of mobile telemetry
- Runtime sampling decisions
- Context-aware annotations
- Policy-based data routing

## Configuration

```yaml
processors:
  mobilepolicy:
    policies:
      - id: ui-freeze-handler
        enabled: true
        match:
          logical_operator: and
          attributes:
            event.name:
              equals: "ui.freeze"
            duration_ms:
              gt: 2000.0
        actions:
          - type: annotate
            parameters:
              trigger_id: "ui-freeze-handler"
              reason: "UI freeze detected over 2 seconds"
              severity: "warning"
          - type: sample
            parameters:
              rate: 1.0
              duration_minutes: 10

      - id: crash-recovery
        enabled: true
        match:
          logical_operator: and
          attributes:
            event.name:
              equals: "crash_marker"
        actions:
          - type: annotate
            parameters:
              trigger_id: "crash-recovery"
              reason: "Application crash detected"
              severity: "error"
```

## Policy Configuration

### Match Conditions

#### Attribute Matchers

| Matcher | Type | Description | Example |
|---------|------|-------------|---------|
| `equals` | string | Exact string match | `equals: "ui.freeze"` |
| `not_equals` | string | String not equal | `not_equals: "debug"` |
| `gt` | number | Greater than | `gt: 2000.0` |
| `gte` | number | Greater than or equal | `gte: 500.0` |
| `lt` | number | Less than | `lt: 100.0` |
| `lte` | number | Less than or equal | `lte: 999.0` |
| `contains` | string | Substring match | `contains: "/api/"` |
| `regex` | string | Regular expression | `regex: "^error.*"` |

#### Logical Operators

- `and`: All conditions must match (default)
- `or`: Any condition must match

### Actions

#### Annotate Action

Adds attributes to matching log records:

```yaml
actions:
  - type: annotate
    parameters:
      trigger_id: "my-trigger"
      reason: "Description of why this matched"
      severity: "warning"
      custom_field: "custom_value"
```

All parameters under `annotate` are added as attributes with `policy.` prefix:
- `policy.trigger_id`
- `policy.reason`
- `policy.severity`
- `policy.custom_field`

#### Sample Action

Adjusts sampling rate for matching logs:

```yaml
actions:
  - type: sample
    parameters:
      rate: 1.0              # 0.0 = 0%, 1.0 = 100%
      duration_minutes: 10   # How long to apply rate
```

Adds attributes:
- `sampling.rate`: The sampling rate
- `sampling.duration_minutes`: Duration of sampling adjustment

## Examples

### Example 1: Performance Monitoring

```yaml
processors:
  mobilepolicy:
    policies:
      - id: slow-operation
        enabled: true
        match:
          logical_operator: and
          attributes:
            operation.duration_ms:
              gt: 5000.0
        actions:
          - type: annotate
            parameters:
              trigger_id: "slow-operation"
              performance_issue: "true"
```

### Example 2: Error Tracking

```yaml
processors:
  mobilepolicy:
    policies:
      - id: http-errors
        enabled: true
        match:
          logical_operator: and
          attributes:
            http.status_code:
              gte: 400.0
            http.route:
              contains: "/api/"
        actions:
          - type: annotate
            parameters:
              trigger_id: "http-error"
              error_category: "api_failure"
          - type: sample
            parameters:
              rate: 1.0
              duration_minutes: 15
```

### Example 3: Multiple Conditions (OR)

```yaml
processors:
  mobilepolicy:
    policies:
      - id: critical-events
        enabled: true
        match:
          logical_operator: or
          attributes:
            event.name:
              equals: "crash"
            severity:
              equals: "FATAL"
            error.type:
              contains: "OutOfMemory"
        actions:
          - type: annotate
            parameters:
              trigger_id: "critical-event"
              priority: "high"
```

## Full Collector Configuration Example

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

processors:
  memory_limiter:
    limit_mib: 512
    check_interval: 1s

  batch:
    timeout: 10s
    send_batch_size: 1000

  mobilepolicy:
    policies:
      - id: ui-freeze
        enabled: true
        match:
          logical_operator: and
          attributes:
            event.name:
              equals: "ui.freeze"
            duration_ms:
              gt: 2000.0
        actions:
          - type: annotate
            parameters:
              trigger_id: "ui-freeze"

exporters:
  otlp:
    endpoint: backend:4317

  logging:
    verbosity: detailed

service:
  pipelines:
    logs:
      receivers: [otlp]
      processors: [memory_limiter, mobilepolicy, batch]
      exporters: [otlp, logging]
```

## Use Cases

### Mobile App Observability

- Identify and annotate performance issues (UI freezes, slow operations)
- Enhance crash reports with context
- Dynamically adjust sampling for error scenarios
- Route different event types to different backends

### Conditional Sampling

- High sampling for errors (100%)
- Low sampling for debug logs (1%)
- Dynamic sampling based on runtime conditions

### Data Enrichment

- Add policy metadata to logs
- Tag events with trigger identifiers
- Annotate severity and categories

## Performance

- Negligible overhead: < 1ms per log record
- Efficient attribute matching
- No external dependencies
- Scales with collector throughput

## Building

```bash
# Build the processor
go build ./...

# Run tests
go test ./...

# Build custom collector with this processor
builder --config=builder-config.yaml
```

## Testing

```bash
# Unit tests
go test -v ./...

# Integration tests
go test -v -tags=integration ./...

# Benchmarks
go test -bench=. ./...
```

## Contributing

Contributions welcome! See [CONTRIBUTING.md](../../CONTRIBUTING.md) for guidelines.

## Documentation

- [Configuration Reference](docs/configuration.md)
- [Policy Syntax Guide](docs/policy-syntax.md)
- [Examples](docs/examples.md)
- [Troubleshooting](docs/troubleshooting.md)

## License

Apache License 2.0 - See [LICENSE](../../LICENSE) for details.

## Support

- [GitHub Issues](https://github.com/open-telemetry/opentelemetry-collector-contrib/issues)
- [Slack Channel](https://cloud-native.slack.com/archives/C01N3AT62SJ) (#otel-collector)
- [Documentation](https://opentelemetry.io/docs/collector/)

[license-image]: https://img.shields.io/badge/license-Apache_2.0-green.svg?style=flat
[license-url]: https://github.com/open-telemetry/opentelemetry-collector-contrib/blob/main/LICENSE
