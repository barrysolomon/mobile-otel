# symbol-upload

Pushes crash-symbolication mappings (Android R8 `mapping.txt`, iOS dSYM, React
Native JS source-maps) to a mapping store, keyed by the **same build id the SDK
stamps into `app.build.id` at runtime** (see
[docs/design/symbolication.md](../../docs/design/symbolication.md)). This is
Phase 2 of the symbolication pipeline — Phase 1 shipped the `app.build.id`
resource attribute; this tool closes the loop so a minified crash can be matched
back to its mapping.

The store is **content-addressed by `(platform, build-id)`**. A build id is
immutable, so an artifact that is already present is never re-uploaded — the tool
issues a `HEAD` first and skips the `PUT` on `200`. Safe to run on every CI build.

## Install / build

Pure Go stdlib, no dependencies:

```bash
cd tools/symbol-upload
go build -o symbol-upload .
go test ./...            # unit tests
```

## Configuration

Endpoint, token, and (optional) dataset come from flags or environment:

| Flag         | Env                      | Meaning                     |
|--------------|--------------------------|-----------------------------|
| `--endpoint` | `DASH0_SYMBOL_ENDPOINT`  | mapping store base URL      |
| `--token`    | `DASH0_AUTH_TOKEN`       | bearer token                |
| `--dataset`  | `DASH0_DATASET`          | optional dataset name       |

## Per-platform usage

### Android (R8 / ProGuard `mapping.txt`)

The R8 mapping id is **not** runtime-readable, so the app stamps a build id it
controls into `io.dash0.mobile.BUILD_ID` (which becomes `app.build.id`) and the
**same value** keys the upload:

```bash
symbol-upload android \
  --mapping app/build/outputs/mapping/release/mapping.txt \
  --build-id "$DASH0_BUILD_ID" \
  --app-version 1.2.3
```

Wire it into a release build so the id is generated once, stamped, and uploaded.
Add to the app's `build.gradle.kts`:

```kotlin
// One build id per release build; used for BOTH the manifest stamp and the upload.
val dash0BuildId = providers.gradleProperty("dash0BuildId")
    .orElse(java.util.UUID.randomUUID().toString())

android.defaultConfig.manifestPlaceholders["dash0BuildId"] = dash0BuildId.get()

// Hook the upload to run after R8 produces mapping.txt on release builds.
tasks.register<Exec>("uploadDash0Mapping") {
    val mapping = layout.buildDirectory.file("outputs/mapping/release/mapping.txt")
    onlyIf { System.getenv("DASH0_SYMBOL_ENDPOINT") != null && mapping.get().asFile.exists() }
    commandLine(
        "symbol-upload", "android",
        "--mapping", mapping.get().asFile.absolutePath,
        "--build-id", dash0BuildId.get(),
    )
}
tasks.matching { it.name == "minifyReleaseWithR8" }.configureEach {
    finalizedBy("uploadDash0Mapping")
}
```

### iOS (dSYM)

The SDK derives `app.build.id` from the main executable's Mach-O `LC_UUID` at
runtime, and the dSYM carries the same UUID by construction — so **no build id
needs to be passed**; the tool reads the UUID(s) straight out of the dSYM:

```bash
symbol-upload ios --dsym "$DWARF_DSYM_FOLDER_PATH/MyApp.app.dSYM" --app-version 1.2.3
```

Add as an Xcode **Run Script** build phase (after "Strip Linked Product") or a
Fastlane step. Xcode exposes `DWARF_DSYM_FOLDER_PATH` and
`DWARF_DSYM_FILE_NAME`:

```sh
# Run Script phase — release/archive only
if [ "$CONFIGURATION" = "Release" ]; then
  symbol-upload ios --dsym "$DWARF_DSYM_FOLDER_PATH/$DWARF_DSYM_FILE_NAME"
fi
```

A fat/multi-arch image carries several UUIDs; the tool uploads once per UUID.

### React Native (Hermes / Metro source-map)

The build id is the **SHA-256 of the JS bundle content**. Upload derives it and
prints it — pass the same value to `Dash0Mobile.start({ buildId })` so runtime JS
crashes match this source-map:

```bash
symbol-upload react-native \
  --bundle android/app/build/generated/assets/.../index.android.bundle \
  --source-map .../index.android.bundle.map \
  --app-version 1.2.3
# → prints: build id <sha256>; pass to Dash0Mobile.start({ buildId })
```

To make the id available to the running app without a manual copy, compute it at
build time and inject it (e.g. write it into your JS config / an env-baked
constant) so `start({ buildId })` uses the identical value the source-map was
keyed by.

## Store contract

For each artifact the tool:

1. `HEAD /v1/symbol-mappings/{platform}/{build-id}` — `200` ⇒ skip.
2. `PUT` the same path, body **gzip-compressed** (`Content-Encoding: gzip`),
   `?app_version=` appended when provided.

Headers: `Authorization: Bearer <token>`, `Dash0-Dataset: <dataset>` (when set).
The backend symbolicator (Phase 3) resolves frames by looking up the stored
artifact under `(platform, build-id)`.
