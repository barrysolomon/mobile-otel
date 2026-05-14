# instrumentation-database

**Status:** Incubating
**Namespace:** `io.opentelemetry.android.mobile.instrumentation.database`

Room / SQLite query telemetry via `RoomDatabase.QueryCallback`. Emits per-query spans with timing and SQL text (parameterized — bind values are scrubbed).

## What it emits

- `db.query` spans following OTel database semantic conventions
- Attributes: `db.statement`, `db.operation`, `db.system=sqlite`, `db.rows_affected`

## How it's wired

User-wired — register the callback when building your Room database:

```kotlin
Room.databaseBuilder(context, AppDatabase::class.java, "app-db")
    .setQueryCallback(
        OTelDatabaseInstrumentation.queryCallback(),
        Executors.newSingleThreadExecutor()
    )
    .build()
```

## Privacy

Bound parameter values are **not** captured — only the parameterized SQL string. Useful for performance triage without leaking user data.

## Test

```bash
cd ../../examples/demo-app
./gradlew :instrumentation-database:test
```
