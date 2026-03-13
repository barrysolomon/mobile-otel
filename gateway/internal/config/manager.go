// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package config

import (
	"encoding/json"
	"fmt"

	"github.com/mobile-observability/gateway/internal/db"
)

type Manager struct {
	db *db.Database
}

// DSLConfig represents the compiled workflow configuration for devices
type DSLConfig struct {
	Version   int           `json:"version"`
	Limits    Limits        `json:"limits"`
	Workflows []Workflow    `json:"workflows"`
}

type Limits struct {
	DiskMB         int `json:"diskMb"`
	RAMEvents      int `json:"ramEvents"`
	RetentionHours int `json:"retentionHours"`
}

type Workflow struct {
	ID      string   `json:"id"`
	Enabled bool     `json:"enabled"`
	Trigger Trigger  `json:"trigger"`
	Actions []Action `json:"actions"`
}

type Trigger struct {
	Any []TriggerCondition `json:"any,omitempty"`
	All []TriggerCondition `json:"all,omitempty"`
}

type TriggerCondition struct {
	Event string      `json:"event,omitempty"`
	Where []Predicate `json:"where,omitempty"`
}

type Predicate struct {
	Attr  string      `json:"attr"`
	Op    string      `json:"op"` // ==, !=, >, >=, <, <=, contains, regex
	Value interface{} `json:"value"`
}

type Action struct {
	Type             string  `json:"type"` // annotate_trigger, flush_window, set_sampling
	TriggerID        string  `json:"trigger_id,omitempty"`
	Reason           string  `json:"reason,omitempty"`
	Minutes          int     `json:"minutes,omitempty"`
	Scope            string  `json:"scope,omitempty"` // session, device
	Rate             float64 `json:"rate,omitempty"`
	DurationMinutes  int     `json:"duration_minutes,omitempty"`
}

// GraphWorkflow represents the UI's React Flow format (for editing)
type GraphWorkflow struct {
	ID           string      `json:"id"`
	Name         string      `json:"name"`
	Enabled      bool        `json:"enabled"`
	EntryNodeID  string      `json:"entryNodeId"`
	Nodes        []GraphNode `json:"nodes"`
	Edges        []GraphEdge `json:"edges"`
}

type GraphNode struct {
	ID   string                 `json:"id"`
	Type string                 `json:"type"`
	Data map[string]interface{} `json:"data"`
}

type GraphEdge struct {
	ID     string `json:"id"`
	Source string `json:"source"`
	Target string `json:"target"`
}

func NewManager(database *db.Database) *Manager {
	return &Manager{db: database}
}

func (m *Manager) GetActiveConfig() (*DSLConfig, error) {
	cv, err := m.db.GetActiveConfig()
	if err != nil {
		return nil, err
	}

	if cv == nil {
		// Return default config if none exists
		return m.getDefaultConfig(), nil
	}

	var dslConfig DSLConfig
	if err := json.Unmarshal([]byte(cv.DSLJSON), &dslConfig); err != nil {
		return nil, fmt.Errorf("failed to unmarshal DSL config: %w", err)
	}

	return &dslConfig, nil
}

func (m *Manager) PublishWorkflow(graphJSON, dslJSON, publishedBy string) (*db.ConfigVersion, error) {
	// Validate DSL JSON
	var dslConfig DSLConfig
	if err := json.Unmarshal([]byte(dslJSON), &dslConfig); err != nil {
		return nil, fmt.Errorf("invalid DSL JSON: %w", err)
	}

	// Validate Graph JSON
	var graphWorkflows []GraphWorkflow
	if err := json.Unmarshal([]byte(graphJSON), &graphWorkflows); err != nil {
		return nil, fmt.Errorf("invalid Graph JSON: %w", err)
	}

	// Publish to database
	return m.db.PublishConfig(graphJSON, dslJSON, publishedBy)
}

func (m *Manager) RollbackToVersion(version int) error {
	return m.db.RollbackToVersion(version)
}

func (m *Manager) ListVersions(limit int) ([]db.ConfigVersion, error) {
	return m.db.ListVersions(limit)
}

func (m *Manager) getDefaultConfig() *DSLConfig {
	return &DSLConfig{
		Version: 1,
		Limits: Limits{
			DiskMB:         50,
			RAMEvents:      5000,
			RetentionHours: 24,
		},
		Workflows: []Workflow{
			{
				ID:      "default-crash",
				Enabled: true,
				Trigger: Trigger{
					Any: []TriggerCondition{
						{Event: "crash_marker"},
					},
				},
				Actions: []Action{
					{
						Type:      "annotate_trigger",
						TriggerID: "default-crash",
						Reason:    "crash detected",
					},
					{
						Type:    "flush_window",
						Minutes: 5,
						Scope:   "session",
					},
				},
			},
		},
	}
}
