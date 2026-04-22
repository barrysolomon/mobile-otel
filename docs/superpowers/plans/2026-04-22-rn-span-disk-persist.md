# RN Gate 4: Span Disk-Persist Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close Gate 4 on RN iOS by persisting spans to disk on export failure and draining them on next launch, mirroring the commit `1a69c7e` log-side guarantee.

**Architecture:** A `PersistingSpanExporter` decorator writes span batches to a new `DiskSpanBuffer` actor (separate `buffered_spans` sqlite table) when the underlying OTLP exporter returns `.failure`. At `OTelMobile.start()`, a `Task.detached` recovery block drains any persisted spans through the OTLP exporter in 512-row batches with read-then-conditionally-delete semantics, emitting a single combined `app.recovery_start` marker with additive `dash0.recovery.span_count` / `dash0.recovery.span_bytes_pending` attributes. `PersistingSpanExporter` uses a `DispatchSemaphore` to synchronously await the actor persist inside the otherwise-synchronous `SpanExporter.export` protocol method.

**Tech Stack:** Swift 5.9 (SwiftPM, iOS 15+ target), actor-based concurrency, raw `SQLite3` C API, Swift Testing (`@Test`/`#expect`), `opentelemetry-swift-core` 2.x.

**Spec:** [`docs/superpowers/specs/2026-04-22-rn-span-disk-persist-design.md`](../specs/2026-04-22-rn-span-disk-persist-design.md)

---

## File Structure

### Create

- `otel-ios-mobile/Sources/OTelMobileSDK/Buffering/BufferedSpan.swift`
- `otel-ios-mobile/Sources/OTelMobileSDK/Buffering/DiskSpanBuffer.swift`
- `otel-ios-mobile/Sources/OTelMobileSDK/Buffering/DiskSpanBufferTestSupport.swift`
- `otel-ios-mobile/Sources/OTelMobileSDK/Export/PersistingSpanExporter.swift`
- `otel-ios-mobile/Tests/OTelMobileSDKTests/Buffering/DiskSpanBufferTests.swift`
- `otel-ios-mobile/Tests/OTelMobileSDKTests/Export/PersistingSpanExporterTests.swift`
- `otel-ios-mobile/Tests/OTelMobileSDKTests/OTelMobileSpanRecoveryTests.swift`

### Modify

- `otel-ios-mobile/Sources/OTelMobileSDK/OTelMobile.swift`
- `examples/upstream-demo-app-rn/AstronomyShopRN/ios/AstronomyShopRN/OTelMobileCallSink.swift`
- `docs/epics/VALIDATION_MATRIX_EPIC.md`

---

Each task is an independent commit with a TDD micro-cycle. Run unit tests on the macOS host via `./run-tests.sh` in `otel-ios-mobile/` (~1s). iOS Simulator build is only for Task 9+ (device validation).

---

## Task 1: `BufferedSpan` value type

**Files:**

- Create: `otel-ios-mobile/Sources/OTelMobileSDK/Buffering/BufferedSpan.swift`

- [ ] **Step 1: Write the file**

Create `otel-ios-mobile/Sources/OTelMobileSDK/Buffering/BufferedSpan.swift`:

```swift
import Foundation
import OpenTelemetrySdk

/// Disk-persisted span wrapper. Mirrors `BufferedEvent` but carries the
/// upstream `SpanData` (Codable) instead of `ReadableLogRecord`. Used by
/// `DiskSpanBuffer` for fail-to-disk persistence when the OTLP trace
/// exporter returns `.failure`.
///
/// `id` is the sqlite rowid; only meaningful on reads. `spanKey` is the
/// dedup unique index (traceId hex + spanId hex) — prevents duplicate
/// disk rows when the same batch is re-presented during RetryableExporter
/// backoffs or crash-safety mid-persist.
public struct BufferedSpan: Sendable {
    public let id: Int64
    public let spanKey: String
    public let startTimeUnixNano: UInt64
    public let sessionId: String
    public let record: SpanData?
    public let recordData: Data
    public let sizeBytes: Int
    public let createdAt: Date

    public init(
        id: Int64 = 0,
        spanKey: String,
        startTimeUnixNano: UInt64,
        sessionId: String,
        record: SpanData? = nil,
        recordData: Data = Data(),
        createdAt: Date = Date()
    ) {
        self.id = id
        self.spanKey = spanKey
        self.startTimeUnixNano = startTimeUnixNano
        self.sessionId = sessionId
        self.record = record
        self.recordData = recordData
        self.sizeBytes = recordData.count
        self.createdAt = createdAt
    }

    /// Build a `BufferedSpan` from an upstream `SpanData`. Encodes the
    /// record to JSON for disk persistence. Returns `nil` if encoding
    /// fails — per SDK_SAFETY.md, buffer malfunction must never crash
    /// the host.
    public static func from(
        _ span: SpanData,
        sessionId: String,
        encoder: JSONEncoder = JSONEncoder()
    ) -> BufferedSpan? {
        guard let data = try? encoder.encode(span) else { return nil }
        let key = span.traceId.hexString + span.spanId.hexString
        let startNs = UInt64(span.startTime.timeIntervalSince1970 * 1_000_000_000)
        return BufferedSpan(
            spanKey: key,
            startTimeUnixNano: startNs,
            sessionId: sessionId,
            record: span,
            recordData: data
        )
    }
}

extension BufferedSpan: Equatable {
    public static func == (lhs: BufferedSpan, rhs: BufferedSpan) -> Bool {
        lhs.spanKey == rhs.spanKey && lhs.recordData == rhs.recordData
    }
}
```

- [ ] **Step 2: Verify build**

Run: `cd otel-ios-mobile && swift build 2>&1 | tail -5`
Expected: `Build complete!`

- [ ] **Step 3: Commit**

```bash
git add otel-ios-mobile/Sources/OTelMobileSDK/Buffering/BufferedSpan.swift
git commit -m "feat(ios): add BufferedSpan value type for disk-persist"
```

---

## Task 2: `DiskSpanBuffer` actor — open/shutdown

**Files:**

- Create: `otel-ios-mobile/Sources/OTelMobileSDK/Buffering/DiskSpanBuffer.swift`
- Create: `otel-ios-mobile/Sources/OTelMobileSDK/Buffering/DiskSpanBufferTestSupport.swift`
- Test: `otel-ios-mobile/Tests/OTelMobileSDKTests/Buffering/DiskSpanBufferTests.swift`

- [ ] **Step 1: Write the failing test**

Create `otel-ios-mobile/Tests/OTelMobileSDKTests/Buffering/DiskSpanBufferTests.swift`:

```swift
import Testing
@testable import OTelMobileSDK

@Suite("DiskSpanBuffer")
struct DiskSpanBufferTests {
    @Test("open creates database file and table")
    func openCreatesDatabase() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }

        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        let rowCount = await buffer.rowCount()
        #expect(rowCount == 0)
        await buffer.shutdown()

        #expect(DiskSpanBufferTestSupport.fileExists(dbPath))
    }

    @Test("shutdown is idempotent")
    func shutdownIdempotent() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }

        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        await buffer.shutdown()
        await buffer.shutdown()
    }
}
```

- [ ] **Step 2: Create test-support helper**

Create `otel-ios-mobile/Sources/OTelMobileSDK/Buffering/DiskSpanBufferTestSupport.swift`:

```swift
import Foundation
import OpenTelemetryApi
import OpenTelemetrySdk

/// Test helpers for `DiskSpanBuffer`. Lives in the SDK target (not the test
/// target) because CLT's `_Testing_Foundation` overlay ships without its
/// `Modules/` directory — test files can't `import Foundation`. See
/// `otel-ios-mobile/CLAUDE.md` gotcha #1.
public enum DiskSpanBufferTestSupport {
    public static func tempDbPath() -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("dash0-span-test-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(
            at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("spans.db")
    }

    public static func removeFile(_ url: URL) {
        try? FileManager.default.removeItem(at: url)
        try? FileManager.default.removeItem(at: url.deletingLastPathComponent())
    }

    public static func fileExists(_ url: URL) -> Bool {
        FileManager.default.fileExists(atPath: url.path)
    }

    /// Build a minimal SpanData for tests. Unique traceId/spanId per call.
    public static func fakeSpan(
        name: String,
        startSecondsAgo: TimeInterval = 5
    ) -> SpanData {
        let now = Date()
        let start = now.addingTimeInterval(-startSecondsAgo)
        return SpanData(
            traceId: TraceId.random(),
            spanId: SpanId.random(),
            name: name,
            kind: .client,
            startTime: start,
            endTime: now
        )
    }
}
```

- [ ] **Step 3: Create the actor skeleton**

Create `otel-ios-mobile/Sources/OTelMobileSDK/Buffering/DiskSpanBuffer.swift`:

```swift
import Foundation
import SQLite3
import OpenTelemetrySdk

private let SQLITE_TRANSIENT_FN = unsafeBitCast(
    OpaquePointer(bitPattern: -1),
    to: sqlite3_destructor_type.self
)

/// Disk-backed span buffer. Actor-isolated, raw `sqlite3` C API. Mirrors
/// `DiskLogBuffer` but stores spans in a separate `buffered_spans` table
/// with its own 50 MB / 24 h budget.
///
/// Used by `PersistingSpanExporter` on export failure and by
/// `OTelMobile.start`'s recovery block on next launch.
///
/// Design: `docs/superpowers/specs/2026-04-22-rn-span-disk-persist-design.md`.
public actor DiskSpanBuffer {
    public nonisolated let dbPath: URL
    public let maxTotalBytes: Int
    public let retentionSeconds: TimeInterval

    private var db: OpaquePointer?
    private var closed: Bool = false

    public init(
        dbPath: URL? = nil,
        maxTotalBytes: Int = 50 * 1024 * 1024,
        retentionSeconds: TimeInterval = 24 * 3600
    ) async throws {
        self.dbPath = try Self.resolveDbPath(preferred: dbPath)
        self.maxTotalBytes = maxTotalBytes
        self.retentionSeconds = retentionSeconds
        self.openAndPrepare()
    }

    public func shutdown() async {
        if closed { return }
        closed = true
        if let handle = db {
            sqlite3_close(handle)
            self.db = nil
        }
    }

    public func rowCount() async -> Int {
        guard !closed, let handle = db else { return 0 }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, "SELECT COUNT(*) FROM buffered_spans;", -1, &stmt, nil) == SQLITE_OK else { return 0 }
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_step(stmt) == SQLITE_ROW else { return 0 }
        return Int(sqlite3_column_int64(stmt, 0))
    }

    // MARK: - Internal

    private static func resolveDbPath(preferred: URL?) throws -> URL {
        if let preferred = preferred { return preferred }
        let base = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true)
        let dir = base.appendingPathComponent("io.dash0.mobile")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("span-buffer.db")
    }

    private func openAndPrepare() {
        var handle: OpaquePointer?
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(dbPath.path, &handle, flags, nil) == SQLITE_OK, handle != nil else {
            NSLog("[DiskSpanBuffer] sqlite3_open_v2 failed at \(dbPath.path)")
            if handle != nil { sqlite3_close(handle) }
            return
        }
        self.db = handle

        _ = runSql("PRAGMA journal_mode=WAL;")
        _ = runSql("PRAGMA synchronous=NORMAL;")
        _ = runSql("PRAGMA temp_store=MEMORY;")
        _ = runSql("""
            CREATE TABLE IF NOT EXISTS buffered_spans (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                span_key        TEXT NOT NULL UNIQUE,
                start_time_ns   INTEGER NOT NULL,
                session_id      TEXT NOT NULL,
                record_json     BLOB NOT NULL,
                size_bytes      INTEGER NOT NULL,
                created_at      INTEGER NOT NULL
            );
        """)
        _ = runSql("CREATE INDEX IF NOT EXISTS idx_start_time ON buffered_spans(start_time_ns);")
    }

    @discardableResult
    fileprivate func runSql(_ sql: String) -> Bool {
        guard let handle = db else { return false }
        var err: UnsafeMutablePointer<CChar>?
        let rc = sqlite3_exec(handle, sql, nil, nil, &err)
        if rc != SQLITE_OK {
            if let err = err {
                NSLog("[DiskSpanBuffer] sqlite error: \(String(cString: err))")
                sqlite3_free(err)
            }
            return false
        }
        return true
    }
}
```

- [ ] **Step 4: Run tests — verify pass**

Run: `cd otel-ios-mobile && ./run-tests.sh 2>&1 | tail -15`
Expected: both tests pass.

- [ ] **Step 5: Commit**

```bash
git add otel-ios-mobile/Sources/OTelMobileSDK/Buffering/DiskSpanBuffer.swift \
        otel-ios-mobile/Sources/OTelMobileSDK/Buffering/DiskSpanBufferTestSupport.swift \
        otel-ios-mobile/Tests/OTelMobileSDKTests/Buffering/DiskSpanBufferTests.swift
git commit -m "feat(ios): DiskSpanBuffer actor skeleton with open/shutdown"
```

---

## Task 3: `DiskSpanBuffer.persist` — dedup-on-write

**Files:**

- Modify: `otel-ios-mobile/Sources/OTelMobileSDK/Buffering/DiskSpanBuffer.swift`
- Modify: `otel-ios-mobile/Tests/OTelMobileSDKTests/Buffering/DiskSpanBufferTests.swift`

- [ ] **Step 1: Write failing tests**

Append to `DiskSpanBufferTests.swift`:

```swift
    @Test("persist writes each span once")
    func persistWritesOnce() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let span = DiskSpanBufferTestSupport.fakeSpan(name: "span.a")
        await buffer.persist([span], sessionId: "sess-1")
        #expect(await buffer.rowCount() == 1)
    }

    @Test("persist is idempotent on dedup key")
    func persistDedup() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let span = DiskSpanBufferTestSupport.fakeSpan(name: "span.a")
        await buffer.persist([span], sessionId: "sess-1")
        await buffer.persist([span], sessionId: "sess-1")
        #expect(await buffer.rowCount() == 1)
    }

    @Test("persist empty list is a no-op")
    func persistEmpty() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        await buffer.persist([], sessionId: "sess-1")
        #expect(await buffer.rowCount() == 0)
    }
```

- [ ] **Step 2: Run tests — verify they fail**

Run: `cd otel-ios-mobile && ./run-tests.sh 2>&1 | head -20`
Expected: compile error — `persist` missing.

- [ ] **Step 3: Implement `persist`**

Inside the `DiskSpanBuffer` actor, before the `// MARK: - Internal` marker, add:

```swift
    /// Insert each span with INSERT OR IGNORE on span_key; re-presenting
    /// the same span list (e.g. during backoff retries) is a no-op.
    public func persist(_ spans: [SpanData], sessionId: String) async {
        guard !closed, db != nil, !spans.isEmpty else { return }
        let encoder = JSONEncoder()
        _ = runSql("BEGIN IMMEDIATE TRANSACTION;")
        for span in spans {
            guard let buffered = BufferedSpan.from(span, sessionId: sessionId, encoder: encoder) else {
                NSLog("[DiskSpanBuffer] skip span \(span.name): encode failed")
                continue
            }
            insertRow(buffered)
        }
        _ = runSql("COMMIT;")
        pruneIfOverBudget()
    }

    private func insertRow(_ span: BufferedSpan) {
        let sql = """
            INSERT OR IGNORE INTO buffered_spans
                (span_key, start_time_ns, session_id, record_json, size_bytes, created_at)
                VALUES (?, ?, ?, ?, ?, ?);
        """
        var stmt: OpaquePointer?
        guard let handle = db,
              sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(stmt) }
        _ = span.spanKey.withCString { cstr in
            sqlite3_bind_text(stmt, 1, cstr, -1, SQLITE_TRANSIENT_FN)
        }
        sqlite3_bind_int64(stmt, 2, Int64(bitPattern: span.startTimeUnixNano))
        _ = span.sessionId.withCString { cstr in
            sqlite3_bind_text(stmt, 3, cstr, -1, SQLITE_TRANSIENT_FN)
        }
        _ = span.recordData.withUnsafeBytes { raw in
            sqlite3_bind_blob(stmt, 4, raw.baseAddress, Int32(span.recordData.count), SQLITE_TRANSIENT_FN)
        }
        sqlite3_bind_int64(stmt, 5, Int64(span.sizeBytes))
        sqlite3_bind_int64(stmt, 6, Int64(span.createdAt.timeIntervalSince1970 * 1000))
        _ = sqlite3_step(stmt)
    }

    /// Placeholder for size-cap enforcement; real implementation lands in Task 5.
    private func pruneIfOverBudget() {}
```

- [ ] **Step 4: Run tests — verify pass**

Run: `cd otel-ios-mobile && ./run-tests.sh 2>&1 | tail -10`
Expected: all 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add otel-ios-mobile/Sources/OTelMobileSDK/Buffering/DiskSpanBuffer.swift \
        otel-ios-mobile/Tests/OTelMobileSDKTests/Buffering/DiskSpanBufferTests.swift
git commit -m "feat(ios): DiskSpanBuffer.persist with dedup-on-write"
```

---

## Task 4: `DiskSpanBuffer.fetchAll` + `deleteUpTo`

**Files:**

- Modify: `otel-ios-mobile/Sources/OTelMobileSDK/Buffering/DiskSpanBuffer.swift`
- Modify: `otel-ios-mobile/Tests/OTelMobileSDKTests/Buffering/DiskSpanBufferTests.swift`

- [ ] **Step 1: Write failing tests**

Append to `DiskSpanBufferTests.swift`:

```swift
    @Test("fetchAll returns rows ordered by id ascending")
    func fetchAllOrdered() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let a = DiskSpanBufferTestSupport.fakeSpan(name: "span.a")
        let b = DiskSpanBufferTestSupport.fakeSpan(name: "span.b")
        await buffer.persist([a, b], sessionId: "sess-1")

        let rows = await buffer.fetchAll(limit: 100)
        #expect(rows.count == 2)
        #expect(rows[0].id < rows[1].id)
        #expect(rows[0].record?.name == "span.a")
    }

    @Test("fetchAll honors the limit")
    func fetchAllRespectsLimit() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        for i in 0..<5 {
            await buffer.persist([DiskSpanBufferTestSupport.fakeSpan(name: "span.\(i)")], sessionId: "sess-1")
        }
        let rows = await buffer.fetchAll(limit: 2)
        #expect(rows.count == 2)
    }

    @Test("deleteUpTo removes rows with id <= anchor")
    func deleteUpToAnchor() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        for i in 0..<4 {
            await buffer.persist([DiskSpanBufferTestSupport.fakeSpan(name: "span.\(i)")], sessionId: "sess-1")
        }
        let rows = await buffer.fetchAll(limit: 10)
        let anchor = rows[1].id
        await buffer.deleteUpTo(id: anchor)

        let remaining = await buffer.fetchAll(limit: 10)
        #expect(remaining.count == 2)
    }
```

- [ ] **Step 2: Run — verify they fail**

Run: `cd otel-ios-mobile && ./run-tests.sh 2>&1 | head -20`
Expected: compile error.

- [ ] **Step 3: Implement `fetchAll` and `deleteUpTo`**

Add to `DiskSpanBuffer.swift` inside the actor, after `persist`:

```swift
    /// Read-only snapshot of up to `limit` rows, oldest first (by rowid).
    /// Used by recovery; caller decides what to delete after a successful
    /// export (see `deleteUpTo(id:)`).
    public func fetchAll(limit: Int) async -> [BufferedSpan] {
        guard !closed, let handle = db, limit > 0 else { return [] }
        let sql = """
            SELECT id, span_key, start_time_ns, session_id, record_json, size_bytes, created_at
            FROM buffered_spans
            ORDER BY id ASC
            LIMIT ?;
        """
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK else { return [] }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int(stmt, 1, Int32(limit))

        var out: [BufferedSpan] = []
        let decoder = JSONDecoder()
        while sqlite3_step(stmt) == SQLITE_ROW {
            let id = sqlite3_column_int64(stmt, 0)
            let spanKey = String(cString: sqlite3_column_text(stmt, 1))
            let startNs = UInt64(bitPattern: sqlite3_column_int64(stmt, 2))
            let sessionId = String(cString: sqlite3_column_text(stmt, 3))
            let blobPtr = sqlite3_column_blob(stmt, 4)
            let blobLen = Int(sqlite3_column_bytes(stmt, 4))
            let data = (blobPtr != nil && blobLen > 0)
                ? Data(bytes: blobPtr!, count: blobLen)
                : Data()
            let createdMs = sqlite3_column_int64(stmt, 6)
            let createdAt = Date(timeIntervalSince1970: TimeInterval(createdMs) / 1000)
            let record = (try? decoder.decode(SpanData.self, from: data))
            out.append(BufferedSpan(
                id: id, spanKey: spanKey, startTimeUnixNano: startNs,
                sessionId: sessionId, record: record, recordData: data,
                createdAt: createdAt))
        }
        return out
    }

    /// Delete rows with `id <= anchor`. Called by recovery after a
    /// successful batch export.
    public func deleteUpTo(id anchor: Int64) async {
        guard !closed, let handle = db else { return }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, "DELETE FROM buffered_spans WHERE id <= ?;", -1, &stmt, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int64(stmt, 1, anchor)
        _ = sqlite3_step(stmt)
    }
```

- [ ] **Step 4: Run — verify pass**

Run: `cd otel-ios-mobile && ./run-tests.sh 2>&1 | tail -10`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add otel-ios-mobile/Sources/OTelMobileSDK/Buffering/DiskSpanBuffer.swift \
        otel-ios-mobile/Tests/OTelMobileSDKTests/Buffering/DiskSpanBufferTests.swift
git commit -m "feat(ios): DiskSpanBuffer.fetchAll + deleteUpTo for recovery"
```

---

## Task 5: `DiskSpanBuffer` — size cap + TTL prune

**Files:**

- Modify: `otel-ios-mobile/Sources/OTelMobileSDK/Buffering/DiskSpanBuffer.swift`
- Modify: `otel-ios-mobile/Tests/OTelMobileSDKTests/Buffering/DiskSpanBufferTests.swift`

- [ ] **Step 1: Write failing tests**

Append to `DiskSpanBufferTests.swift`:

```swift
    @Test("pruneByTTL removes rows older than retentionSeconds")
    func pruneByTTL() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath, retentionSeconds: 1)
        defer { Task { await buffer.shutdown() } }

        await buffer.persist([DiskSpanBufferTestSupport.fakeSpan(name: "old")], sessionId: "s")
        try await Task.sleep(nanoseconds: 1_200_000_000)
        await buffer.pruneByTTL()
        #expect(await buffer.rowCount() == 0)
    }

    @Test("size cap evicts oldest when exceeded")
    func pruneBySize() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath, maxTotalBytes: 2048)
        defer { Task { await buffer.shutdown() } }

        for i in 0..<20 {
            await buffer.persist([DiskSpanBufferTestSupport.fakeSpan(name: "span.\(i)")], sessionId: "s")
        }
        let count = await buffer.rowCount()
        #expect(count < 20)
    }
```

- [ ] **Step 2: Run — verify they fail**

Run: `cd otel-ios-mobile && ./run-tests.sh 2>&1 | head -20`
Expected: compile error on `pruneByTTL`, or size-cap test fails.

- [ ] **Step 3: Implement prune + totalSizeBytes**

Replace the placeholder `private func pruneIfOverBudget() {}` in `DiskSpanBuffer.swift` with:

```swift
    /// Remove rows older than `retentionSeconds`. Called from recovery.
    public func pruneByTTL() async {
        guard !closed, let handle = db else { return }
        let cutoffMs = Int64((Date().timeIntervalSince1970 - retentionSeconds) * 1000)
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, "DELETE FROM buffered_spans WHERE created_at < ?;", -1, &stmt, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_int64(stmt, 1, cutoffMs)
        _ = sqlite3_step(stmt)
    }

    /// Current total size in bytes. Used by recovery stats + tests.
    public func totalSizeBytes() async -> Int {
        guard !closed, let handle = db else { return 0 }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, "SELECT COALESCE(SUM(size_bytes), 0) FROM buffered_spans;", -1, &stmt, nil) == SQLITE_OK else { return 0 }
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_step(stmt) == SQLITE_ROW else { return 0 }
        return Int(sqlite3_column_int64(stmt, 0))
    }

    private func pruneIfOverBudget() {
        guard !closed, let handle = db else { return }
        var current = currentBytes()
        if current <= Int64(maxTotalBytes) { return }
        let deleteStep: Int32 = 10
        while current > Int64(maxTotalBytes) {
            var del: OpaquePointer?
            let sql = "DELETE FROM buffered_spans WHERE id IN (SELECT id FROM buffered_spans ORDER BY id ASC LIMIT ?);"
            guard sqlite3_prepare_v2(handle, sql, -1, &del, nil) == SQLITE_OK else { return }
            sqlite3_bind_int(del, 1, deleteStep)
            let rc = sqlite3_step(del)
            let changes = sqlite3_changes(handle)
            sqlite3_finalize(del)
            if rc != SQLITE_DONE || changes == 0 { break }
            current = currentBytes()
        }
    }

    private func currentBytes() -> Int64 {
        guard let handle = db else { return 0 }
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, "SELECT COALESCE(SUM(size_bytes), 0) FROM buffered_spans;", -1, &stmt, nil) == SQLITE_OK else { return 0 }
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_step(stmt) == SQLITE_ROW else { return 0 }
        return sqlite3_column_int64(stmt, 0)
    }
```

- [ ] **Step 4: Run — verify pass**

Run: `cd otel-ios-mobile && ./run-tests.sh 2>&1 | tail -10`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add otel-ios-mobile/Sources/OTelMobileSDK/Buffering/DiskSpanBuffer.swift \
        otel-ios-mobile/Tests/OTelMobileSDKTests/Buffering/DiskSpanBufferTests.swift
git commit -m "feat(ios): DiskSpanBuffer size cap + TTL prune"
```

---

## Task 6: `PersistingSpanExporter` decorator

**Files:**

- Create: `otel-ios-mobile/Sources/OTelMobileSDK/Export/PersistingSpanExporter.swift`
- Create: `otel-ios-mobile/Tests/OTelMobileSDKTests/Export/PersistingSpanExporterTests.swift`

- [ ] **Step 1: Write failing tests**

Create `otel-ios-mobile/Tests/OTelMobileSDKTests/Export/PersistingSpanExporterTests.swift`:

```swift
import Testing
import OpenTelemetrySdk
@testable import OTelMobileSDK

@Suite("PersistingSpanExporter")
struct PersistingSpanExporterTests {
    @Test("delegate success: no disk write")
    func successNoWrite() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let delegate = StubSpanExporter(result: .success)
        let exporter = PersistingSpanExporter(delegate: delegate, diskBuffer: buffer, sessionId: "s")

        let result = exporter.export(
            spans: [DiskSpanBufferTestSupport.fakeSpan(name: "ok")],
            explicitTimeout: nil)
        #expect(result == .success)
        #expect(await buffer.rowCount() == 0)
    }

    @Test("delegate failure: spans written to disk")
    func failureWritesDisk() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let delegate = StubSpanExporter(result: .failure)
        let exporter = PersistingSpanExporter(delegate: delegate, diskBuffer: buffer, sessionId: "s")

        let result = exporter.export(
            spans: [DiskSpanBufferTestSupport.fakeSpan(name: "a"),
                    DiskSpanBufferTestSupport.fakeSpan(name: "b")],
            explicitTimeout: nil)
        #expect(result == .failure)
        #expect(await buffer.rowCount() == 2)
    }

    @Test("nil diskBuffer: passthrough, no crash")
    func nilBufferPassthrough() async throws {
        let delegate = StubSpanExporter(result: .failure)
        let exporter = PersistingSpanExporter(delegate: delegate, diskBuffer: nil, sessionId: "s")
        let result = exporter.export(
            spans: [DiskSpanBufferTestSupport.fakeSpan(name: "x")],
            explicitTimeout: nil)
        #expect(result == .failure)
    }

    @Test("empty span list: never writes even on failure")
    func emptyListNoWrite() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let delegate = StubSpanExporter(result: .failure)
        let exporter = PersistingSpanExporter(delegate: delegate, diskBuffer: buffer, sessionId: "s")
        _ = exporter.export(spans: [], explicitTimeout: nil)
        #expect(await buffer.rowCount() == 0)
    }

    @Test("flush and shutdown pass through")
    func flushShutdownPassthrough() async throws {
        let delegate = StubSpanExporter(result: .success)
        let exporter = PersistingSpanExporter(delegate: delegate, diskBuffer: nil, sessionId: "s")
        #expect(exporter.flush(explicitTimeout: nil) == .success)
        exporter.shutdown(explicitTimeout: nil)
        #expect(delegate.shutdownCalled)
    }
}

final class StubSpanExporter: SpanExporter, @unchecked Sendable {
    let result: SpanExporterResultCode
    private(set) var shutdownCalled = false
    init(result: SpanExporterResultCode) { self.result = result }
    func export(spans: [SpanData], explicitTimeout: TimeInterval?) -> SpanExporterResultCode { result }
    func flush(explicitTimeout: TimeInterval?) -> SpanExporterResultCode { result }
    func shutdown(explicitTimeout: TimeInterval?) { shutdownCalled = true }
}
```

- [ ] **Step 2: Run — verify they fail**

Run: `cd otel-ios-mobile && ./run-tests.sh 2>&1 | head -10`
Expected: compile error.

- [ ] **Step 3: Implement the decorator**

Create `otel-ios-mobile/Sources/OTelMobileSDK/Export/PersistingSpanExporter.swift`:

```swift
import Foundation
import OpenTelemetrySdk

/// `SpanExporter` decorator. On `.failure` from the delegate, persists the
/// batch to `DiskSpanBuffer` for recovery on next launch.
///
/// `SpanExporter.export` is synchronous, but our disk buffer is actor-
/// isolated (async). We bridge with a `DispatchSemaphore` so the export
/// call does not return until the disk write completes (or the 5s cap
/// elapses). This mirrors the log-side pattern where
/// `MobileLogRecordProcessor.onEmit` awaits `diskBuffer.insert` inline.
///
/// The wait happens on BSP's background BlockOperation thread, so briefly
/// blocking it on sqlite I/O is acceptable.
public final class PersistingSpanExporter: SpanExporter {
    private let delegate: SpanExporter
    private let diskBuffer: DiskSpanBuffer?
    private let sessionId: String
    private let persistTimeout: TimeInterval

    public init(
        delegate: SpanExporter,
        diskBuffer: DiskSpanBuffer?,
        sessionId: String,
        persistTimeout: TimeInterval = 5
    ) {
        self.delegate = delegate
        self.diskBuffer = diskBuffer
        self.sessionId = sessionId
        self.persistTimeout = persistTimeout
    }

    public func export(
        spans: [SpanData],
        explicitTimeout: TimeInterval?
    ) -> SpanExporterResultCode {
        let result = delegate.export(spans: spans, explicitTimeout: explicitTimeout)
        guard result == .failure,
              let buffer = diskBuffer,
              !spans.isEmpty else { return result }
        let semaphore = DispatchSemaphore(value: 0)
        Task {
            await buffer.persist(spans, sessionId: sessionId)
            semaphore.signal()
        }
        _ = semaphore.wait(timeout: .now() + persistTimeout)
        return result
    }

    public func flush(explicitTimeout: TimeInterval?) -> SpanExporterResultCode {
        delegate.flush(explicitTimeout: explicitTimeout)
    }

    public func shutdown(explicitTimeout: TimeInterval?) {
        delegate.shutdown(explicitTimeout: explicitTimeout)
    }
}
```

- [ ] **Step 4: Run — verify pass**

Run: `cd otel-ios-mobile && ./run-tests.sh 2>&1 | tail -10`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add otel-ios-mobile/Sources/OTelMobileSDK/Export/PersistingSpanExporter.swift \
        otel-ios-mobile/Tests/OTelMobileSDKTests/Export/PersistingSpanExporterTests.swift
git commit -m "feat(ios): PersistingSpanExporter — fail-to-disk decorator"
```

---

## Task 7: `OTelMobile.start` — new `spanDiskBuffer` parameter

**Files:**

- Modify: `otel-ios-mobile/Sources/OTelMobileSDK/OTelMobile.swift`

Context: current signature is `start(config:diskBuffer:)` where `diskBuffer: DiskLogBuffer? = nil`. Add a third parameter `spanDiskBuffer: DiskSpanBuffer? = nil` so existing callers keep working.

- [ ] **Step 1: Inspect `RetryableExporter` to decide wrapping shape**

Run: `head -30 otel-ios-mobile/Sources/OTelMobileSDK/Export/RetryableExporter.swift`

If it is typed against `LogRecordExporter`-only (likely, per commit `1a69c7e`), do NOT wrap spans with it in this task. Use `PersistingSpanExporter` alone as the top-level trace exporter. Add a follow-up item for "generalize RetryableExporter to SpanExporter" to the epic.

If it is generic / protocol-typed across signals, wrap as `RetryableExporter(delegate: PersistingSpanExporter(...))`.

- [ ] **Step 2: Extend the signature and wire the stack**

In `OTelMobile.swift`, change the `public static func start` signature (around line 188):

```swift
    public static func start(
        config: MobileConfig,
        diskBuffer: DiskLogBuffer? = nil,
        spanDiskBuffer: DiskSpanBuffer? = nil
    ) throws -> OTelMobile {
```

Locate where `otlpTraceExporter` is built (around line 245) and replace that single line with the assumption from Step 1. Log-only `RetryableExporter` case (most likely):

```swift
        let baseTraceExporter = try OTLPExporterFactory.makeHttpTraceExporter(
            endpoint: config.endpoint,
            authToken: config.authToken,
            extraHeaders: config.extraHeaders
        )
        let otlpTraceExporter = PersistingSpanExporter(
            delegate: baseTraceExporter,
            diskBuffer: spanDiskBuffer,
            sessionId: sessionProvider.current().id
        )
```

Generic case (if `RetryableExporter` accepts SpanExporter):

```swift
        let baseTraceExporter = try OTLPExporterFactory.makeHttpTraceExporter(
            endpoint: config.endpoint,
            authToken: config.authToken,
            extraHeaders: config.extraHeaders
        )
        let persisting = PersistingSpanExporter(
            delegate: baseTraceExporter,
            diskBuffer: spanDiskBuffer,
            sessionId: sessionProvider.current().id
        )
        let otlpTraceExporter = RetryableExporter(delegate: persisting)
```

- [ ] **Step 3: Store `spanDiskBuffer` as an instance property**

Add to the `OTelMobile` class (near the existing `diskBuffer` stored property; search for it):

```swift
    /// Held so the recovery Task.detached can call stats/fetchAll/deleteUpTo.
    private let spanDiskBuffer: DiskSpanBuffer?
```

Set it in the instance factory alongside the existing `diskBuffer` stored property assignment. If the instance is built via a private initializer (common pattern), add the parameter to that initializer.

- [ ] **Step 4: Build**

Run: `cd otel-ios-mobile && swift build 2>&1 | tail -5`
Expected: `Build complete!`

- [ ] **Step 5: Commit**

```bash
git add otel-ios-mobile/Sources/OTelMobileSDK/OTelMobile.swift
git commit -m "feat(ios): OTelMobile.start accepts spanDiskBuffer; wire PersistingSpanExporter"
```

---

## Task 8: `OTelMobile.start` — recovery block for spans

**Files:**

- Modify: `otel-ios-mobile/Sources/OTelMobileSDK/OTelMobile.swift`
- Create: `otel-ios-mobile/Tests/OTelMobileSDKTests/OTelMobileSpanRecoveryTests.swift`

- [ ] **Step 1: Write failing tests**

Create `otel-ios-mobile/Tests/OTelMobileSDKTests/OTelMobileSpanRecoveryTests.swift`:

```swift
import Testing
import OpenTelemetrySdk
@testable import OTelMobileSDK

@Suite("OTelMobile span recovery")
struct OTelMobileSpanRecoveryTests {
    @Test("recovers persisted spans and deletes rows")
    func recoversAndDeletes() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let spans = (0..<3).map { DiskSpanBufferTestSupport.fakeSpan(name: "span.\($0)") }
        await buffer.persist(spans, sessionId: "s")
        #expect(await buffer.rowCount() == 3)

        let captured = CapturingSpanExporter()
        await OTelMobile.recoverSpans(from: buffer, exporter: captured, batchSize: 2)

        let totalExported = captured.exportedBatches.reduce(0) { $0 + $1.count }
        #expect(totalExported == 3)
        #expect(await buffer.rowCount() == 0)
    }

    @Test("on export failure, rows remain for next launch")
    func failureRetainsRows() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        await buffer.persist(
            [DiskSpanBufferTestSupport.fakeSpan(name: "x")], sessionId: "s")

        let failing = StubSpanExporter(result: .failure)
        await OTelMobile.recoverSpans(from: buffer, exporter: failing, batchSize: 512)
        #expect(await buffer.rowCount() == 1)
    }

    @Test("empty buffer: no export calls")
    func emptyBufferNoop() async throws {
        let dbPath = DiskSpanBufferTestSupport.tempDbPath()
        defer { DiskSpanBufferTestSupport.removeFile(dbPath) }
        let buffer = try await DiskSpanBuffer(dbPath: dbPath)
        defer { Task { await buffer.shutdown() } }

        let captured = CapturingSpanExporter()
        await OTelMobile.recoverSpans(from: buffer, exporter: captured, batchSize: 512)
        #expect(captured.exportedBatches.isEmpty)
    }
}

final class CapturingSpanExporter: SpanExporter, @unchecked Sendable {
    private(set) var exportedBatches: [[SpanData]] = []
    func export(spans: [SpanData], explicitTimeout: TimeInterval?) -> SpanExporterResultCode {
        exportedBatches.append(spans)
        return .success
    }
    func flush(explicitTimeout: TimeInterval?) -> SpanExporterResultCode { .success }
    func shutdown(explicitTimeout: TimeInterval?) {}
}
```

- [ ] **Step 2: Run — verify they fail**

Run: `cd otel-ios-mobile && ./run-tests.sh 2>&1 | head -10`
Expected: compile error on `recoverSpans`.

- [ ] **Step 3: Add `recoverSpans` helper + extend the recovery block**

Add to `OTelMobile.swift` (as a static method on `OTelMobile`):

```swift
    /// Drains all persisted spans through `exporter` in `batchSize` chunks.
    /// Read-then-conditionally-delete: rows are only removed from disk
    /// after a successful export. Mirrors `MobileLogRecordProcessor.recoverFromDisk`.
    ///
    /// Public for testability. Called from the `Task.detached` recovery
    /// block inside `start()`.
    public static func recoverSpans(
        from buffer: DiskSpanBuffer,
        exporter: SpanExporter,
        batchSize: Int
    ) async {
        while true {
            let batch = await buffer.fetchAll(limit: batchSize)
            if batch.isEmpty { break }
            let spans = batch.compactMap { $0.record }
            guard !spans.isEmpty else { break }
            let result = exporter.export(spans: spans, explicitTimeout: 10)
            guard result == .success, let anchor = batch.last?.id else { break }
            await buffer.deleteUpTo(id: anchor)
        }
    }
```

Extend the existing `Task.detached` recovery block (the one that starts around line 458 with `if diskBuffer != nil { Task.detached { ... } }`). Replace that entire block with:

```swift
            if diskBuffer != nil || spanDiskBuffer != nil {
                Task.detached { [bufferProcessor, logger, spanDiskBuffer, otlpTraceExporter] in
                    let logStats = await bufferProcessor.diskStats()
                    let spanStats: (count: Int, bytes: Int)? = await {
                        guard let b = spanDiskBuffer else { return nil }
                        let count = await b.rowCount()
                        guard count > 0 else { return nil }
                        let bytes = await b.totalSizeBytes()
                        return (count, bytes)
                    }()
                    let logCount = logStats?.count ?? 0
                    let spanCount = spanStats?.count ?? 0
                    guard logCount > 0 || spanCount > 0 else { return }

                    var attrs: [String: AttributeValue] = [:]
                    if let s = logStats, s.count > 0 {
                        attrs["dash0.recovery.event_count"] = .int(s.count)
                        attrs["dash0.recovery.bytes_pending"] = .int(s.bytes)
                    }
                    if let s = spanStats {
                        attrs["dash0.recovery.span_count"] = .int(s.count)
                        attrs["dash0.recovery.span_bytes_pending"] = .int(s.bytes)
                    }
                    logger.logRecordBuilder()
                        .setBody(AttributeValue.string("app.recovery_start"))
                        .setSeverity(.info)
                        .setAttributes(attrs)
                        .emit()

                    if logCount > 0 {
                        _ = await bufferProcessor.recoverFromDisk()
                    }
                    if let b = spanDiskBuffer, spanCount > 0 {
                        await OTelMobile.recoverSpans(
                            from: b,
                            exporter: otlpTraceExporter,
                            batchSize: 512)
                    }
                }
            }
```

- [ ] **Step 4: Run — verify pass**

Run: `cd otel-ios-mobile && ./run-tests.sh 2>&1 | tail -15`
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add otel-ios-mobile/Sources/OTelMobileSDK/OTelMobile.swift \
        otel-ios-mobile/Tests/OTelMobileSDKTests/OTelMobileSpanRecoveryTests.swift
git commit -m "feat(ios): recovery loop drains persisted spans; additive marker attrs"
```

---

## Task 9: RN iOS demo wiring — open both disk buffers

**Files:**

- Modify: `examples/upstream-demo-app-rn/AstronomyShopRN/ios/AstronomyShopRN/OTelMobileCallSink.swift`

Current state: the sink builds `MobileConfig` and calls `OTelMobile.start(config:)` with no disk buffer. We need to open both `DiskLogBuffer` and `DiskSpanBuffer` before that call. Both inits are `async`; the sink method is sync → semaphore bridge.

- [ ] **Step 1: Add the semaphore-bridged async buffer open**

In `OTelMobileCallSink.swift`, just before the existing `let mobileConfig = MobileConfig(...)` line, insert:

```swift
        // Open dual disk buffers (logs + spans) synchronously via a
        // DispatchSemaphore bridge. The sink's start() is synchronous,
        // but DiskLogBuffer / DiskSpanBuffer are actors with async init.
        // Same pattern iOS native AstronomyShop uses in ShopBootstrap.swift.
        let diskBaseDir = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("io.dash0.mobile")
        try? FileManager.default.createDirectory(
            at: diskBaseDir, withIntermediateDirectories: true)
        let logDbPath = diskBaseDir.appendingPathComponent("buffer.db")
        let spanDbPath = diskBaseDir.appendingPathComponent("span-buffer.db")

        var logBuffer: DiskLogBuffer?
        var spanBuffer: DiskSpanBuffer?
        let openSemaphore = DispatchSemaphore(value: 0)
        Task {
            logBuffer = try? await DiskLogBuffer(dbPath: logDbPath)
            spanBuffer = try? await DiskSpanBuffer(dbPath: spanDbPath)
            openSemaphore.signal()
        }
        _ = openSemaphore.wait(timeout: .now() + 5)
```

- [ ] **Step 2: Pass the buffers into `OTelMobile.start`**

Locate the existing `otel = try OTelMobile.start(config: mobileConfig)` call and change it to:

```swift
            otel = try OTelMobile.start(
                config: mobileConfig,
                diskBuffer: logBuffer,
                spanDiskBuffer: spanBuffer)
```

- [ ] **Step 3: Build the iOS app**

Run: `cd examples/upstream-demo-app-rn/AstronomyShopRN/ios && DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild -workspace AstronomyShopRN.xcworkspace -scheme AstronomyShopRN -configuration Release -sdk iphonesimulator -destination 'platform=iOS Simulator,id=65F2CFA7-B5AA-4C37-8A39-A9235CA4FBFE' -derivedDataPath build build 2>&1 | tail -5`
Expected: `** BUILD SUCCEEDED **`.

- [ ] **Step 4: Commit**

```bash
git add examples/upstream-demo-app-rn/AstronomyShopRN/ios/AstronomyShopRN/OTelMobileCallSink.swift
git commit -m "feat(rn-ios-demo): wire DiskLogBuffer + DiskSpanBuffer via semaphore-bridged async init"
```

---

## Task 10: Device validation (Gate 4 on RN iOS)

**Files:** none (procedural; captures evidence for Task 11).

- [ ] **Step 1: Stage the invalid endpoint config**

Current demo `otel-config.json` has the real Dash0 endpoint. Save a copy + write an invalid variant:

```bash
cp examples/upstream-demo-app-rn/AstronomyShopRN/otel-config.json /tmp/otel-config.real.json
python3 -c "
import json
c = json.load(open('/tmp/otel-config.real.json'))
c['endpoint'] = 'https://ingress-offline-test.invalid:4318'
json.dump(c, open('/tmp/otel-config.invalid.json','w'), indent=2)
print('wrote invalid config')
"
```

- [ ] **Step 2: Swap to invalid, rebuild, install, launch**

```bash
cp /tmp/otel-config.invalid.json \
   examples/upstream-demo-app-rn/AstronomyShopRN/otel-config.json
cd examples/upstream-demo-app-rn/AstronomyShopRN
/opt/homebrew/bin/node node_modules/react-native/cli.js bundle \
  --platform ios --dev false --entry-file index.js \
  --bundle-output ios/main.jsbundle --assets-dest ios
cd ios
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  xcodebuild -workspace AstronomyShopRN.xcworkspace -scheme AstronomyShopRN \
  -configuration Release -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,id=65F2CFA7-B5AA-4C37-8A39-A9235CA4FBFE' \
  -derivedDataPath build build
APP=build/Build/Products/Release-iphonesimulator/AstronomyShopRN.app
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  xcrun simctl install 65F2CFA7-B5AA-4C37-8A39-A9235CA4FBFE "$APP"
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  xcrun simctl launch 65F2CFA7-B5AA-4C37-8A39-A9235CA4FBFE \
  org.reactjs.native.example.AstronomyShopRN
```

- [ ] **Step 3: Drive UI for ~30s to generate spans**

`open -a Simulator` then manually tap product rows / navigate for 30 seconds so ShopTelemetry + pokeBackend spans are emitted. Every export will fail → every span persists.

- [ ] **Step 4: Terminate + verify disk has spans**

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  xcrun simctl terminate 65F2CFA7-B5AA-4C37-8A39-A9235CA4FBFE \
  org.reactjs.native.example.AstronomyShopRN

DATA=$(DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
  xcrun simctl get_app_container 65F2CFA7-B5AA-4C37-8A39-A9235CA4FBFE \
  org.reactjs.native.example.AstronomyShopRN data)
SPAN_DB="$DATA/Library/Application Support/io.dash0.mobile/span-buffer.db"
sqlite3 "$SPAN_DB" "SELECT COUNT(*) FROM buffered_spans;"
```

Expected: positive integer. Record this number as N.

- [ ] **Step 5: Swap back to real endpoint, rebuild, install, launch**

```bash
cp /tmp/otel-config.real.json \
   examples/upstream-demo-app-rn/AstronomyShopRN/otel-config.json
# Re-run Step 2 build commands.
```

- [ ] **Step 6: Query Dash0 for recovery marker + spans**

```bash
sleep 60
dash0 -X logs query \
  --filter "service.name is otel-rn-astronomy-shop" \
  --from now-10m -o json > /tmp/gate4-marker.json
python3 -c "
import json
d = json.load(open('/tmp/gate4-marker.json'))
for res in d.get('resourceLogs', []):
    for sl in res.get('scopeLogs', []):
        for lr in sl.get('logRecords', []):
            body = (lr.get('body',{}).get('stringValue') or '')
            if body == 'app.recovery_start':
                attrs = {a['key']: (a['value'].get('intValue') or a['value'].get('stringValue'))
                         for a in lr.get('attributes',[])}
                print('MARKER:', attrs)
"
```

Expected: a `MARKER: {...}` dict containing `dash0.recovery.span_count=N`.

```bash
dash0 -X spans query \
  --filter "service.name is otel-rn-astronomy-shop" \
  --from now-30m -o json > /tmp/gate4-spans.json
python3 -c "
import json
d = json.load(open('/tmp/gate4-spans.json'))
total = 0
for res in d.get('resourceSpans', []):
    for ss in res.get('scopeSpans', []):
        total += len(ss.get('spans', []))
print('span count:', total)
"
```

Expected: span count matches N.

- [ ] **Step 7: Verify disk is drained**

```bash
sqlite3 "$SPAN_DB" "SELECT COUNT(*) FROM buffered_spans;"
```

Expected: 0.

- [ ] **Step 8: Capture evidence**

Record the marker attrs, the span count, and the post-drain row count. Used in Task 11 to update the epic doc.

No code commit in this task.

---

## Task 11: Update epic doc

**Files:**

- Modify: `docs/epics/VALIDATION_MATRIX_EPIC.md`

- [ ] **Step 1: Flip Gate 4 🔴 → 🟢**

Locate the RN iOS row in the matrix table. Replace the Gate 4 cell (currently `🔴 **span path has no disk persist** — ...`) with:

```markdown
🟢 **verified <YYYY-MM-DD>** N spans persisted during invalid-endpoint window, all re-exported on reconnect with original timestamps; `app.recovery_start` marker emitted with `dash0.recovery.span_count=N`. Disk-persist via `PersistingSpanExporter` + `DiskSpanBuffer` (commits <sha1>, <sha2>)
```

Substitute `<YYYY-MM-DD>` with the validation date, `N` with the actual span count from Task 10, and the `<shaN>` values with the 7-char SHAs of the Task 6 and Task 8 commits.

- [ ] **Step 2: Mark the Gate 4 follow-up done**

Find the line starting `- [ ] Gate 4 fix: extend disk-persist-on-failure from logs to spans ...` and replace with:

```markdown
- [x] Gate 4 fix: PersistingSpanExporter + DiskSpanBuffer + recovery loop at OTelMobile.start. RN iOS demo wires both DiskLogBuffer and DiskSpanBuffer. Device-verified on iPhone 17 Sim iOS 26.4.
```

- [ ] **Step 3: Bump the RN iOS summary**

Replace `### RN iOS (AstronomyShopRN) — 2 of 4 gates green` with `### RN iOS (AstronomyShopRN) — 3 of 4 gates green`. Update the paragraph that follows to reflect Gate 1 as the only remaining red.

- [ ] **Step 4: Rewrite the Gate 4 matchy-matchy detail block**

Replace the existing Gate 4 detail block (starts with `**Gate 4 — Offline 🔴**`) with:

```markdown
**Gate 4 — Offline 🟢**

Flow post-fix:
1. App offline (endpoint is *.invalid); user drives UI for 30s.
2. BSP emits span batches → PersistingSpanExporter.export →
   OTLP .failure → DiskSpanBuffer.persist(spans) (dedup on
   traceId+spanId).
3. App terminates. Disk holds N span rows in buffered_spans.
4. Relaunch with real endpoint. OTelMobile.start's Task.detached
   recovery reads logStats + spanStats, emits combined
   app.recovery_start marker (dash0.recovery.span_count=N among
   its attributes), then OTelMobile.recoverSpans drains the disk
   in 512-row batches via otlpTraceExporter, deleting on each
   success.

Evidence: see Task 10 of the implementation plan.
```

- [ ] **Step 5: Commit**

```bash
git add docs/epics/VALIDATION_MATRIX_EPIC.md
git commit -m "docs: RN iOS Gate 4 🟢 — span disk-persist + recovery shipped"
```

---

## Self-review checklist

- **Spec coverage:** every spec section maps to a task. Architecture diagram → Tasks 7, 8. BufferedSpan → Task 1. DiskSpanBuffer API surface → Tasks 2, 3, 4, 5. PersistingSpanExporter → Task 6. Recovery flow → Task 8. Demo wiring → Task 9. Device validation → Task 10. Epic update → Task 11.
- **Type consistency:** `persist(_ spans:, sessionId:)` signature identical in Tasks 3, 6, 9. `deleteUpTo(id:)` identical in Tasks 4, 8. `BufferedSpan.id` field used consistently across Tasks 1, 4, 8. `SQLITE_TRANSIENT_FN` helper declared once in Task 2 and referenced in Task 3.
- **No placeholders:** every step has exact code or exact commands. Task 7 Step 1 explicitly handles the two possible `RetryableExporter` shapes inline.
- **Test-first:** Tasks 2, 3, 4, 5, 6, 8 each start with a failing test before the implementation.
- **Commit cadence:** 11 commits total; each self-contained and passes tests on its own.

---

## Execution handoff

Plan complete and committed. Two execution options:

1. **Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — execute in this session using executing-plans, batch execution with checkpoints.

Which approach?
