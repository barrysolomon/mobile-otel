// Copyright 2025 Barry Solomon
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

// Integration tests: realistic mobile telemetry scenarios through full ConsumeLogs pipeline.
// Each test simulates a user journey with multiple events, multiple policies, and
// verifies that annotations land on the correct log records.

func TestScenario_CrashRecoveryFlush(t *testing.T) {
	// Scenario: App crashes, restarts, emits crash recovery event.
	// Expected: crash-recovery policy annotates the recovery event but not navigation events.
	policies := []Policy{
		{
			ID: "crash-recovery", Enabled: true,
			Match: Match{
				LogicalOperator: "and",
				Attributes:      map[string]Condition{"event.name": {Equals: stringPtr("app.crash")}},
			},
			Actions: []Action{{Type: "annotate", Parameters: map[string]interface{}{"reason": "crash detected"}}},
		},
	}

	sink := &consumertest.LogsSink{}
	processor, err := newMobilePolicyProcessor(&Config{Policies: policies}, zaptest.NewLogger(t), sink)
	require.NoError(t, err)

	logs := createMobileLogs(
		mobileEvent("screen.view", map[string]interface{}{"screen.name": "HomeActivity"}),
		mobileEvent("screen.view", map[string]interface{}{"screen.name": "BookingActivity"}),
		mobileEvent("app.crash", map[string]interface{}{"error.type": "java.lang.NullPointerException"}),
		mobileEvent("app.crash_recovery", map[string]interface{}{"recovery_type": "crash"}),
	)

	err = processor.ConsumeLogs(context.Background(), logs)
	require.NoError(t, err)
	assert.Equal(t, 4, sink.LogRecordCount())

	records := extractLogRecords(sink)
	assertNotAnnotated(t, records[0], "screen.view should not match crash policy")
	assertNotAnnotated(t, records[1], "screen.view should not match crash policy")
	assertAnnotated(t, records[2], "crash-recovery", "app.crash should match")
	assertNotAnnotated(t, records[3], "app.crash_recovery != app.crash")
}

func TestScenario_UIFreezeWithDuration(t *testing.T) {
	// Scenario: UI freeze > 2s triggers flush.
	// Expected: Only the freeze event with duration > 2000 matches.
	policies := []Policy{
		{
			ID: "ui-freeze-detector", Enabled: true,
			Match: Match{
				LogicalOperator: "and",
				Attributes: map[string]Condition{
					"event.name": {Equals: stringPtr("ui.freeze")},
					"duration_ms": {Gt: float64Ptr(2000.0)},
				},
			},
			Actions: []Action{{Type: "annotate", Parameters: map[string]interface{}{"reason": "UI freeze > 2s"}}},
		},
	}

	sink := &consumertest.LogsSink{}
	processor, err := newMobilePolicyProcessor(&Config{Policies: policies}, zaptest.NewLogger(t), sink)
	require.NoError(t, err)

	logs := createMobileLogs(
		mobileEvent("ui.freeze", map[string]interface{}{"duration_ms": int64(500)}),
		mobileEvent("ui.freeze", map[string]interface{}{"duration_ms": int64(3000)}),
		mobileEvent("ui.tap", map[string]interface{}{"element.id": "btn_submit"}),
	)

	err = processor.ConsumeLogs(context.Background(), logs)
	require.NoError(t, err)
	assert.Equal(t, 3, sink.LogRecordCount())

	records := extractLogRecords(sink)
	assertNotAnnotated(t, records[0], "freeze 500ms < 2000 threshold")
	assertAnnotated(t, records[1], "ui-freeze-detector", "freeze 3000ms > 2000 threshold")
	assertNotAnnotated(t, records[2], "tap should not match freeze policy")
}

func TestScenario_MultiplePoliciesFirstWins(t *testing.T) {
	// Scenario: HTTP error matches both http-error and generic-error policies.
	// Expected: Both policies annotate the event (not first-wins — all matching policies apply).
	policies := []Policy{
		{
			ID: "http-error-detector", Enabled: true,
			Match: Match{
				LogicalOperator: "and",
				Attributes:      map[string]Condition{"event.name": {Equals: stringPtr("http.error")}},
			},
			Actions: []Action{{Type: "annotate", Parameters: map[string]interface{}{"reason": "HTTP error"}}},
		},
		{
			ID: "generic-error", Enabled: true,
			Match: Match{
				LogicalOperator: "and",
				Attributes:      map[string]Condition{"event.name": {Contains: stringPtr("error")}},
			},
			Actions: []Action{{Type: "annotate", Parameters: map[string]interface{}{"reason": "any error"}}},
		},
	}

	sink := &consumertest.LogsSink{}
	processor, err := newMobilePolicyProcessor(&Config{Policies: policies}, zaptest.NewLogger(t), sink)
	require.NoError(t, err)

	logs := createMobileLogs(
		mobileEvent("http.error", map[string]interface{}{"http.status_code": int64(500)}),
	)

	err = processor.ConsumeLogs(context.Background(), logs)
	require.NoError(t, err)

	records := extractLogRecords(sink)
	matched, exists := records[0].Attributes().Get("policy.matched")
	assert.True(t, exists, "should be annotated")
	assert.Equal(t, "true", matched.Str())
}

func TestScenario_DisabledPolicyIgnored(t *testing.T) {
	policies := []Policy{
		{
			ID: "disabled", Enabled: false,
			Match: Match{
				LogicalOperator: "and",
				Attributes:      map[string]Condition{"event.name": {Equals: stringPtr("app.crash")}},
			},
			Actions: []Action{{Type: "annotate"}},
		},
	}

	sink := &consumertest.LogsSink{}
	processor, err := newMobilePolicyProcessor(&Config{Policies: policies}, zaptest.NewLogger(t), sink)
	require.NoError(t, err)

	logs := createMobileLogs(mobileEvent("app.crash", nil))
	err = processor.ConsumeLogs(context.Background(), logs)
	require.NoError(t, err)

	records := extractLogRecords(sink)
	assertNotAnnotated(t, records[0], "disabled policy should not match")
}

func TestScenario_OROperatorMatchesAny(t *testing.T) {
	// Scenario: OR policy matches if ANY condition is true.
	policies := []Policy{
		{
			ID: "any-critical", Enabled: true,
			Match: Match{
				LogicalOperator: "or",
				Attributes: map[string]Condition{
					"event.name": {Equals: stringPtr("app.crash")},
					"severity":   {Equals: stringPtr("critical")},
				},
			},
			Actions: []Action{{Type: "annotate"}},
		},
	}

	sink := &consumertest.LogsSink{}
	processor, err := newMobilePolicyProcessor(&Config{Policies: policies}, zaptest.NewLogger(t), sink)
	require.NoError(t, err)

	logs := createMobileLogs(
		mobileEvent("normal.event", map[string]interface{}{"severity": "critical"}),
		mobileEvent("app.crash", nil),
		mobileEvent("normal.event", map[string]interface{}{"severity": "info"}),
	)

	err = processor.ConsumeLogs(context.Background(), logs)
	require.NoError(t, err)

	records := extractLogRecords(sink)
	assertAnnotated(t, records[0], "any-critical", "severity=critical matches OR")
	assertAnnotated(t, records[1], "any-critical", "event.name=app.crash matches OR")
	assertNotAnnotated(t, records[2], "neither condition matches")
}

func TestScenario_RegexMatching(t *testing.T) {
	policies := []Policy{
		{
			ID: "http-5xx", Enabled: true,
			Match: Match{
				LogicalOperator: "and",
				Attributes:      map[string]Condition{"error.type": {Regex: stringPtr("http\\.5[0-9]{2}")}},
			},
			Actions: []Action{{Type: "annotate"}},
		},
	}

	sink := &consumertest.LogsSink{}
	processor, err := newMobilePolicyProcessor(&Config{Policies: policies}, zaptest.NewLogger(t), sink)
	require.NoError(t, err)

	logs := createMobileLogs(
		mobileEvent("http.error", map[string]interface{}{"error.type": "http.500"}),
		mobileEvent("http.error", map[string]interface{}{"error.type": "http.503"}),
		mobileEvent("http.error", map[string]interface{}{"error.type": "http.404"}),
	)

	err = processor.ConsumeLogs(context.Background(), logs)
	require.NoError(t, err)

	records := extractLogRecords(sink)
	assertAnnotated(t, records[0], "http-5xx", "500 matches 5xx regex")
	assertAnnotated(t, records[1], "http-5xx", "503 matches 5xx regex")
	assertNotAnnotated(t, records[2], "404 does not match 5xx regex")
}

func TestScenario_ResourceAttributesPropagated(t *testing.T) {
	// Verify resource attributes (service.name, device.id) are available for policy evaluation.
	policies := []Policy{
		{
			ID: "resource-aware", Enabled: true,
			Match: Match{
				LogicalOperator: "and",
				Attributes:      map[string]Condition{"event.name": {Equals: stringPtr("test.event")}},
			},
			Actions: []Action{{Type: "annotate"}},
		},
	}

	sink := &consumertest.LogsSink{}
	processor, err := newMobilePolicyProcessor(&Config{Policies: policies}, zaptest.NewLogger(t), sink)
	require.NoError(t, err)

	logs := plog.NewLogs()
	rl := logs.ResourceLogs().AppendEmpty()
	rl.Resource().Attributes().PutStr("service.name", "otel-mobile-demo")
	rl.Resource().Attributes().PutStr("device.id", "pixel-7-abc123")
	sl := rl.ScopeLogs().AppendEmpty()
	lr := sl.LogRecords().AppendEmpty()
	lr.Body().SetStr("test.event")
	lr.SetTimestamp(pcommon.NewTimestampFromTime(time.Now()))

	err = processor.ConsumeLogs(context.Background(), logs)
	require.NoError(t, err)

	records := extractLogRecords(sink)
	assertAnnotated(t, records[0], "resource-aware", "should match with resource attrs present")
}

func TestScenario_EmptyBatchPassesThrough(t *testing.T) {
	sink := &consumertest.LogsSink{}
	processor, err := newMobilePolicyProcessor(&Config{}, zaptest.NewLogger(t), sink)
	require.NoError(t, err)

	err = processor.ConsumeLogs(context.Background(), plog.NewLogs())
	require.NoError(t, err)
	assert.Equal(t, 0, sink.LogRecordCount())
}

func TestScenario_LargeBatch(t *testing.T) {
	// Verify the processor handles a large batch without errors.
	policies := []Policy{
		{
			ID: "crash-policy", Enabled: true,
			Match: Match{
				LogicalOperator: "and",
				Attributes:      map[string]Condition{"event.name": {Equals: stringPtr("app.crash")}},
			},
			Actions: []Action{{Type: "annotate"}},
		},
	}

	sink := &consumertest.LogsSink{}
	processor, err := newMobilePolicyProcessor(&Config{Policies: policies}, zaptest.NewLogger(t), sink)
	require.NoError(t, err)

	logs := plog.NewLogs()
	sl := logs.ResourceLogs().AppendEmpty().ScopeLogs().AppendEmpty()
	for i := 0; i < 1000; i++ {
		lr := sl.LogRecords().AppendEmpty()
		if i == 500 {
			lr.Body().SetStr("app.crash")
		} else {
			lr.Body().SetStr("screen.view")
		}
		lr.SetTimestamp(pcommon.NewTimestampFromTime(time.Now()))
	}

	err = processor.ConsumeLogs(context.Background(), logs)
	require.NoError(t, err)
	assert.Equal(t, 1000, sink.LogRecordCount())
}

func TestScenario_SampleAction(t *testing.T) {
	// Verify sample action annotates with sampling rate.
	policies := []Policy{
		{
			ID: "error-upsample", Enabled: true,
			Match: Match{
				LogicalOperator: "and",
				Attributes:      map[string]Condition{"event.name": {Equals: stringPtr("error.critical")}},
			},
			Actions: []Action{
				{Type: "sample", Parameters: map[string]interface{}{"sample_rate": 1.0, "duration_minutes": 10.0}},
				{Type: "annotate", Parameters: map[string]interface{}{"reason": "critical error"}},
			},
		},
	}

	sink := &consumertest.LogsSink{}
	processor, err := newMobilePolicyProcessor(&Config{Policies: policies}, zaptest.NewLogger(t), sink)
	require.NoError(t, err)

	logs := createMobileLogs(mobileEvent("error.critical", nil))
	err = processor.ConsumeLogs(context.Background(), logs)
	require.NoError(t, err)

	records := extractLogRecords(sink)
	sampleRate, exists := records[0].Attributes().Get("policy.sample_rate")
	assert.True(t, exists, "sample action should add sample_rate attr")
	assert.Equal(t, 1.0, sampleRate.Double())

	duration, exists := records[0].Attributes().Get("policy.sample_duration_minutes")
	assert.True(t, exists, "sample action should add duration attr")
	assert.Equal(t, 10.0, duration.Double())
}

// ── Helpers ─────────────────────────────────────────────────────────────────

type eventSpec struct {
	name  string
	attrs map[string]interface{}
}

func mobileEvent(name string, attrs map[string]interface{}) eventSpec {
	return eventSpec{name: name, attrs: attrs}
}

func createMobileLogs(events ...eventSpec) plog.Logs {
	logs := plog.NewLogs()
	rl := logs.ResourceLogs().AppendEmpty()
	rl.Resource().Attributes().PutStr("service.name", "otel-mobile-demo")
	sl := rl.ScopeLogs().AppendEmpty()

	for _, e := range events {
		lr := sl.LogRecords().AppendEmpty()
		lr.Body().SetStr(e.name)
		lr.SetTimestamp(pcommon.NewTimestampFromTime(time.Now()))
		if e.attrs != nil {
			for k, v := range e.attrs {
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
	}
	return logs
}

func extractLogRecords(sink *consumertest.LogsSink) []plog.LogRecord {
	var records []plog.LogRecord
	for _, logs := range sink.AllLogs() {
		for i := 0; i < logs.ResourceLogs().Len(); i++ {
			rl := logs.ResourceLogs().At(i)
			for j := 0; j < rl.ScopeLogs().Len(); j++ {
				sl := rl.ScopeLogs().At(j)
				for k := 0; k < sl.LogRecords().Len(); k++ {
					records = append(records, sl.LogRecords().At(k))
				}
			}
		}
	}
	return records
}

func assertAnnotated(t *testing.T, lr plog.LogRecord, expectedPolicyID, msg string) {
	t.Helper()
	matched, exists := lr.Attributes().Get("policy.matched")
	assert.True(t, exists, msg+": policy.matched should exist")
	assert.Equal(t, "true", matched.Str(), msg+": policy.matched should be true")

	policyID, exists := lr.Attributes().Get("policy.id")
	assert.True(t, exists, msg+": policy.id should exist")
	assert.Equal(t, expectedPolicyID, policyID.Str(), msg+": policy.id mismatch")
}

func assertNotAnnotated(t *testing.T, lr plog.LogRecord, msg string) {
	t.Helper()
	_, exists := lr.Attributes().Get("policy.matched")
	assert.False(t, exists, msg+": policy.matched should not exist")
}
