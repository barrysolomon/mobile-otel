// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package mobilepolicyprocessor

import (
	"fmt"

	"go.opentelemetry.io/collector/component"
)

// Config defines configuration for the mobile policy processor
type Config struct {
	// Policies is the list of policy rules to evaluate
	Policies []Policy `mapstructure:"policies"`
}

// Policy represents a single mobile observability policy
type Policy struct {
	// ID is a unique identifier for the policy
	ID string `mapstructure:"id"`

	// Enabled indicates whether the policy is active
	Enabled bool `mapstructure:"enabled"`

	// Match defines the conditions that must be met
	Match Match `mapstructure:"match"`

	// Actions defines what to do when the policy matches
	Actions []Action `mapstructure:"actions"`
}

// Match defines the matching logic for a policy
type Match struct {
	// LogicalOperator combines attribute conditions ("and" or "or")
	LogicalOperator string `mapstructure:"logical_operator"`

	// Attributes maps attribute names to their match conditions
	Attributes map[string]Condition `mapstructure:"attributes"`
}

// Condition defines a single matching condition for an attribute
type Condition struct {
	// Equals checks for exact string match
	Equals *string `mapstructure:"equals,omitempty"`

	// Gt checks if numeric value is greater than
	Gt *float64 `mapstructure:"gt,omitempty"`

	// Lt checks if numeric value is less than
	Lt *float64 `mapstructure:"lt,omitempty"`

	// Gte checks if numeric value is greater than or equal
	Gte *float64 `mapstructure:"gte,omitempty"`

	// Lte checks if numeric value is less than or equal
	Lte *float64 `mapstructure:"lte,omitempty"`

	// Contains checks if string contains substring
	Contains *string `mapstructure:"contains,omitempty"`

	// Regex checks if string matches regular expression
	Regex *string `mapstructure:"regex,omitempty"`
}

// Action defines what to do when a policy matches
type Action struct {
	// Type specifies the action type ("annotate", "sample", "drop", etc.)
	Type string `mapstructure:"type"`

	// Parameters provides action-specific configuration
	Parameters interface{} `mapstructure:"parameters,omitempty"`
}

// Validate checks if the configuration is valid
func (cfg *Config) Validate() error {
	if len(cfg.Policies) == 0 {
		return fmt.Errorf("at least one policy must be defined")
	}

	for i, policy := range cfg.Policies {
		if policy.ID == "" {
			return fmt.Errorf("policy %d: id is required", i)
		}

		if policy.Match.LogicalOperator != "and" && policy.Match.LogicalOperator != "or" {
			return fmt.Errorf("policy %s: logical_operator must be 'and' or 'or'", policy.ID)
		}

		if len(policy.Match.Attributes) == 0 {
			return fmt.Errorf("policy %s: at least one match attribute is required", policy.ID)
		}

		for attrKey, condition := range policy.Match.Attributes {
			if err := validateCondition(attrKey, condition); err != nil {
				return fmt.Errorf("policy %s: %w", policy.ID, err)
			}
		}

		if len(policy.Actions) == 0 {
			return fmt.Errorf("policy %s: at least one action is required", policy.ID)
		}

		for j, action := range policy.Actions {
			if action.Type == "" {
				return fmt.Errorf("policy %s, action %d: type is required", policy.ID, j)
			}
		}
	}

	return nil
}

// validateCondition ensures a condition has at least one operator
func validateCondition(attrKey string, condition Condition) error {
	hasOperator := condition.Equals != nil ||
		condition.Gt != nil ||
		condition.Lt != nil ||
		condition.Gte != nil ||
		condition.Lte != nil ||
		condition.Contains != nil ||
		condition.Regex != nil

	if !hasOperator {
		return fmt.Errorf("attribute %s: at least one condition operator is required", attrKey)
	}

	return nil
}

var _ component.Config = (*Config)(nil)
