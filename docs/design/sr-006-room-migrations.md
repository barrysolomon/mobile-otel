# SR-006: Explicit Room Migrations

**Severity:** HIGH
**Status:** Done (2026-04-09)

## Problem

`DiskLogBuffer` uses `fallbackToDestructiveMigration()` (DiskLogBuffer:59). Any schema version mismatch — rollback, skipped update, phased rollout race — silently drops the entire `log_records` table and recreates it. All buffered telemetry (including pre-crash events) is lost without any signal.

## Design

Replace destructive fallback with explicit `Migration` objects for every schema transition.

### Migration 2 → 3 (monotonicMs + bootId)

Already shipped. The destructive fallback handles this today, but only because it drops everything. Define the proper migration:

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE log_records ADD COLUMN monotonicMs INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE log_records ADD COLUMN bootId TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_log_records_monotonicMs ON log_records(monotonicMs)")
    }
}
```

### Future Migrations

All future schema changes must include:
1. A `Migration(N, N+1)` object
2. A test that opens a database at version N, runs the migration, and verifies data integrity
3. Room's `exportSchema = true` to generate JSON schemas for verification

### Configuration Change

```kotlin
Room.databaseBuilder(context, LogDatabase::class.java, DB_NAME)
    .addMigrations(MIGRATION_2_3)
    // NO fallbackToDestructiveMigration()
    .build()
```

If an unknown version is encountered (future → past rollback), Room will throw `IllegalStateException`. This is preferable to silent data loss — the SDK catches it, logs a warning, and creates a fresh database.

## Files Changed

| File | Change |
|------|--------|
| `DiskLogBuffer.kt` | Add `MIGRATION_2_3`, remove `fallbackToDestructiveMigration()`, add migration-failure fallback handler |
| `LogDatabase.kt` (or equivalent) | Set `exportSchema = true` |

## Testing

- Test: create database at v2 schema, run migration, verify existing rows survive with `monotonicMs=0` and `bootId=null`
- Test: downgrade from v4 to v3 throws, SDK recovers gracefully
