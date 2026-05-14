# packages/

JavaScript / TypeScript packages published to npm (or planned to be).

## Contents

| Package | Purpose |
|---------|---------|
| [`react-native/`](react-native/) | `@dash0hq/mobile-otel-react-native` — RN bridge over the native Android + iOS SDKs |

## Design

RN is a **thin JS facade** (Datadog-style, not Sentry-style). All buffering, policy evaluation, and OTLP export happen in the native SDKs (`otel-android-mobile/` and `otel-ios-mobile/`). The JS layer marshals calls across the bridge with 50 ms batching.

See [`react-native/README.md`](react-native/README.md) for usage, and [docs/REACT_NATIVE_SDK_GUIDE.md](../docs/REACT_NATIVE_SDK_GUIDE.md) for the integration guide.
