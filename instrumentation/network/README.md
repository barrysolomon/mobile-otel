# instrumentation-network

**Status:** Production (user-wired)
**Namespace:** `io.opentelemetry.android.mobile.instrumentation.network`
**Class:** `OTelNetworkInterceptor`

OkHttp interceptor for HTTP request/response spans. Unlike the other modules this one is **opt-in by code** — you add it to your own `OkHttpClient.Builder()` so we don't try to swap the user's HTTP stack at startup.

## What it emits

- HTTP spans following OTel HTTP semantic conventions (`http.request.method`, `http.response.status_code`, `url.full`, etc.)
- `http.error` log records when `status_code >= 500` (triggers the HTTP-error flush policy)

## How to wire it

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(OTelNetworkInterceptor())
    .build()
```

## Config

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(OTelNetworkInterceptor(NetworkConfig.production()))  // privacy preset
    .build()
```

Presets: `default()`, `minimal()`, `debug()`, `production()`. See [docs/CONFIGURATION.md](../../docs/CONFIGURATION.md).

## Test

```bash
cd ../../examples/demo-app
./gradlew :instrumentation-network:test
```

## Gotchas

- Interceptor order matters — add `OTelNetworkInterceptor` as the **first** application interceptor so it sees the URL the user dispatched, not whatever a downstream interceptor rewrites it to.
- `http.error` events MUST carry `event.name=http.error` for the flush policy to fire (memory `feedback_http_error_event_name_missing.md`).
