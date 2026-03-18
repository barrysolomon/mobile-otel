# Documentation Index

## Getting Started

- [Quick Start](QUICK_START.md) — SDK integration in 5 minutes, or run the full demo end-to-end
- [Android SDK Guide](ANDROID_SDK_GUIDE.md) — Complete integration guide (auto-instrumentation, network, privacy, flush control)
- [Tutorial: Android Quickstart](guides/TUTORIAL_ANDROID_QUICKSTART.md) — Step-by-step tutorial with the Schedulr starter app
- [Configuration](CONFIGURATION.md) — MobileConfig reference, export modes, policy DSL, sub-configs

## SDK Reference

- [Auto-Instrumentation](AUTO_INSTRUMENTATION.md) — All auto-captured signals, trace hierarchy, privacy controls
- [Buffering & Tail Sampling](BUFFERING_AND_TAIL_SAMPLING.md) — Dual-tier ring buffer internals, flush mechanics, crash recovery
- [Export Modes](EXPORT_MODES.md) — CONDITIONAL, CONTINUOUS, HYBRID modes
- [Device Metrics](DEVICE_METRICS.md) — Health metric gauges (memory, battery, thermal, storage, predictions)
- [Geo/Device Policy Extension](GEO_DEVICE_POLICY_EXTENSION.md) — Country/region/device-class export policy DSL
- [Sampling](SAMPLING.md) — Dynamic sampling configuration
- [Log Tailing](LOG_TAILING.md) — Circular buffer for pattern detection and crash context
- [Bundled Config](BUNDLED_CONFIG.md) — Ship apps with pre-configured export policies
- [API Reference](API_REFERENCE.md) — Public API surface

## Architecture

- [Architecture Overview](ARCHITECTURE_OVERVIEW.md) — System components and data flow
- [System Overview](architecture/01-system-overview.md)
- [Ring Buffer Architecture](architecture/02-ring-buffer-architecture.md)
- [Flush Behavior](architecture/03-flush-behavior.md)
- [Low-Memory Handling](architecture/04-low-memory-handling.md)
- [Metrics Capture](architecture/05-metrics-capture.md)
- [Architecture Deep Dive](reference/ARCHITECTURE.md) — Comprehensive design reference

## Operations & Development

- [Developer Guide](DEVELOPER_GUIDE.md) — Extending the SDK and collector processor
- [Troubleshooting](TROUBLESHOOTING_GUIDE.md) — Common issues and solutions
- [Operations Guide](OPERATIONS_GUIDE.md) — Production deployment and monitoring
- [How It Builds](HOW_IT_BUILDS.md) — Build process documentation

## Testing

- [Testing Strategy](guides/TESTING_STRATEGY.md) — Testing pyramid, unit/integration/E2E approach
- [Testing Implementation](reference/TESTING_IMPLEMENTATION.md) — Test inventory and patterns

## Guides

- [Authentication](guides/AUTHENTICATION.md) — Authentication configuration
- [Deployment](guides/DEPLOYMENT_GUIDE.md) — Production deployment checklist
- [Offline Resilience](guides/OFFLINE_RESILIENCE.md) — Crash recovery and network loss handling

## OTel Enhancement Proposals

- [Mobile Buffering Pattern](OTEPs/OTEP-mobile-buffering-pattern.md)
- [Conditional Export for Mobile](OTEPs/OTEP-conditional-export-mobile.md)
- [Predictive Telemetry](OTEPs/OTEP-PREDICTIVE-TELEMETRY.md)

## Other Resources

- [Why Not a Fork](../WHY_NOT_A_FORK.md) — OTel alignment and composition-over-forking rationale
- [Why This SDK](WHY_THIS_SDK.md) — Motivation and differentiators
- [What Is an OTEP](WHAT_IS_OTEP.md) — OpenTelemetry Enhancement Proposals explained
- [Geo/Device E2E Verification](E2E_GEO_DEVICE_VERIFICATION.md) — End-to-end policy verification
- [User Guide](USER_GUIDE.md) — End-user feature overview
- [Dashboards](../dashboards/README.md) — Dash0 dashboard JSON definitions and import instructions
