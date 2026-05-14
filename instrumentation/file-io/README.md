# instrumentation-file-io

**Status:** Incubating
**Namespace:** `io.opentelemetry.android.mobile.instrumentation.fileio`

File I/O spans via a traced-wrapper pattern. The app wraps its own `InputStream` / `OutputStream` calls; the SDK doesn't try to swap the framework I/O classes.

## What it emits

- `file.read` / `file.write` spans
- Attributes: `file.path` (with sandbox-relative paths only), `file.size_bytes`, `io.bytes_transferred`

## How it's wired

User-wired:

```kotlin
val input = OTelFileIO.trace(FileInputStream(file), "read-config")
val output = OTelFileIO.trace(FileOutputStream(file), "write-cache")
```

## Privacy

Absolute paths under the app sandbox are rewritten to relative paths (`/data/data/<pkg>/files/...` → `files/...`).

## Test

```bash
cd ../../examples/demo-app
./gradlew :instrumentation-file-io:test
```
