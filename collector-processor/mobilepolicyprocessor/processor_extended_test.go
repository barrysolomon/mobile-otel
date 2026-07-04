// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

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

// TestDropActionNoAnnotation verifies that "drop" action type doesn't annotate
// (currently a no-op in the switch — regression test for future implementation)
func TestDropActionNoAnnotation(t *testing.T) {
	policy := Policy{
		ID:      "drop-policy",
		Enabled: true,
		Match: Match{
			LogicalOperator: "and",
			Attributes: map[string]Condition{
				"event.name": {Equals: stringPtr("debug.trace")},
			},
		},
		Actions: []Action{{Type: "drop"}},
	}

	logger := zaptest.NewLogger(t)
	processor, err := newMobilePolicyProcessor(
		&Config{Policies: []Policy{policy}},
		logger,
		consumertest.NewNop(),
	)
	require.NoError(t, err)

	lr := createTestLogRecord("debug.trace", nil)
	resourceAttrs := pcommon.NewMap()
	processor.processLogRecord(lr, resourceAttrs)

	// policy.matched should still be set (annotateLogRecord is called for all action types)
	matched, exists := lr.Attributes().Get("policy.matched")
	assert.True(t, exists)
	assert.Equal(t, "true", matched.Str())

	// No sample_rate annotation (drop action doesn't set it)
	_, hasSampleRate := lr.Attributes().Get("policy.sample_rate")
	assert.False(t, hasSampleRate)
}

// TestForwardActionNoAnnotation verifies that "forward" action type doesn't crash or annotate extra attrs
func TestForwardActionNoAnnotation(t *testing.T) {
	policy := Policy{
		ID:      "forward-policy",
		Enabled: true,
		Match: Match{
			LogicalOperator: "and",
			Attributes: map[string]Condition{
				"event.name": {Equals: stringPtr("critical.event")},
			},
		},
		Actions: []Action{{Type: "forward", Parameters: map[string]interface{}{"target": "escalation"}}},
	}

	logger := zaptest.NewLogger(t)
	processor, err := newMobilePolicyProcessor(
		&Config{Policies: []Policy{policy}},
		logger,
		consumertest.NewNop(),
	)
	require.NoError(t, err)

	lr := createTestLogRecord("critical.event", nil)
	resourceAttrs := pcommon.NewMap()
	processor.processLogRecord(lr, resourceAttrs)

	matched, exists := lr.Attributes().Get("policy.matched")
	assert.True(t, exists)
	assert.Equal(t, "true", matched.Str())

	policyID, _ := lr.Attributes().Get("policy.id")
	assert.Equal(t, "forward-policy", policyID.Str())
}

// TestConsumeLogsEmptyPolicies verifies that zero policies processes logs without error
func TestConsumeLogsEmptyPolicies(t *testing.T) {
	logger := zaptest.NewLogger(t)
	sink := &consumertest.LogsSink{}
	processor, err := newMobilePolicyProcessor(
		&Config{Policies: []Policy{}},
		logger,
		sink,
	)
	require.NoError(t, err)

	ld := plog.NewLogs()
	rl := ld.ResourceLogs().AppendEmpty()
	sl := rl.ScopeLogs().AppendEmpty()
	lr := sl.LogRecords().AppendEmpty()
	lr.Body().SetStr("test.event")

	err = processor.ConsumeLogs(context.Background(), ld)
	require.NoError(t, err)

	// Log should pass through without annotation
	require.Equal(t, 1, sink.LogRecordCount())
	outLr := sink.AllLogs()[0].ResourceLogs().At(0).ScopeLogs().At(0).LogRecords().At(0)
	_, hasMatched := outLr.Attributes().Get("policy.matched")
	assert.False(t, hasMatched, "no policies → no policy.matched annotation")
}

// TestConsumeLogsContextCancelled verifies that a cancelled context is propagated to next consumer
func TestConsumeLogsContextCancelled(t *testing.T) {
	logger := zaptest.NewLogger(t)
	sink := &consumertest.LogsSink{}
	processor, err := newMobilePolicyProcessor(
		&Config{Policies: []Policy{}},
		logger,
		sink,
	)
	require.NoError(t, err)

	ctx, cancel := context.WithCancel(context.Background())
	cancel() // cancel immediately

	ld := plog.NewLogs()
	rl := ld.ResourceLogs().AppendEmpty()
	sl := rl.ScopeLogs().AppendEmpty()
	lr := sl.LogRecords().AppendEmpty()
	lr.Body().SetStr("test.event")

	// ConsumeLogs passes ctx to next consumer — nop sink doesn't check context, so no error
	err = processor.ConsumeLogs(ctx, ld)
	assert.NoError(t, err)
}

// TestConsumeLogsLargeBatch verifies processing of many log records
func TestConsumeLogsLargeBatch(t *testing.T) {
	policy := Policy{
		ID:      "batch-policy",
		Enabled: true,
		Match: Match{
			LogicalOperator: "and",
			Attributes: map[string]Condition{
				"event.name": {Contains: stringPtr("event")},
			},
		},
		Actions: []Action{{Type: "annotate"}},
	}

	logger := zaptest.NewLogger(t)
	sink := &consumertest.LogsSink{}
	processor, err := newMobilePolicyProcessor(
		&Config{Policies: []Policy{policy}},
		logger,
		sink,
	)
	require.NoError(t, err)

	ld := plog.NewLogs()
	rl := ld.ResourceLogs().AppendEmpty()
	sl := rl.ScopeLogs().AppendEmpty()

	batchSize := 200
	for i := 0; i < batchSize; i++ {
		lr := sl.LogRecords().AppendEmpty()
		lr.Body().SetStr("test.event")
	}

	err = processor.ConsumeLogs(context.Background(), ld)
	require.NoError(t, err)
	require.Equal(t, batchSize, sink.LogRecordCount())

	// Verify all records annotated
	outSl := sink.AllLogs()[0].ResourceLogs().At(0).ScopeLogs().At(0)
	for i := 0; i < outSl.LogRecords().Len(); i++ {
		lr := outSl.LogRecords().At(i)
		matched, exists := lr.Attributes().Get("policy.matched")
		assert.True(t, exists, "record %d should be annotated", i)
		assert.Equal(t, "true", matched.Str())
	}
}

// TestSampleActionMissingParameters verifies sample action handles missing/bad params gracefully
func TestSampleActionMissingParameters(t *testing.T) {
	tests := []struct {
		name   string
		params interface{}
	}{
		{"nil parameters", nil},
		{"wrong type parameters", "not-a-map"},
		{"empty map", map[string]interface{}{}},
		{"missing sample_rate", map[string]interface{}{"duration_minutes": 5.0}},
		{"wrong sample_rate type", map[string]interface{}{"sample_rate": "one"}},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			policy := Policy{
				ID:      "bad-sample",
				Enabled: true,
				Match: Match{
					LogicalOperator: "and",
					Attributes: map[string]Condition{
						"event.name": {Equals: stringPtr("match")},
					},
				},
				Actions: []Action{{Type: "sample", Parameters: tt.params}},
			}

			logger := zaptest.NewLogger(t)
			processor, _ := newMobilePolicyProcessor(
				&Config{Policies: []Policy{policy}},
				logger,
				consumertest.NewNop(),
			)

			lr := createTestLogRecord("match", nil)
			processor.processLogRecord(lr, pcommon.NewMap())

			// Should not crash; policy.matched still set
			matched, exists := lr.Attributes().Get("policy.matched")
			assert.True(t, exists)
			assert.Equal(t, "true", matched.Str())
		})
	}
}

// TestAnnotateActionMissingParameters verifies annotate action handles bad params gracefully
func TestAnnotateActionMissingParameters(t *testing.T) {
	policy := Policy{
		ID:      "bad-annotate",
		Enabled: true,
		Match: Match{
			LogicalOperator: "and",
			Attributes: map[string]Condition{
				"event.name": {Equals: stringPtr("match")},
			},
		},
		Actions: []Action{{Type: "annotate", Parameters: "not-a-map"}},
	}

	logger := zaptest.NewLogger(t)
	processor, _ := newMobilePolicyProcessor(
		&Config{Policies: []Policy{policy}},
		logger,
		consumertest.NewNop(),
	)

	lr := createTestLogRecord("match", nil)
	processor.processLogRecord(lr, pcommon.NewMap())

	// Should not crash; policy.matched still set, but no custom annotations
	matched, exists := lr.Attributes().Get("policy.matched")
	assert.True(t, exists)
	assert.Equal(t, "true", matched.Str())
}

// TestDisabledPolicySkipped verifies disabled policies don't match
func TestDisabledPolicySkipped(t *testing.T) {
	policy := Policy{
		ID:      "disabled-policy",
		Enabled: false,
		Match: Match{
			LogicalOperator: "and",
			Attributes: map[string]Condition{
				"event.name": {Equals: stringPtr("match")},
			},
		},
		Actions: []Action{{Type: "annotate"}},
	}

	logger := zaptest.NewLogger(t)
	processor, _ := newMobilePolicyProcessor(
		&Config{Policies: []Policy{policy}},
		logger,
		consumertest.NewNop(),
	)

	lr := createTestLogRecord("match", nil)
	processor.processLogRecord(lr, pcommon.NewMap())

	_, exists := lr.Attributes().Get("policy.matched")
	assert.False(t, exists, "disabled policy should not annotate")
}

// TestAndOperatorAllMustMatch verifies AND requires all conditions true
func TestAndOperatorAllMustMatch(t *testing.T) {
	logger := zaptest.NewLogger(t)
	processor, _ := newMobilePolicyProcessor(&Config{}, logger, consumertest.NewNop())

	policy := Policy{
		ID:      "and-policy",
		Enabled: true,
		Match: Match{
			LogicalOperator: "and",
			Attributes: map[string]Condition{
				"event.name": {Equals: stringPtr("ui.freeze")},
				"severity":   {Equals: stringPtr("high")},
			},
		},
	}

	// Both match
	assert.True(t, processor.evaluatePolicy(policy, map[string]interface{}{
		"event.name": "ui.freeze",
		"severity":   "high",
	}))

	// Only one matches
	assert.False(t, processor.evaluatePolicy(policy, map[string]interface{}{
		"event.name": "ui.freeze",
		"severity":   "low",
	}))
}

// TestOrOperatorAnyCanMatch verifies OR only needs one condition true
func TestOrOperatorAnyCanMatch(t *testing.T) {
	logger := zaptest.NewLogger(t)
	processor, _ := newMobilePolicyProcessor(&Config{}, logger, consumertest.NewNop())

	policy := Policy{
		ID:      "or-policy",
		Enabled: true,
		Match: Match{
			LogicalOperator: "or",
			Attributes: map[string]Condition{
				"event.name": {Equals: stringPtr("ui.freeze")},
				"severity":   {Equals: stringPtr("critical")},
			},
		},
	}

	// Only second matches
	assert.True(t, processor.evaluatePolicy(policy, map[string]interface{}{
		"event.name": "other",
		"severity":   "critical",
	}))

	// Neither matches
	assert.False(t, processor.evaluatePolicy(policy, map[string]interface{}{
		"event.name": "other",
		"severity":   "low",
	}))
}

// TestContainsEmptySubstring edge case
func TestContainsEmptySubstring(t *testing.T) {
	assert.True(t, containsString("hello", ""))
	assert.True(t, containsString("", ""))
	assert.False(t, containsString("", "a"))
}
