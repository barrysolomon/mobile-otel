# instrumentation-amplify-datastore

**Status:** Incubating
**Namespace:** `io.opentelemetry.android.mobile.instrumentation.amplifydatastore`
**Classes:** `AmplifyDataStoreInstrumentation`, `AmplifyDataStoreConfig`

Telemetry for [AWS Amplify DataStore](https://docs.amplify.aws/android/build-a-backend/more-features/datastore/) — outbox queue, sync, conflicts. Useful for offline-first apps where local mutations queue up and synchronize when connectivity returns.

## What it emits

| Signal | Default | Purpose |
|--------|---------|---------|
| Outbox events (log records) | On | Pending / publishing / published transitions |
| Sync spans | On | Per-sync-cycle timing |
| Sync metrics | On | Counters + histograms for sync throughput |
| Conflict events | On | Local-vs-remote conflict resolutions |
| Network-state attachments | On | Attach `network.online` to each event |

## How it's wired

Opt-in:

```kotlin
MobileOtel.initialize(this, MobileConfig(
    amplifyDataStoreConfig = AmplifyDataStoreConfig(
        enabled = true,
        captureOutboxEvents = true,
        captureSyncSpans = true,
        captureSyncMetrics = true,
        captureConflicts = true,
        attachNetworkState = true,
        syncTimeoutMs = 60_000,
    )
))
```

Subscribes to `Amplify.DataStore.observe(...)` and `Amplify.Hub` channels.

## Test

```bash
cd ../../examples/demo-app
./gradlew :instrumentation-amplify-datastore:test
```

## See also

- [project memory: Innovapptive deal](../../../docs/superpowers/) — context on why this module exists
