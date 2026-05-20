// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

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

	// Verify all logs were passed to next consumer (processor annotates, does not filter)
	assert.Equal(t, 2, sink.LogRecordCount())

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

	return lr
}

// TestEvaluateConditionGteLte covers gte/lte operators and error paths
func TestEvaluateConditionGteLte(t *testing.T) {
	logger := zaptest.NewLogger(t)
	processor, _ := newMobilePolicyProcessor(&Config{}, logger, consumertest.NewNop())

	gte := 100.0
	lte := 200.0

	assert.True(t, processor.evaluateCondition(100.0, Condition{Gte: &gte}))
	assert.True(t, processor.evaluateCondition(150.0, Condition{Gte: &gte}))
	assert.False(t, processor.evaluateCondition(99.0, Condition{Gte: &gte}))
	assert.True(t, processor.evaluateCondition(200.0, Condition{Lte: &lte}))
	assert.True(t, processor.evaluateCondition(150.0, Condition{Lte: &lte}))
	assert.False(t, processor.evaluateCondition(201.0, Condition{Lte: &lte}))

	// non-numeric value → toFloat64 error path
	assert.False(t, processor.evaluateCondition("not-a-number", Condition{Gte: &gte}))
	assert.False(t, processor.evaluateCondition("not-a-number", Condition{Lte: &lte}))
	assert.False(t, processor.evaluateCondition("not-a-number", Condition{Gt: &gte}))
	assert.False(t, processor.evaluateCondition("not-a-number", Condition{Lt: &lte}))

	// invalid regex → warn and return false
	badRegex := "["
	assert.False(t, processor.evaluateCondition("value", Condition{Regex: &badRegex}))

	// no condition set → false
	assert.False(t, processor.evaluateCondition("value", Condition{}))
}

// TestToFloat64 covers all type branches
func TestToFloat64(t *testing.T) {
	cases := []struct {
		input    interface{}
		expected float64
		wantErr  bool
	}{
		{float64(1.5), 1.5, false},
		{float32(2.5), 2.5, false},
		{int(3), 3.0, false},
		{int64(4), 4.0, false},
		{int32(5), 5.0, false},
		{"6.5", 6.5, false},
		{"not-a-float", 0, true},
		{true, 0, true}, // unsupported type
	}

	for _, c := range cases {
		result, err := toFloat64(c.input)
		if c.wantErr {
			assert.Error(t, err)
		} else {
			assert.NoError(t, err)
			assert.InDelta(t, c.expected, result, 0.001)
		}
	}
}

// TestConvertValue covers all pcommon.Value type branches
func TestConvertValue(t *testing.T) {
	strVal := pcommon.NewValueStr("hello")
	assert.Equal(t, "hello", convertValue(strVal))

	intVal := pcommon.NewValueInt(42)
	assert.Equal(t, int64(42), convertValue(intVal))

	doubleVal := pcommon.NewValueDouble(3.14)
	assert.Equal(t, 3.14, convertValue(doubleVal))

	boolVal := pcommon.NewValueBool(true)
	assert.Equal(t, true, convertValue(boolVal))

	// default branch: map type falls through to AsString
	mapVal := pcommon.NewValueMap()
	mapVal.Map().PutStr("key", "val")
	result := convertValue(mapVal)
	assert.NotNil(t, result)
}

// TestFindSubstring covers the not-found branch
func TestFindSubstring(t *testing.T) {
	assert.True(t, findSubstring("hello world", "world"))
	assert.False(t, findSubstring("hello world", "xyz"))
	assert.True(t, containsString("hello", "hello"))
}

// TestEvaluatePolicyUnknownOperator covers the fallthrough return false at line 148
func TestEvaluatePolicyUnknownOperator(t *testing.T) {
	logger := zaptest.NewLogger(t)
	processor, _ := newMobilePolicyProcessor(&Config{}, logger, consumertest.NewNop())

	policy := Policy{
		ID:      "unknown-op-policy",
		Enabled: true,
		Match: Match{
			LogicalOperator: "xor", // unsupported
			Attributes: map[string]Condition{
				"event.name": {Equals: stringPtr("ui.freeze")},
			},
		},
		Actions: []Action{{Type: "annotate"}},
	}

	assert.False(t, processor.evaluatePolicy(policy, map[string]interface{}{"event.name": "ui.freeze"}))
}

// TestEvaluatePolicyMissingAttribute covers the missing-attr branch in evaluatePolicy
func TestEvaluatePolicyMissingAttribute(t *testing.T) {
	logger := zaptest.NewLogger(t)
	processor, _ := newMobilePolicyProcessor(&Config{}, logger, consumertest.NewNop())

	policy := Policy{
		ID:      "missing-attr-policy",
		Enabled: true,
		Match: Match{
			LogicalOperator: "and",
			Attributes: map[string]Condition{
				"nonexistent.attr": {Equals: stringPtr("value")},
			},
		},
		Actions: []Action{{Type: "annotate"}},
	}

	// attribute doesn't exist → appends false → and returns false
	assert.False(t, processor.evaluatePolicy(policy, map[string]interface{}{}))

	// or operator with missing attr → false
	policy.Match.LogicalOperator = "or"
	assert.False(t, processor.evaluatePolicy(policy, map[string]interface{}{}))
}

func stringPtr(s string) *string {
	return &s
}

func float64Ptr(f float64) *float64 {
	return &f
}

// TestGetOrCompileRegex_CachesByPattern — SR-012
//
// regexp.MatchString recompiles the regex on every policy evaluation. At
// fleet scale that's measurable waste. Verify same pattern returns the
// same *regexp.Regexp pointer (cached), and different patterns return
// different pointers (not pinning to a single cache slot).
func TestGetOrCompileRegex_CachesByPattern(t *testing.T) {
	logger := zaptest.NewLogger(t)
	processor, err := newMobilePolicyProcessor(&Config{}, logger, consumertest.NewNop())
	require.NoError(t, err)

	r1, err := processor.getOrCompileRegex(`^ui\..*`)
	require.NoError(t, err)
	r2, err := processor.getOrCompileRegex(`^ui\..*`)
	require.NoError(t, err)
	require.Same(t, r1, r2, "same pattern must return cached compiled regex")

	r3, err := processor.getOrCompileRegex(`^app\..*`)
	require.NoError(t, err)
	require.NotSame(t, r1, r3, "different pattern must compile a new regex")
}

// TestGetOrCompileRegex_InvalidPatternReturnsError — SR-012
//
// Bad patterns must surface a compile error rather than panic or cache a
// nil pointer that later derefs.
func TestGetOrCompileRegex_InvalidPatternReturnsError(t *testing.T) {
	logger := zaptest.NewLogger(t)
	processor, err := newMobilePolicyProcessor(&Config{}, logger, consumertest.NewNop())
	require.NoError(t, err)

	_, err = processor.getOrCompileRegex(`[unclosed`)
	require.Error(t, err, "invalid regex must return error")
}

// TestProcessLogRecord_EventNameOverridenByNonStringAttr — SR-025
//
// processLogRecord seeds attrs["event.name"] from the log body (always
// a string) and then overwrites every attrs[k] with convertValue(v).
// A log record whose attribute key is literally "event.name" with a
// numeric value clobbers the seeded string. When *another* attribute
// condition then matches the policy, the match-logger does a bare
// `.(string)` type assertion on the now-int64 value and panics —
// taking down the whole batch in the collector pipeline.
//
// Verifies: no panic, and the policy match annotation still lands.
func TestProcessLogRecord_EventNameOverridenByNonStringAttr(t *testing.T) {
	// Match on a different attribute (not event.name), so the panic
	// path on the match-debug log line is exercised.
	policy := Policy{
		ID:      "event-name-poison",
		Enabled: true,
		Match: Match{
			LogicalOperator: "and",
			Attributes: map[string]Condition{
				"trigger": {Equals: stringPtr("yes")},
			},
		},
		Actions: []Action{
			{Type: "annotate", Parameters: map[string]interface{}{"reason": "ok"}},
		},
	}

	logger := zaptest.NewLogger(t)
	processor, err := newMobilePolicyProcessor(
		&Config{Policies: []Policy{policy}},
		logger,
		consumertest.NewNop(),
	)
	require.NoError(t, err)

	// Body seeds event.name=ui.freeze; "event.name" attribute then
	// overwrites it with int64. "trigger" attribute makes the policy
	// match, so processLogRecord reaches the .(string) assertion.
	logRecord := createTestLogRecord("ui.freeze", map[string]interface{}{
		"event.name": int64(42),
		"trigger":    "yes",
	})
	resourceAttrs := pcommon.NewMap()

	require.NotPanics(t, func() {
		processor.processLogRecord(logRecord, resourceAttrs)
	})

	// Annotation lands.
	policyID, exists := logRecord.Attributes().Get("policy.id")
	require.True(t, exists, "policy.id annotation should have landed")
	require.Equal(t, "event-name-poison", policyID.Str())
}
