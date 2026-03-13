// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package mobilepolicyprocessor

import (
	"context"
	"fmt"
	"regexp"
	"strconv"

	"go.opentelemetry.io/collector/component"
	"go.opentelemetry.io/collector/consumer"
	"go.opentelemetry.io/collector/pdata/pcommon"
	"go.opentelemetry.io/collector/pdata/plog"
	"go.uber.org/zap"
)

// mobilePolicyProcessor implements policy-based annotation and filtering for mobile telemetry
type mobilePolicyProcessor struct {
	logger   *zap.Logger
	config   *Config
	policies []Policy
	next     consumer.Logs
}

// newMobilePolicyProcessor creates a new mobile policy processor
func newMobilePolicyProcessor(
	cfg *Config,
	logger *zap.Logger,
	next consumer.Logs,
) (*mobilePolicyProcessor, error) {
	return &mobilePolicyProcessor{
		logger:   logger,
		config:   cfg,
		policies: cfg.Policies,
		next:     next,
	}, nil
}

// Start is called when the processor is started
func (mpp *mobilePolicyProcessor) Start(ctx context.Context, host component.Host) error {
	mpp.logger.Info("Starting mobile policy processor",
		zap.Int("policy_count", len(mpp.policies)))
	return nil
}

// Shutdown is called when the processor is shut down
func (mpp *mobilePolicyProcessor) Shutdown(ctx context.Context) error {
	mpp.logger.Info("Shutting down mobile policy processor")
	return nil
}

// Capabilities returns the consumer capabilities
func (mpp *mobilePolicyProcessor) Capabilities() consumer.Capabilities {
	return consumer.Capabilities{MutatesData: true}
}

// ConsumeLogs processes log records according to policies
func (mpp *mobilePolicyProcessor) ConsumeLogs(ctx context.Context, ld plog.Logs) error {
	// Process each resource log
	for i := 0; i < ld.ResourceLogs().Len(); i++ {
		resourceLog := ld.ResourceLogs().At(i)

		// Process each scope log
		for j := 0; j < resourceLog.ScopeLogs().Len(); j++ {
			scopeLog := resourceLog.ScopeLogs().At(j)

			// Process each log record
			for k := 0; k < scopeLog.LogRecords().Len(); k++ {
				logRecord := scopeLog.LogRecords().At(k)
				mpp.processLogRecord(logRecord, resourceLog.Resource().Attributes())
			}
		}
	}

	// Pass to next consumer
	return mpp.next.ConsumeLogs(ctx, ld)
}

// processLogRecord evaluates policies and annotates the log record
func (mpp *mobilePolicyProcessor) processLogRecord(lr plog.LogRecord, resourceAttrs pcommon.Map) {
	// Extract attributes for policy evaluation
	attrs := make(map[string]interface{})

	// Add log record body as "event.name"
	attrs["event.name"] = lr.Body().AsString()

	// Add all log record attributes
	lr.Attributes().Range(func(k string, v pcommon.Value) bool {
		attrs[k] = convertValue(v)
		return true
	})

	// Add resource attributes (prefixed with "resource.")
	resourceAttrs.Range(func(k string, v pcommon.Value) bool {
		attrs["resource."+k] = convertValue(v)
		return true
	})

	// Evaluate policies (first match wins for policy.id annotation)
	for _, policy := range mpp.policies {
		if mpp.evaluatePolicy(policy, attrs) {
			mpp.logger.Debug("Policy matched",
				zap.String("policy_id", policy.ID),
				zap.String("event.name", attrs["event.name"].(string)))

			// Annotate log record with policy match
			mpp.annotateLogRecord(lr, policy)
		}
	}
}

// evaluatePolicy checks if attributes match the policy's match conditions
func (mpp *mobilePolicyProcessor) evaluatePolicy(policy Policy, attrs map[string]interface{}) bool {
	if !policy.Enabled {
		return false
	}
	match := policy.Match
	results := make([]bool, 0, len(match.Attributes))

	// Evaluate each attribute condition
	for attrKey, condition := range match.Attributes {
		attrValue, exists := attrs[attrKey]
		if !exists {
			results = append(results, false)
			continue
		}

		matched := mpp.evaluateCondition(attrValue, condition)
		results = append(results, matched)
	}

	// Apply logical operator
	if match.LogicalOperator == "and" {
		for _, result := range results {
			if !result {
				return false
			}
		}
		return len(results) > 0
	} else if match.LogicalOperator == "or" {
		for _, result := range results {
			if result {
				return true
			}
		}
		return false
	}

	return false
}

// evaluateCondition checks if a value matches a condition
func (mpp *mobilePolicyProcessor) evaluateCondition(value interface{}, condition Condition) bool {
	// Equals
	if condition.Equals != nil {
		return fmt.Sprintf("%v", value) == *condition.Equals
	}

	// Greater than
	if condition.Gt != nil {
		numValue, err := toFloat64(value)
		if err != nil {
			return false
		}
		return numValue > *condition.Gt
	}

	// Less than
	if condition.Lt != nil {
		numValue, err := toFloat64(value)
		if err != nil {
			return false
		}
		return numValue < *condition.Lt
	}

	// Greater than or equal
	if condition.Gte != nil {
		numValue, err := toFloat64(value)
		if err != nil {
			return false
		}
		return numValue >= *condition.Gte
	}

	// Less than or equal
	if condition.Lte != nil {
		numValue, err := toFloat64(value)
		if err != nil {
			return false
		}
		return numValue <= *condition.Lte
	}

	// Contains
	if condition.Contains != nil {
		strValue := fmt.Sprintf("%v", value)
		return containsString(strValue, *condition.Contains)
	}

	// Regex
	if condition.Regex != nil {
		strValue := fmt.Sprintf("%v", value)
		matched, err := regexp.MatchString(*condition.Regex, strValue)
		if err != nil {
			mpp.logger.Warn("Invalid regex", zap.String("regex", *condition.Regex), zap.Error(err))
			return false
		}
		return matched
	}

	return false
}

// annotateLogRecord adds policy match annotations to the log record (first match wins)
func (mpp *mobilePolicyProcessor) annotateLogRecord(lr plog.LogRecord, policy Policy) {
	// Add policy match marker; preserve first-matching policy.id
	lr.Attributes().PutStr("policy.matched", "true")
	if _, exists := lr.Attributes().Get("policy.id"); !exists {
		lr.Attributes().PutStr("policy.id", policy.ID)
	}

	// Add action parameters as annotations
	for _, action := range policy.Actions {
		switch action.Type {
		case "annotate":
			// Add custom annotations from action parameters
			if params, ok := action.Parameters.(map[string]interface{}); ok {
				for k, v := range params {
					lr.Attributes().PutStr(fmt.Sprintf("policy.%s", k), fmt.Sprintf("%v", v))
				}
			}

		case "sample":
			// Add sampling annotation
			if params, ok := action.Parameters.(map[string]interface{}); ok {
				if sampleRate, ok := params["sample_rate"].(float64); ok {
					lr.Attributes().PutDouble("policy.sample_rate", sampleRate)
				}
				if duration, ok := params["duration_minutes"].(float64); ok {
					lr.Attributes().PutDouble("policy.sample_duration_minutes", duration)
				}
			}
		}
	}

	mpp.logger.Debug("Annotated log record",
		zap.String("policy_id", policy.ID),
		zap.Int("action_count", len(policy.Actions)))
}

// Helper functions

func convertValue(v pcommon.Value) interface{} {
	switch v.Type() {
	case pcommon.ValueTypeStr:
		return v.Str()
	case pcommon.ValueTypeInt:
		return v.Int()
	case pcommon.ValueTypeDouble:
		return v.Double()
	case pcommon.ValueTypeBool:
		return v.Bool()
	default:
		return v.AsString()
	}
}

func toFloat64(v interface{}) (float64, error) {
	switch val := v.(type) {
	case float64:
		return val, nil
	case float32:
		return float64(val), nil
	case int:
		return float64(val), nil
	case int64:
		return float64(val), nil
	case int32:
		return float64(val), nil
	case string:
		return strconv.ParseFloat(val, 64)
	default:
		return 0, fmt.Errorf("cannot convert %T to float64", v)
	}
}

func containsString(s, substr string) bool {
	return len(s) >= len(substr) && (s == substr || findSubstring(s, substr))
}

func findSubstring(s, substr string) bool {
	for i := 0; i <= len(s)-len(substr); i++ {
		if s[i:i+len(substr)] == substr {
			return true
		}
	}
	return false
}
