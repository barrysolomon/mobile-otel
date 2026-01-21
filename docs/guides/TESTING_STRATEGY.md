# Testing Strategy for OpenTelemetry Native Mobile Observability

## Overview

This document outlines a comprehensive testing strategy for the OTEL-native mobile observability system, covering unit tests, integration tests, and end-to-end tests.

---

## 🎯 Testing Principles

1. **Testability by Design**: All components designed with dependency injection
2. **Fast Feedback**: Unit tests run in <5 seconds
3. **Isolation**: Mock external dependencies (network, disk, time)
4. **Coverage**: Target >80% code coverage
5. **Realistic**: Integration tests use real OTEL Collector
6. **Automated**: All tests run in CI/CD pipeline

---

## 📊 Testing Pyramid

```
                    ┌─────────────────┐
                    │   E2E Tests     │  <-- 5% (Full system)
                    │   ~10 tests     │
                    └─────────────────┘
                  ┌───────────────────────┐
                  │  Integration Tests    │  <-- 15% (Component pairs)
                  │    ~30 tests          │
                  └───────────────────────┘
              ┌─────────────────────────────────┐
              │       Unit Tests                │  <-- 80% (Individual units)
              │       ~200 tests                │
              └─────────────────────────────────┘
```

---

## 1️⃣ Unit Tests

### Android Library Unit Tests

#### MobileLoggerProvider Tests
**File**: `otel-android-mobile/src/test/java/io/opentelemetry/android/mobile/MobileLoggerProviderTest.kt`

**What to Test**:
- ✅ Initialization with valid config
- ✅ Initialization with invalid config (should fail)
- ✅ Singleton behavior
- ✅ Device ID generation and persistence
- ✅ Resource attribute configuration
- ✅ Logger creation
- ✅ Force flush behavior
- ✅ Shutdown behavior

**Testing Approach**:
```kotlin
@RunWith(RobolectricTestRunner::class)
class MobileLoggerProviderTest {

    private lateinit var context: Context
    private lateinit var config: MobileConfig

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        config = MobileConfig(
            serviceName = "test-service",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317"
        )
    }

    @Test
    fun `initialization creates valid provider`() {
        val provider = MobileLoggerProvider.getInstance(context, config)
        assertNotNull(provider)
        assertNotNull(provider.getDeviceId())
    }

    @Test
    fun `device ID persists across restarts`() {
        val provider1 = MobileLoggerProvider.getInstance(context, config)
        val deviceId1 = provider1.getDeviceId()

        // Simulate app restart
        MobileLoggerProvider::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, null)
        }

        val provider2 = MobileLoggerProvider.getInstance(context, config)
        val deviceId2 = provider2.getDeviceId()

        assertEquals(deviceId1, deviceId2)
    }

    @Test
    fun `force flush completes successfully`() {
        val provider = MobileLoggerProvider.getInstance(context, config)
        val result = provider.forceFlush(5)
        assertTrue(result.isSuccess)
    }
}
```

**Mocking Strategy**:
- Use Robolectric for Android context
- Mock OTLP exporter with test implementation
- Mock SharedPreferences for device ID storage

---

#### MobileLogRecordProcessor Tests
**File**: `otel-android-mobile/src/test/java/io/opentelemetry/android/mobile/buffering/MobileLogRecordProcessorTest.kt`

**What to Test**:
- ✅ onEmit adds to RAM buffer
- ✅ RAM buffer overflow triggers disk persistence
- ✅ Policy evaluation triggers flush
- ✅ Time window flushing
- ✅ Force flush exports all events
- ✅ Shutdown behavior
- ✅ Thread safety (concurrent onEmit calls)
- ✅ Buffer statistics

**Testing Approach**:
```kotlin
@RunWith(RobolectricTestRunner::class)
class MobileLogRecordProcessorTest {

    private lateinit var context: Context
    private lateinit var mockExporter: MockLogRecordExporter
    private lateinit var mockDiskBuffer: MockDiskLogBuffer
    private lateinit var mockPolicyEvaluator: MockPolicyEvaluator
    private lateinit var processor: MobileLogRecordProcessor

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        mockExporter = MockLogRecordExporter()
        mockDiskBuffer = MockDiskLogBuffer()
        mockPolicyEvaluator = MockPolicyEvaluator()

        processor = MobileLogRecordProcessor.builder(context)
            .setExporter(mockExporter)
            .setRamBufferSize(100) // Small for testing
            .setCollectorEndpoint("http://localhost:4317")
            .build()
    }

    @Test
    fun `onEmit adds log to RAM buffer`() {
        val logRecord = createTestLogRecord("test.event")

        processor.onEmit(Context.root(), logRecord)

        val stats = processor.getBufferStats()
        assertEquals(1, stats.ramBufferSize)
    }

    @Test
    fun `RAM buffer overflow triggers disk persistence`() {
        // Fill RAM buffer beyond capacity
        repeat(150) { i ->
            processor.onEmit(Context.root(), createTestLogRecord("event.$i"))
        }

        // Wait for async overflow
        Thread.sleep(1000)

        val stats = processor.getBufferStats()
        assertTrue(stats.ramBufferSize <= 100)
        assertTrue(stats.diskBufferSize > 0)
    }

    @Test
    fun `concurrent onEmit calls are thread safe`() {
        val threads = (1..10).map { threadId ->
            thread {
                repeat(100) { i ->
                    processor.onEmit(
                        Context.root(),
                        createTestLogRecord("thread.$threadId.event.$i")
                    )
                }
            }
        }

        threads.forEach { it.join() }

        val stats = processor.getBufferStats()
        assertEquals(1000, stats.ramBufferSize + stats.diskBufferSize)
    }

    @Test
    fun `force flush exports all events`() {
        repeat(50) { i ->
            processor.onEmit(Context.root(), createTestLogRecord("event.$i"))
        }

        val result = processor.forceFlush()
        assertTrue(result.isSuccess)

        assertEquals(50, mockExporter.exportedLogs.size)
    }

    private fun createTestLogRecord(body: String): LogRecordData {
        return LogRecordData.builder()
            .setBody(body)
            .setTimestampEpochNanos(System.currentTimeMillis() * 1_000_000)
            .build()
    }
}

// Mock implementations for testing
class MockLogRecordExporter : LogRecordExporter {
    val exportedLogs = mutableListOf<LogRecordData>()

    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
        exportedLogs.addAll(logs)
        return CompletableResultCode.ofSuccess()
    }

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}
```

**Key Testing Patterns**:
- **Dependency Injection**: Pass mock exporter instead of real OTLP exporter
- **Time Control**: Use fixed timestamps for time window tests
- **Async Verification**: Wait for background tasks with timeouts
- **Thread Safety**: Use concurrent threads to verify lock-free operations

---

#### DiskLogBuffer Tests
**File**: `otel-android-mobile/src/test/java/io/opentelemetry/android/mobile/buffering/DiskLogBufferTest.kt`

**What to Test**:
- ✅ Event persistence
- ✅ Time window queries
- ✅ TTL-based cleanup
- ✅ Size-based eviction
- ✅ Database migration
- ✅ Crash recovery (database corruption)

**Testing Approach**:
```kotlin
@RunWith(AndroidJUnit4::class)
class DiskLogBufferTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var context: Context
    private lateinit var database: LogDatabase
    private lateinit var diskBuffer: DiskLogBuffer

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Use in-memory database for testing
        database = Room.inMemoryDatabaseBuilder(
            context,
            LogDatabase::class.java
        ).allowMainThreadQueries().build()

        diskBuffer = DiskLogBuffer(context, maxSizeMb = 10, ttlHours = 24)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `persistEvents stores logs in database`() = runBlocking {
        val logs = listOf(
            createTestLogRecord("event.1"),
            createTestLogRecord("event.2")
        )

        diskBuffer.persistEvents(logs)

        val count = diskBuffer.getEventCount()
        assertEquals(2, count)
    }

    @Test
    fun `getEventsInWindow returns correct events`() = runBlocking {
        val now = System.currentTimeMillis()
        val twoMinutesAgo = now - (2 * 60 * 1000)

        // Persist events with different timestamps
        diskBuffer.persistEvents(listOf(
            createTestLogRecord("old.event", twoMinutesAgo - 60_000),
            createTestLogRecord("recent.event", twoMinutesAgo + 60_000)
        ))

        val events = diskBuffer.getEventsInWindow(twoMinutesAgo)

        assertEquals(1, events.size)
        assertEquals("recent.event", events[0].body.asString())
    }

    @Test
    fun `cleanup removes expired events`() = runBlocking {
        val now = System.currentTimeMillis()
        val expired = now - (25 * 60 * 60 * 1000) // 25 hours ago

        diskBuffer.persistEvents(listOf(
            createTestLogRecord("expired.event", expired),
            createTestLogRecord("fresh.event", now)
        ))

        diskBuffer.cleanup()

        val count = diskBuffer.getEventCount()
        assertEquals(1, count)
    }

    @Test
    fun `size limit enforcement removes oldest events`() = runBlocking {
        // Create buffer with very small size limit
        val smallBuffer = DiskLogBuffer(context, maxSizeMb = 1, ttlHours = 24)

        // Persist many events to exceed size
        repeat(10000) { i ->
            smallBuffer.persistEvents(listOf(createTestLogRecord("event.$i")))
        }

        // Verify size is bounded
        val dbFile = context.getDatabasePath("otel_log_buffer.db")
        val sizeMb = dbFile.length() / (1024.0 * 1024.0)
        assertTrue(sizeMb <= 2.0) // Some overhead allowed
    }
}
```

**Testability Features**:
- In-memory database for fast tests
- Dependency injection for Room database
- Fixed timestamps for deterministic tests
- InstantTaskExecutorRule for coroutine testing

---

#### PolicyEvaluator Tests
**File**: `otel-android-mobile/src/test/java/io/opentelemetry/android/mobile/policy/PolicyEvaluatorTest.kt`

**What to Test**:
- ✅ Policy matching (equals, gt, lt, contains, regex)
- ✅ Logical operators (and, or)
- ✅ Config fetching and parsing
- ✅ Config refresh
- ✅ Network failure handling
- ✅ Invalid config handling

**Testing Approach**:
```kotlin
class PolicyEvaluatorTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var evaluator: PolicyEvaluator

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()

        evaluator = PolicyEvaluator(
            collectorEndpoint = mockWebServer.url("/").toString(),
            configPollIntervalSeconds = 3600 // Don't poll during test
        )
    }

    @After
    fun tearDown() {
        evaluator.shutdown()
        mockWebServer.shutdown()
    }

    @Test
    fun `equals condition matches correctly`() {
        val policy = Policy(
            id = "test-policy",
            enabled = true,
            match = Match(
                logicalOperator = "and",
                attributes = mapOf(
                    "event.name" to Condition(equals = "ui.freeze")
                )
            ),
            actions = Actions(flushWindowMinutes = 2)
        )

        val logRecord = createTestLogRecord("ui.freeze")
        val result = evaluator.evaluatePolicy(logRecord, policy)

        assertNotNull(result)
        assertEquals("test-policy", result.policyId)
    }

    @Test
    fun `gt condition with numeric value`() {
        val policy = Policy(
            id = "threshold-policy",
            enabled = true,
            match = Match(
                logicalOperator = "and",
                attributes = mapOf(
                    "duration_ms" to Condition(gt = 2000.0)
                )
            ),
            actions = Actions(flushWindowMinutes = 2)
        )

        val logRecord = createTestLogRecord("event")
            .toBuilder()
            .setAttribute(AttributeKey.longKey("duration_ms"), 2500L)
            .build()

        val result = evaluator.evaluatePolicy(logRecord, policy)
        assertNotNull(result)
    }

    @Test
    fun `and operator requires all conditions`() {
        val policy = Policy(
            id = "and-policy",
            enabled = true,
            match = Match(
                logicalOperator = "and",
                attributes = mapOf(
                    "event.name" to Condition(equals = "ui.freeze"),
                    "duration_ms" to Condition(gt = 2000.0)
                )
            ),
            actions = Actions(flushWindowMinutes = 2)
        )

        // Only one condition matches
        val logRecord1 = createTestLogRecord("ui.freeze")
        assertNull(evaluator.evaluatePolicy(logRecord1, policy))

        // Both conditions match
        val logRecord2 = createTestLogRecord("ui.freeze")
            .toBuilder()
            .setAttribute(AttributeKey.longKey("duration_ms"), 2500L)
            .build()
        assertNotNull(evaluator.evaluatePolicy(logRecord2, policy))
    }

    @Test
    fun `config fetching and parsing`() {
        val configJson = """
        {
          "workflows": [
            {
              "id": "test-workflow",
              "enabled": true,
              "nodes": {
                "trigger": [{
                  "data": {
                    "match": {
                      "logical_operator": "and",
                      "attributes": {
                        "event.name": {"equals": "ui.freeze"}
                      }
                    }
                  }
                }],
                "action": [{
                  "data": {
                    "flush_window_minutes": 2
                  }
                }]
              }
            }
          ]
        }
        """.trimIndent()

        mockWebServer.enqueue(MockResponse().setBody(configJson))

        // Trigger config fetch
        evaluator.fetchConfig()
        Thread.sleep(500) // Wait for async fetch

        val logRecord = createTestLogRecord("ui.freeze")
        val result = evaluator.evaluate(logRecord)

        assertNotNull(result)
        assertEquals("test-workflow", result.policyId)
    }

    @Test
    fun `network failure handling`() {
        mockWebServer.enqueue(MockResponse().setResponseCode(500))

        // Should not crash
        evaluator.fetchConfig()
        Thread.sleep(500)

        // Should return null (no config loaded)
        val logRecord = createTestLogRecord("ui.freeze")
        val result = evaluator.evaluate(logRecord)
        assertNull(result)
    }
}
```

**Testability Features**:
- MockWebServer for HTTP mocking
- Expose internal methods for testing (make them internal/visible for testing)
- Disable periodic refresh during tests
- Test each condition operator independently

---

### Collector Processor Unit Tests

#### Processor Tests
**File**: `collector-processor/mobilepolicyprocessor/processor_test.go`

**What to Test**:
- ✅ Policy evaluation logic
- ✅ Condition matching (all operators)
- ✅ Logical operators (and, or)
- ✅ Log annotation
- ✅ Attribute extraction
- ✅ ConsumeLogs processing

**Testing Approach**:
```go
package mobilepolicyprocessor

import (
	"context"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.opentelemetry.io/collector/consumer/consumertest"
	"go.opentelemetry.io/collector/pdata/pcommon"
	"go.opentelemetry.io/collector/pdata/plog"
	"go.uber.org/zap/zaptest"
)

func TestPolicyEvaluation(t *testing.T) {
	tests := []struct {
		name          string
		policy        Policy
		logRecord     plog.LogRecord
		shouldMatch   bool
	}{
		{
			name: "equals condition matches",
			policy: Policy{
				ID:      "test-policy",
				Enabled: true,
				Match: Match{
					LogicalOperator: "and",
					Attributes: map[string]Condition{
						"event.name": {Equals: stringPtr("ui.freeze")},
					},
				},
			},
			logRecord:   createTestLogRecord("ui.freeze", nil),
			shouldMatch: true,
		},
		{
			name: "equals condition does not match",
			policy: Policy{
				ID:      "test-policy",
				Enabled: true,
				Match: Match{
					LogicalOperator: "and",
					Attributes: map[string]Condition{
						"event.name": {Equals: stringPtr("ui.freeze")},
					},
				},
			},
			logRecord:   createTestLogRecord("other.event", nil),
			shouldMatch: false,
		},
		{
			name: "gt condition matches",
			policy: Policy{
				ID:      "threshold-policy",
				Enabled: true,
				Match: Match{
					LogicalOperator: "and",
					Attributes: map[string]Condition{
						"duration_ms": {Gt: float64Ptr(2000.0)},
					},
				},
			},
			logRecord: createTestLogRecord("event", map[string]interface{}{
				"duration_ms": int64(2500),
			}),
			shouldMatch: true,
		},
		{
			name: "and operator requires all conditions",
			policy: Policy{
				ID:      "and-policy",
				Enabled: true,
				Match: Match{
					LogicalOperator: "and",
					Attributes: map[string]Condition{
						"event.name":  {Equals: stringPtr("ui.freeze")},
						"duration_ms": {Gt: float64Ptr(2000.0)},
					},
				},
			},
			logRecord: createTestLogRecord("ui.freeze", map[string]interface{}{
				"duration_ms": int64(1500), // Below threshold
			}),
			shouldMatch: false,
		},
		{
			name: "or operator matches if any condition matches",
			policy: Policy{
				ID:      "or-policy",
				Enabled: true,
				Match: Match{
					LogicalOperator: "or",
					Attributes: map[string]Condition{
						"event.name":  {Equals: stringPtr("ui.freeze")},
						"duration_ms": {Gt: float64Ptr(2000.0)},
					},
				},
			},
			logRecord: createTestLogRecord("ui.freeze", map[string]interface{}{
				"duration_ms": int64(1500), // Below threshold but name matches
			}),
			shouldMatch: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			logger := zaptest.NewLogger(t)
			nextConsumer := consumertest.NewNop()

			processor, err := newMobilePolicyProcessor(
				&Config{Policies: []Policy{tt.policy}},
				logger,
				nextConsumer,
			)
			require.NoError(t, err)

			// Extract attributes for evaluation
			attrs := make(map[string]interface{})
			attrs["event.name"] = tt.logRecord.Body().AsString()
			tt.logRecord.Attributes().Range(func(k string, v pcommon.Value) bool {
				attrs[k] = convertValue(v)
				return true
			})

			result := processor.evaluatePolicy(tt.policy, attrs)
			assert.Equal(t, tt.shouldMatch, result)
		})
	}
}

func TestLogAnnotation(t *testing.T) {
	policy := Policy{
		ID:      "annotate-policy",
		Enabled: true,
		Match: Match{
			LogicalOperator: "and",
			Attributes: map[string]Condition{
				"event.name": {Equals: stringPtr("ui.freeze")},
			},
		},
		Actions: []Action{
			{
				Type: "annotate",
				Parameters: map[string]interface{}{
					"trigger_id": "ui-freeze-handler",
					"reason":     "UI freeze detected",
				},
			},
		},
	}

	logger := zaptest.NewLogger(t)
	nextConsumer := consumertest.NewNop()

	processor, err := newMobilePolicyProcessor(
		&Config{Policies: []Policy{policy}},
		logger,
		nextConsumer,
	)
	require.NoError(t, err)

	logRecord := createTestLogRecord("ui.freeze", nil)
	resourceAttrs := pcommon.NewMap()

	processor.processLogRecord(logRecord, resourceAttrs)

	// Verify annotations were added
	matched, _ := logRecord.Attributes().Get("policy.matched")
	assert.Equal(t, "true", matched.Str())

	policyID, _ := logRecord.Attributes().Get("policy.id")
	assert.Equal(t, "annotate-policy", policyID.Str())

	triggerID, _ := logRecord.Attributes().Get("policy.trigger_id")
	assert.Equal(t, "ui-freeze-handler", triggerID.Str())
}

func TestConsumeLogs(t *testing.T) {
	policy := Policy{
		ID:      "test-policy",
		Enabled: true,
		Match: Match{
			LogicalOperator: "and",
			Attributes: map[string]Condition{
				"event.name": {Equals: stringPtr("ui.freeze")},
			},
		},
		Actions: []Action{
			{Type: "annotate", Parameters: map[string]interface{}{"reason": "test"}},
		},
	}

	logger := zaptest.NewLogger(t)
	sink := &consumertest.LogsSink{}

	processor, err := newMobilePolicyProcessor(
		&Config{Policies: []Policy{policy}},
		logger,
		sink,
	)
	require.NoError(t, err)

	// Create test logs
	logs := plog.NewLogs()
	resourceLog := logs.ResourceLogs().AppendEmpty()
	scopeLog := resourceLog.ScopeLogs().AppendEmpty()
	logRecord := scopeLog.LogRecords().AppendEmpty()
	logRecord.Body().SetStr("ui.freeze")

	// Process logs
	err = processor.ConsumeLogs(context.Background(), logs)
	require.NoError(t, err)

	// Verify logs were passed to next consumer
	assert.Equal(t, 1, sink.LogRecordCount())

	// Verify annotation was added
	receivedLogs := sink.AllLogs()[0]
	receivedLogRecord := receivedLogs.ResourceLogs().At(0).ScopeLogs().At(0).LogRecords().At(0)
	matched, _ := receivedLogRecord.Attributes().Get("policy.matched")
	assert.Equal(t, "true", matched.Str())
}

// Helper functions

func createTestLogRecord(body string, attributes map[string]interface{}) plog.LogRecord {
	lr := plog.NewLogRecord()
	lr.Body().SetStr(body)
	lr.SetTimestamp(pcommon.NewTimestampFromTime(time.Now()))

	if attributes != nil {
		for k, v := range attributes {
			switch val := v.(type) {
			case string:
				lr.Attributes().PutStr(k, val)
			case int64:
				lr.Attributes().PutInt(k, val)
			case float64:
				lr.Attributes().PutDouble(k, val)
			case bool:
				lr.Attributes().PutBool(k, val)
			}
		}
	}

	return lr
}

func stringPtr(s string) *string {
	return &s
}

func float64Ptr(f float64) *float64 {
	return &f
}
```

**Testability Features**:
- Use `consumertest.NewNop()` for next consumer
- Use `consumertest.LogsSink` to capture output
- Test each operator independently
- Use table-driven tests for comprehensive coverage

---

#### Config Validation Tests
**File**: `collector-processor/mobilepolicyprocessor/config_test.go`

```go
func TestConfigValidation(t *testing.T) {
	tests := []struct {
		name        string
		config      Config
		expectError bool
		errorMsg    string
	}{
		{
			name: "valid config",
			config: Config{
				Policies: []Policy{
					{
						ID:      "test-policy",
						Enabled: true,
						Match: Match{
							LogicalOperator: "and",
							Attributes: map[string]Condition{
								"event.name": {Equals: stringPtr("test")},
							},
						},
						Actions: []Action{{Type: "annotate"}},
					},
				},
			},
			expectError: false,
		},
		{
			name: "empty policies",
			config: Config{
				Policies: []Policy{},
			},
			expectError: true,
			errorMsg:    "at least one policy must be defined",
		},
		{
			name: "missing policy ID",
			config: Config{
				Policies: []Policy{
					{
						ID:      "",
						Enabled: true,
						Match:   Match{LogicalOperator: "and", Attributes: map[string]Condition{}},
						Actions: []Action{{Type: "annotate"}},
					},
				},
			},
			expectError: true,
			errorMsg:    "id is required",
		},
		{
			name: "invalid logical operator",
			config: Config{
				Policies: []Policy{
					{
						ID:      "test",
						Enabled: true,
						Match: Match{
							LogicalOperator: "xor", // Invalid
							Attributes:      map[string]Condition{"key": {Equals: stringPtr("val")}},
						},
						Actions: []Action{{Type: "annotate"}},
					},
				},
			},
			expectError: true,
			errorMsg:    "logical_operator must be 'and' or 'or'",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := tt.config.Validate()
			if tt.expectError {
				assert.Error(t, err)
				assert.Contains(t, err.Error(), tt.errorMsg)
			} else {
				assert.NoError(t, err)
			}
		})
	}
}
```

---

## 2️⃣ Integration Tests

### Android Integration Tests

#### End-to-End Buffer Flow
**File**: `otel-android-mobile/src/androidTest/java/io/opentelemetry/android/mobile/integration/BufferIntegrationTest.kt`

```kotlin
@RunWith(AndroidJUnit4::class)
@LargeTest
class BufferIntegrationTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(TestActivity::class.java)

    private lateinit var provider: MobileLoggerProvider
    private lateinit var logger: Logger
    private val exportedLogs = mutableListOf<LogRecordData>()

    @Before
    fun setup() {
        val config = MobileConfig(
            serviceName = "integration-test",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:4317"
        )

        provider = MobileLoggerProvider.getInstance(
            ApplicationProvider.getApplicationContext(),
            config
        )
        logger = provider.get("integration-test")
    }

    @Test
    fun testRAMBufferToExport() {
        // Emit logs
        repeat(10) { i ->
            logger.logRecordBuilder()
                .setBody("test.event.$i")
                .emit()
        }

        // Force flush
        val result = provider.forceFlush(10)
        assertTrue(result.isSuccess)

        // Verify logs were exported (would check mock exporter)
    }

    @Test
    fun testDiskPersistenceAfterOverflow() {
        // Emit many logs to trigger overflow
        repeat(6000) { i ->
            logger.logRecordBuilder()
                .setBody("overflow.event.$i")
                .emit()
        }

        // Wait for overflow
        Thread.sleep(2000)

        // Force flush and verify all events exported
        provider.forceFlush(30)

        // Verify count (would check via exporter)
    }
}
```

### Collector Integration Tests

#### Processor in Real Collector
**File**: `collector-processor/mobilepolicyprocessor/integration_test.go`

```go
func TestProcessorInCollector(t *testing.T) {
	// Start test collector with mobile policy processor
	factories := createTestFactories()

	cfg := &Config{
		Policies: []Policy{
			{
				ID:      "test-policy",
				Enabled: true,
				Match: Match{
					LogicalOperator: "and",
					Attributes: map[string]Condition{
						"event.name": {Equals: stringPtr("ui.freeze")},
					},
				},
				Actions: []Action{{Type: "annotate"}},
			},
		},
	}

	// Create processor
	processor, err := factories.Processors["mobilepolicy"].CreateLogsProcessor(
		context.Background(),
		processortest.NewNopCreateSettings(),
		cfg,
		consumertest.NewNop(),
	)
	require.NoError(t, err)

	// Send logs through processor
	logs := createTestLogs()
	err = processor.ConsumeLogs(context.Background(), logs)
	require.NoError(t, err)

	// Verify processing
	// (Check annotations, etc.)
}
```

---

## 3️⃣ End-to-End Tests

### Full System E2E Test

**File**: `e2e-tests/full_system_test.sh`

```bash
#!/bin/bash
set -e

echo "Starting E2E test..."

# 1. Start OTEL Collector
echo "Starting OTEL Collector..."
docker run -d --name otel-collector \
    -p 4317:4317 \
    -v $(pwd)/collector-config.yaml:/etc/otel/config.yaml \
    otelcol-mobile:latest

# 2. Start mock backend to receive logs
echo "Starting mock backend..."
python3 mock_backend.py &
BACKEND_PID=$!

# 3. Run Android app (emulator required)
echo "Running Android app..."
./gradlew :demo-app:connectedAndroidTest

# 4. Verify logs received
echo "Verifying logs..."
LOGS_RECEIVED=$(curl -s http://localhost:8080/logs/count)

if [ "$LOGS_RECEIVED" -gt 0 ]; then
    echo "✅ E2E test passed: $LOGS_RECEIVED logs received"
    exit 0
else
    echo "❌ E2E test failed: No logs received"
    exit 1
fi

# Cleanup
docker stop otel-collector
docker rm otel-collector
kill $BACKEND_PID
```

---

## 🛠️ Test Infrastructure

### Mock Implementations

#### MockLogRecordExporter (Android)
```kotlin
class MockLogRecordExporter : LogRecordExporter {
    val exportedLogs = Collections.synchronizedList(mutableListOf<LogRecordData>())
    var shouldFail = false

    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
        return if (shouldFail) {
            CompletableResultCode.ofFailure()
        } else {
            exportedLogs.addAll(logs)
            CompletableResultCode.ofSuccess()
        }
    }

    override fun shutdown(): CompletableResultCode {
        exportedLogs.clear()
        return CompletableResultCode.ofSuccess()
    }
}
```

#### Test Utilities
```kotlin
object TestUtils {
    fun createTestLogRecord(
        body: String,
        attributes: Map<String, Any> = emptyMap(),
        timestamp: Long = System.currentTimeMillis()
    ): LogRecordData {
        val builder = LogRecordData.builder()
            .setBody(body)
            .setTimestampEpochNanos(timestamp * 1_000_000)

        attributes.forEach { (key, value) ->
            when (value) {
                is String -> builder.setAttribute(AttributeKey.stringKey(key), value)
                is Long -> builder.setAttribute(AttributeKey.longKey(key), value)
                is Double -> builder.setAttribute(AttributeKey.doubleKey(key), value)
                is Boolean -> builder.setAttribute(AttributeKey.booleanKey(key), value)
            }
        }

        return builder.build()
    }
}
```

### Test Configuration

#### build.gradle.kts (Android tests)
```kotlin
android {
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Unit testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("org.mockito:mockito-core:5.7.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // Android instrumented tests
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
```

---

## 📊 Test Coverage Goals

| Component | Target Coverage | Priority |
|-----------|----------------|----------|
| MobileLoggerProvider | >90% | High |
| MobileLogRecordProcessor | >85% | High |
| DiskLogBuffer | >80% | High |
| PolicyEvaluator | >85% | High |
| MobileConfig | >95% | Medium |
| Processor (Go) | >80% | High |
| Config (Go) | >90% | High |
| Factory (Go) | >80% | Medium |

---

## 🚀 CI/CD Integration

### GitHub Actions Workflow

```yaml
name: Test

on: [push, pull_request]

jobs:
  android-unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
      - name: Run unit tests
        run: ./gradlew test
      - name: Upload coverage
        uses: codecov/codecov-action@v3

  android-integration-tests:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v3
      - name: Run instrumented tests
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 29
          script: ./gradlew connectedAndroidTest

  go-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up Go
        uses: actions/setup-go@v4
        with:
          go-version: '1.21'
      - name: Run tests
        run: |
          cd collector-processor/mobilepolicyprocessor
          go test -v -race -coverprofile=coverage.txt ./...
      - name: Upload coverage
        uses: codecov/codecov-action@v3

  e2e-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Build custom collector
        run: ./scripts/build-collector.sh
      - name: Run E2E tests
        run: ./e2e-tests/run-all.sh
```

---

## 📝 Summary

### Test Organization

```
Tests (Total: ~240 tests)
├── Android Unit Tests (~120 tests)
│   ├── MobileLoggerProviderTest (15 tests)
│   ├── MobileLogRecordProcessorTest (30 tests)
│   ├── DiskLogBufferTest (25 tests)
│   ├── PolicyEvaluatorTest (40 tests)
│   └── MobileConfigTest (10 tests)
│
├── Go Unit Tests (~70 tests)
│   ├── processor_test.go (40 tests)
│   ├── config_test.go (20 tests)
│   └── factory_test.go (10 tests)
│
├── Integration Tests (~40 tests)
│   ├── Android integration (20 tests)
│   └── Collector integration (20 tests)
│
└── E2E Tests (~10 tests)
    ├── Full system scenarios (3 tests)
    ├── Performance tests (4 tests)
    └── Load tests (3 tests)
```

### Key Testability Features

1. **Dependency Injection**: All components accept dependencies via constructors
2. **Interface-Based**: Easy to mock (LogRecordExporter, consumer.Logs, etc.)
3. **Testable Time**: Use fixed timestamps in tests
4. **In-Memory Storage**: Room in-memory database for fast tests
5. **Mock HTTP**: MockWebServer for network testing
6. **Isolated Tests**: No shared state between tests
7. **Fast Feedback**: Unit tests run in <5 seconds

### Test Execution

```bash
# Android unit tests
./gradlew test

# Android instrumented tests
./gradlew connectedAndroidTest

# Go tests
cd collector-processor/mobilepolicyprocessor
go test -v -race ./...

# Coverage report
./gradlew testDebugUnitTestCoverage
go test -coverprofile=coverage.out ./...

# E2E tests
./e2e-tests/run-all.sh
```

---

**Next Steps for Phase 4**:
1. Implement all unit tests outlined above
2. Set up CI/CD pipeline
3. Achieve >80% code coverage
4. Run integration tests
5. Execute E2E tests
6. Generate test report

This testing strategy ensures high quality, maintainability, and confidence in the OTEL-native implementation!
