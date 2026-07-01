# React Native — Configuration & API Reference

The RN package (`@barrysolomon/mobile-react-native`) is a **thin JS facade**: all
buffering, policy evaluation, crash recovery, and OTLP export happen in the native
Android + iOS SDKs. This doc is the RN-specific reference — the other guides
([CONFIGURATION.md](CONFIGURATION.md), [QUICK_START.md](QUICK_START.md),
[AUTO_INSTRUMENTATION.md](AUTO_INSTRUMENTATION.md)) describe the native Kotlin API.

> **Install & native wiring:** see the [README React Native section](../README.md#react-native-integration-npm).
> Short version: `npm install @barrysolomon/mobile-react-native@alpha`, `pod install`
> (iOS), and add the public Maven repo to your host `android/settings.gradle`:
> `maven { url 'https://barrysolomon.github.io/mobile-otel/maven' }` (no auth).

## The public API

Everything is on the **named** export `Dash0Mobile` (there is no default export):

```ts
import { Dash0Mobile } from '@barrysolomon/mobile-react-native';
```

| Method | Signature | Purpose |
|---|---|---|
| `start` | `start(config: StartConfig): Promise<void>` | Initialize the SDK + auto-instrumentation. Call once at app startup. |
| `log` | `log(name, attributes?, severity?): void` | Emit an OTel log record. `severity` is an OTel `SeverityNumber` (default `9` = INFO; `17` = ERROR; `21`+ = FATAL, flushed synchronously). |
| `startSpan` | `startSpan(name, attributes?, spanKind?): SpanHandle` | Start a span; returns a handle with `setAttribute` / `setStatus` / `end`. Nested `startSpan` calls auto-parent (LIFO). |
| `span` | `span(name, fn, attributes?): Promise<T>` | Wrap async/sync work in a span; auto-sets `OK`/`ERROR` status and ends the span. |
| `recordMetric` | `recordMetric(name, value, instrumentType?, attributes?): void` | Record a `counter` (default), `histogram`, or `gauge` value. |
| `flushWindow` | `flushWindow(minutes: number): Promise<void>` | Force-export the last N minutes of buffered events now (drains the JS batch + native buffer). |
| `shutdown` | `shutdown(): Promise<void>` | Uninstall auto-instrumentation, flush, and tear down. |

**Experimental (may change in a minor):** `startJourney(name)`, `endJourney(id)`,
`captureScreenshot(trigger?)`, `captureWireframe(trigger?)`.

**Opt-in helpers (separate named exports):**

```ts
import { installReactNavigationInstrumentation, withTapTelemetry, otel } from '@barrysolomon/mobile-react-native';
```

- `installReactNavigationInstrumentation(navigationRef)` — screen tracking for React Navigation.
- `withTapTelemetry('targetName', handler)` — wrap a press handler to emit a tap event.
- `otel` — an OTel-API-compatible shim (`otel.trace.getTracer(...)`) so third-party JS libs flow through the bridge.

## `StartConfig`

```ts
await Dash0Mobile.start({
  serviceName: 'my-app',          // required
  serviceVersion: '1.2.3',        // optional
  endpoint: 'https://ingress.us-west-2.aws.dash0.com', // see "Endpoints" below
  authToken: 'auth_...',          // optional — sets Authorization: Bearer <token>
  dataset: 'otel-mobile',         // optional — Dash0 dataset (sets the Dash0-Dataset header)

  // Trace sampling. RN default is always_on (unlike the native SDKs' dynamic(0.1)),
  // because RN manual spans are root spans and dynamic sampling would drop ~90% of
  // a first span. Sample in the collector, or opt into on-device sampling here:
  sampling: { strategy: 'always_on' },        // or { strategy: 'dynamic', normalRate: 0.1 }

  bufferConfig: { ramEvents: 5000, diskBytes: 52428800 },  // optional overrides
  enablePolicyPolling: false,                              // optional

  // Auto-capture toggles. JS network + errors are ON by default; the native-only
  // suites (tap/scroll/screen/freeze/vitals/deviceStats/screenshot/wireframe) are
  // OFF by default on RN — set true to enable the native capability.
  autoCapture: {
    network: true,   // fetch/XHR spans (JS) + native OkHttp interceptor (Android)
    errors: true,    // JS errors + unhandled rejections
    // tap, scroll, textInput, screen, freeze, vitals, deviceStats,
    // screenshot, wireframe — native-only, default OFF on RN
  },

  // Merged into the native resource. The bridge always injects
  // telemetry.distro.name/version + app.framework=react-native; add your own here.
  extraResourceAttributes: { 'deployment.environment': 'production' },
});
```

Lifecycle events (`app.foreground` / `app.background` / `app.start`) are always-on
via native instrumentation (Android `ProcessLifecycleOwner`, iOS `NotificationCenter`)
— there is no JS knob for them.

## Endpoints

- **Dash0 ingress** — a plain HTTPS host with **no port and no path**:
  `https://ingress.us-west-2.aws.dash0.com` (US) or `https://ingress.eu-west-1.aws.dash0.com` (EU).
  Dash0 terminates on 443 and appends `/v1/...`. Pass `authToken` + `dataset`.
- **Generic OTLP collector** — include the port/path your collector expects, e.g.
  `https://collector.example.com:4318` (OTLP HTTP/protobuf).

> **Transport:** the RN → Android path exports over the native Android SDK. Point
> `endpoint` at an HTTPS ingress/collector; the native layer handles the OTLP
> protocol and W3C `traceparent` propagation for mobile→backend trace stitching.

## Minimal example

```ts
import { Dash0Mobile } from '@barrysolomon/mobile-react-native';

export async function initTelemetry() {
  await Dash0Mobile.start({
    serviceName: 'astronomy-shop-rn',
    endpoint: 'https://ingress.us-west-2.aws.dash0.com',
    authToken: process.env.DASH0_AUTH_TOKEN,
    dataset: 'otel-mobile',
    autoCapture: { network: true, errors: true },
  });
}

// Manual telemetry
Dash0Mobile.log('cart.add_item', { 'shop.item_id': 'sku-42', qty: 2 });

await Dash0Mobile.span('checkout', async (span) => {
  span.setAttribute('cart.total', 129.99);
  await doCheckout();
});

try {
  await risky();
} catch (e) {
  Dash0Mobile.log('checkout.failed', { error: String(e) }, 17); // 17 = ERROR
}

await Dash0Mobile.flushWindow(5); // export the last 5 minutes now
```

## Concurrency note

`startSpan` maintains a single global LIFO parent stack, so nested/sequential spans
auto-parent correctly. Concurrent async span *trees* (overlapping, not nested) are
not distinguished — RN doesn't expose `AsyncLocalStorage`/async-hooks cleanly. For
overlapping concurrent work, thread parent context manually or keep spans nested.
