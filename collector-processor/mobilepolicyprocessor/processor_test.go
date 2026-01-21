package mobilepolicyprocessor

import (
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.opentelemetry.io/collector/consumer/consumertest"
	"go.opentelemetry.io/collector/pdata/pcommon"
	"go.opentelemetry.io/collector/pdata/plog"
	"go.uber.org/zap/zaptest"
)

// TestPolicyEvaluation tests the core policy evaluation logic
func TestPolicyEvaluation(t *testing.T) {
	tests := []struct {
		name        string
		policy      Policy
		logRecord   plog.LogRecord
		shouldMatch bool
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
				Actions: []Action{{Type: "annotate"}},
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
				Actions: []Action{{Type: "annotate"}},
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
				Actions: []Action{{Type: "annotate"}},
			},
			logRecord: createTestLogRecord("event", map[string]interface{}{
				"duration_ms": int64(2500),
			}),
			shouldMatch: true,
		},
		{
			name: "gt condition does not match",
			policy: Policy{
				ID:      "threshold-policy",
				Enabled: true,
				Match: Match{
					LogicalOperator: "and",
					Attributes: map[string]Condition{
						"duration_ms": {Gt: float64Ptr(2000.0)},
					},
				},
				Actions: []Action{{Type: "annotate"}},
			},
			logRecord: createTestLogRecord("event", map[string]interface{}{
				"duration_ms": int64(1500),
			}),
			shouldMatch: false,
		},
		{
			name: "lt condition matches",
			policy: Policy{
				ID:      "lt-policy",
				Enabled: true,
				Match: Match{
					LogicalOperator: "and",
					Attributes: map[string]Condition{
						"latency_ms": {Lt: float64Ptr(100.0)},
					},
				},
				Actions: []Action{{Type: "annotate"}},
			},
			logRecord: createTestLogRecord("event", map[string]interface{}{
				"latency_ms": int64(50),
			}),
			shouldMatch: true,
		},
		{
			name: "contains condition matches",
			policy: Policy{
				ID:      "contains-policy",
				Enabled: true,
				Match: Match{
					LogicalOperator: "and",
					Attributes: map[string]Condition{
						"http.route": {Contains: stringPtr("/appointments")},
					},
				},
				Actions: []Action{{Type: "annotate"}},
			},
			logRecord: createTestLogRecord("http.request", map[string]interface{}{
				"http.route": "/api/appointments/123",
			}),
			shouldMatch: true,
		},
		{
			name: "regex condition matches",
			policy: Policy{
				ID:      "regex-policy",
				Enabled: true,
				Match: Match{
					LogicalOperator: "and",
					Attributes: map[string]Condition{
						"event.name": {Regex: stringPtr("^ui\\.(freeze|lag)$")},
					},
				},
				Actions: []Action{{Type: "annotate"}},
			},
			logRecord:   createTestLogRecord("ui.freeze", nil),
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
				Actions: []Action{{Type: "annotate"}},
			},
			logRecord: createTestLogRecord("ui.freeze", map[string]interface{}{
				"duration_ms": int64(1500), // Below threshold
			}),
			shouldMatch: false,
		},
		{
			name: "and operator matches when all conditions meet",
			policy: Policy{
				ID:      "and-policy-match",
				Enabled: true,
				Match: Match{
					LogicalOperator: "and",
					Attributes: map[string]Condition{
						"event.name":  {Equals: stringPtr("ui.freeze")},
						"duration_ms": {Gt: float64Ptr(2000.0)},
					},
				},
				Actions: []Action{{Type: "annotate"}},
			},
			logRecord: createTestLogRecord("ui.freeze", map[string]interface{}{
				"duration_ms": int64(2500), // Above threshold
			}),
			shouldMatch: true,
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
				Actions: []Action{{Type: "annotate"}},
			},
			logRecord: createTestLogRecord("ui.freeze", map[string]interface{}{
				"duration_ms": int64(1500), // Below threshold but name matches
			}),
			shouldMatch: true,
		},
		{
			name: "disabled policy does not match",
			policy: Policy{
				ID:      "disabled-policy",
				Enabled: false, // Disabled
				Match: Match{
					LogicalOperator: "and",
					Attributes: map[string]Condition{
						"event.name": {Equals: stringPtr("ui.freeze")},
					},
				},
				Actions: []Action{{Type: "annotate"}},
			},
			logRecord:   createTestLogRecord("ui.freeze", nil),
			shouldMatch: false,
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
			assert.Equal(t, tt.shouldMatch, result, "Policy match result mismatch")
		})
	}
}

// TestLogAnnotation verifies that policies correctly annotate log records
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
	matched, exists := logRecord.Attributes().Get("policy.matched")
	assert.True(t, exists, "policy.matched attribute should exist")
	assert.Equal(t, "true", matched.Str())

	policyID, exists := logRecord.Attributes().Get("policy.id")
	assert.True(t, exists, "policy.id attribute should exist")
	assert.Equal(t, "annotate-policy", policyID.Str())

	triggerID, exists := logRecord.Attributes().Get("policy.trigger_id")
	assert.True(t, exists, "policy.trigger_id attribute should exist")
	assert.Equal(t, "ui-freeze-handler", triggerID.Str())

	reason, exists := logRecord.Attributes().Get("policy.reason")
	assert.True(t, exists, "policy.reason attribute should exist")
	assert.Equal(t, "UI freeze detected", reason.Str())
}

// TestConsumeLogs tests the main ConsumeLogs processing pipeline
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

	// Add matching log
	logRecord1 := scopeLog.LogRecords().AppendEmpty()
	logRecord1.Body().SetStr("ui.freeze")
	logRecord1.SetTimestamp(pcommon.NewTimestampFromTime(time.Now()))

	// Add non-matching log
	logRecord2 := scopeLog.LogRecords().AppendEmpty()
	logRecord2.Body().SetStr("other.event")
	logRecord2.SetTimestamp(pcommon.NewTimestampFromTime(time.Now()))

	// Process logs
	err = processor.ConsumeLogs(context.Background(), logs)
	require.NoError(t, err)

	// Verify logs were passed to next consumer
	assert.Equal(t, 1, sink.LogRecordCount())

	// Verify annotation was added to matching log only
	receivedLogs := sink.AllLogs()[0]
	receivedLogRecord := receivedLogs.ResourceLogs().At(0).ScopeLogs().At(0).LogRecords().At(0)

	matched, exists := receivedLogRecord.Attributes().Get("policy.matched")
	if receivedLogRecord.Body().AsString() == "ui.freeze" {
		assert.True(t, exists, "Matching log should have policy.matched")
		assert.Equal(t, "true", matched.Str())
	}
}

// TestResourceAttributeExtraction verifies resource attributes are extracted correctly
func TestResourceAttributeExtraction(t *testing.T) {
	policy := Policy{
		ID:      "resource-policy",
		Enabled: true,
		Match: Match{
			LogicalOperator: "and",
			Attributes: map[string]Condition{
				"resource.service.name": {Equals: stringPtr("mobile-app")},
			},
		},
		Actions: []Action{{Type: "annotate"}},
	}

	logger := zaptest.NewLogger(t)
	nextConsumer := consumertest.NewNop()

	processor, err := newMobilePolicyProcessor(
		&Config{Policies: []Policy{policy}},
		logger,
		nextConsumer,
	)
	require.NoError(t, err)

	logRecord := createTestLogRecord("test.event", nil)
	resourceAttrs := pcommon.NewMap()
	resourceAttrs.PutStr("service.name", "mobile-app")

	processor.processLogRecord(logRecord, resourceAttrs)

	// Verify policy matched based on resource attribute
	matched, exists := logRecord.Attributes().Get("policy.matched")
	assert.True(t, exists)
	assert.Equal(t, "true", matched.Str())
}

// TestSampleAction verifies sample rate annotations
func TestSampleAction(t *testing.T) {
	policy := Policy{
		ID:      "sample-policy",
		Enabled: true,
		Match: Match{
			LogicalOperator: "and",
			Attributes: map[string]Condition{
				"event.name": {Equals: stringPtr("error.critical")},
			},
		},
		Actions: []Action{
			{
				Type: "sample",
				Parameters: map[string]interface{}{
					"sample_rate":      1.0,
					"duration_minutes": 10.0,
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

	logRecord := createTestLogRecord("error.critical", nil)
	resourceAttrs := pcommon.NewMap()

	processor.processLogRecord(logRecord, resourceAttrs)

	// Verify sample annotations
	sampleRate, exists := logRecord.Attributes().Get("policy.sample_rate")
	assert.True(t, exists)
	assert.Equal(t, 1.0, sampleRate.Double())

	duration, exists := logRecord.Attributes().Get("policy.sample_duration_minutes")
	assert.True(t, exists)
	assert.Equal(t, 10.0, duration.Double())
}

// TestMultiplePolicies verifies handling of multiple policy matches
func TestMultiplePolicies(t *testing.T) {
	policies := []Policy{
		{
			ID:      "policy-1",
			Enabled: true,
			Match: Match{
				LogicalOperator: "and",
				Attributes: map[string]Condition{
					"event.name": {Equals: stringPtr("ui.freeze")},
				},
			},
			Actions: []Action{{Type: "annotate", Parameters: map[string]interface{}{"policy": "first"}}},
		},
		{
			ID:      "policy-2",
			Enabled: true,
			Match: Match{
				LogicalOperator: "and",
				Attributes: map[string]Condition{
					"duration_ms": {Gt: float64Ptr(2000.0)},
				},
			},
			Actions: []Action{{Type: "annotate", Parameters: map[string]interface{}{"policy": "second"}}},
		},
	}

	logger := zaptest.NewLogger(t)
	nextConsumer := consumertest.NewNop()

	processor, err := newMobilePolicyProcessor(
		&Config{Policies: policies},
		logger,
		nextConsumer,
	)
	require.NoError(t, err)

	logRecord := createTestLogRecord("ui.freeze", map[string]interface{}{
		"duration_ms": int64(2500),
	})
	resourceAttrs := pcommon.NewMap()

	processor.processLogRecord(logRecord, resourceAttrs)

	// Verify both policies matched (annotations from first match win)
	matched, exists := logRecord.Attributes().Get("policy.matched")
	assert.True(t, exists)
	assert.Equal(t, "true", matched.Str())

	// First policy should be recorded
	policyID, _ := logRecord.Attributes().Get("policy.id")
	assert.Equal(t, "policy-1", policyID.Str())
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
