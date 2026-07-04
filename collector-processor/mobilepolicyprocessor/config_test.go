// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package mobilepolicyprocessor

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

// TestConfigValidation tests configuration validation logic
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
						Match: Match{
							LogicalOperator: "and",
							Attributes:      map[string]Condition{"key": {Equals: stringPtr("val")}},
						},
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
		{
			name: "empty match attributes",
			config: Config{
				Policies: []Policy{
					{
						ID:      "test",
						Enabled: true,
						Match: Match{
							LogicalOperator: "and",
							Attributes:      map[string]Condition{}, // Empty
						},
						Actions: []Action{{Type: "annotate"}},
					},
				},
			},
			expectError: true,
			errorMsg:    "at least one match attribute is required",
		},
		{
			name: "condition without operator",
			config: Config{
				Policies: []Policy{
					{
						ID:      "test",
						Enabled: true,
						Match: Match{
							LogicalOperator: "and",
							Attributes: map[string]Condition{
								"key": {}, // No operator
							},
						},
						Actions: []Action{{Type: "annotate"}},
					},
				},
			},
			expectError: true,
			errorMsg:    "at least one condition operator is required",
		},
		{
			name: "empty actions",
			config: Config{
				Policies: []Policy{
					{
						ID:      "test",
						Enabled: true,
						Match: Match{
							LogicalOperator: "and",
							Attributes:      map[string]Condition{"key": {Equals: stringPtr("val")}},
						},
						Actions: []Action{}, // Empty
					},
				},
			},
			expectError: true,
			errorMsg:    "at least one action is required",
		},
		{
			name: "action without type",
			config: Config{
				Policies: []Policy{
					{
						ID:      "test",
						Enabled: true,
						Match: Match{
							LogicalOperator: "and",
							Attributes:      map[string]Condition{"key": {Equals: stringPtr("val")}},
						},
						Actions: []Action{{Type: ""}}, // No type
					},
				},
			},
			expectError: true,
			errorMsg:    "type is required",
		},
		{
			name: "multiple valid policies",
			config: Config{
				Policies: []Policy{
					{
						ID:      "policy-1",
						Enabled: true,
						Match: Match{
							LogicalOperator: "and",
							Attributes:      map[string]Condition{"key1": {Equals: stringPtr("val1")}},
						},
						Actions: []Action{{Type: "annotate"}},
					},
					{
						ID:      "policy-2",
						Enabled: true,
						Match: Match{
							LogicalOperator: "or",
							Attributes:      map[string]Condition{"key2": {Gt: float64Ptr(100.0)}},
						},
						Actions: []Action{{Type: "sample"}},
					},
				},
			},
			expectError: false,
		},
		{
			name: "all condition operators valid",
			config: Config{
				Policies: []Policy{
					{
						ID:      "multi-condition",
						Enabled: true,
						Match: Match{
							LogicalOperator: "and",
							Attributes: map[string]Condition{
								"attr1": {Equals: stringPtr("value")},
								"attr2": {Gt: float64Ptr(10.0)},
								"attr3": {Lt: float64Ptr(100.0)},
								"attr4": {Gte: float64Ptr(0.0)},
								"attr5": {Lte: float64Ptr(1000.0)},
								"attr6": {Contains: stringPtr("substring")},
								"attr7": {Regex: stringPtr("^pattern$")},
							},
						},
						Actions: []Action{{Type: "annotate"}},
					},
				},
			},
			expectError: false,
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

// TestConditionOperators tests individual condition operator validation
func TestConditionOperators(t *testing.T) {
	tests := []struct {
		name      string
		condition Condition
		valid     bool
	}{
		{
			name:      "equals operator",
			condition: Condition{Equals: stringPtr("value")},
			valid:     true,
		},
		{
			name:      "gt operator",
			condition: Condition{Gt: float64Ptr(10.0)},
			valid:     true,
		},
		{
			name:      "lt operator",
			condition: Condition{Lt: float64Ptr(10.0)},
			valid:     true,
		},
		{
			name:      "gte operator",
			condition: Condition{Gte: float64Ptr(10.0)},
			valid:     true,
		},
		{
			name:      "lte operator",
			condition: Condition{Lte: float64Ptr(10.0)},
			valid:     true,
		},
		{
			name:      "contains operator",
			condition: Condition{Contains: stringPtr("substring")},
			valid:     true,
		},
		{
			name:      "regex operator",
			condition: Condition{Regex: stringPtr("^pattern$")},
			valid:     true,
		},
		{
			name:      "no operator",
			condition: Condition{},
			valid:     false,
		},
		{
			name: "multiple operators (valid)",
			condition: Condition{
				Gt: float64Ptr(10.0),
				Lt: float64Ptr(100.0),
			},
			valid: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := validateCondition("test_attr", tt.condition)
			if tt.valid {
				assert.NoError(t, err)
			} else {
				assert.Error(t, err)
			}
		})
	}
}

// TestLogicalOperators tests logical operator validation
func TestLogicalOperators(t *testing.T) {
	tests := []struct {
		name     string
		operator string
		valid    bool
	}{
		{"and operator", "and", true},
		{"or operator", "or", true},
		{"invalid xor", "xor", false},
		{"invalid not", "not", false},
		{"empty string", "", false},
		{"uppercase AND", "AND", false}, // Should be lowercase
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			config := Config{
				Policies: []Policy{
					{
						ID:      "test",
						Enabled: true,
						Match: Match{
							LogicalOperator: tt.operator,
							Attributes:      map[string]Condition{"key": {Equals: stringPtr("val")}},
						},
						Actions: []Action{{Type: "annotate"}},
					},
				},
			}

			err := config.Validate()
			if tt.valid {
				assert.NoError(t, err)
			} else {
				assert.Error(t, err)
			}
		})
	}
}

// TestActionTypes tests action type validation
func TestActionTypes(t *testing.T) {
	validActionTypes := []string{"annotate", "sample", "drop", "forward"}

	for _, actionType := range validActionTypes {
		t.Run("action type: "+actionType, func(t *testing.T) {
			config := Config{
				Policies: []Policy{
					{
						ID:      "test",
						Enabled: true,
						Match: Match{
							LogicalOperator: "and",
							Attributes:      map[string]Condition{"key": {Equals: stringPtr("val")}},
						},
						Actions: []Action{{Type: actionType}},
					},
				},
			}

			err := config.Validate()
			assert.NoError(t, err, "Valid action type should not error")
		})
	}
}

// TestConfigWithParameters tests actions with parameters
func TestConfigWithParameters(t *testing.T) {
	config := Config{
		Policies: []Policy{
			{
				ID:      "param-test",
				Enabled: true,
				Match: Match{
					LogicalOperator: "and",
					Attributes:      map[string]Condition{"key": {Equals: stringPtr("val")}},
				},
				Actions: []Action{
					{
						Type: "annotate",
						Parameters: map[string]interface{}{
							"trigger_id": "test-trigger",
							"reason":     "Test reason",
							"priority":   "high",
						},
					},
				},
			},
		},
	}

	err := config.Validate()
	assert.NoError(t, err)
}

// TestDisabledPolicies tests that disabled policies still pass validation
func TestDisabledPolicies(t *testing.T) {
	config := Config{
		Policies: []Policy{
			{
				ID:      "disabled",
				Enabled: false, // Disabled but still valid
				Match: Match{
					LogicalOperator: "and",
					Attributes:      map[string]Condition{"key": {Equals: stringPtr("val")}},
				},
				Actions: []Action{{Type: "annotate"}},
			},
		},
	}

	err := config.Validate()
	assert.NoError(t, err, "Disabled policies should still be valid")
}

// TestComplexPolicyScenarios tests realistic policy configurations
func TestComplexPolicyScenarios(t *testing.T) {
	config := Config{
		Policies: []Policy{
			{
				ID:      "ui-freeze-handler",
				Enabled: true,
				Match: Match{
					LogicalOperator: "and",
					Attributes: map[string]Condition{
						"event.name":  {Equals: stringPtr("ui.freeze")},
						"duration_ms": {Gt: float64Ptr(2000.0)},
					},
				},
				Actions: []Action{
					{
						Type: "annotate",
						Parameters: map[string]interface{}{
							"trigger_id":           "ui-freeze-handler",
							"reason":               "UI freeze detected over 2 seconds",
							"flush_window_minutes": 2,
						},
					},
				},
			},
			{
				ID:      "network-error-handler",
				Enabled: true,
				Match: Match{
					LogicalOperator: "and",
					Attributes: map[string]Condition{
						"event.name":      {Equals: stringPtr("http.error")},
						"http.status_code": {Gte: float64Ptr(500.0)},
						"http.route":      {Contains: stringPtr("/appointments")},
					},
				},
				Actions: []Action{
					{
						Type: "annotate",
						Parameters: map[string]interface{}{
							"trigger_id":           "network-error-handler",
							"reason":               "Server error on critical endpoint",
							"flush_window_minutes": 2,
						},
					},
					{
						Type: "sample",
						Parameters: map[string]interface{}{
							"sample_rate":      1.0,
							"duration_minutes": 10.0,
						},
					},
				},
			},
		},
	}

	err := config.Validate()
	assert.NoError(t, err, "Complex policy configuration should be valid")
}
