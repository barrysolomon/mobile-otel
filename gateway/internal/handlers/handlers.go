package handlers

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strconv"
	"time"

	"github.com/mobile-observability/gateway/internal/config"
	"github.com/mobile-observability/gateway/internal/db"
	"github.com/mobile-observability/gateway/internal/otel"
)

type Handler struct {
	db        *db.Database
	exporter  *otel.LogExporter
	configMgr *config.Manager
}

type IngestRequest struct {
	Events []otel.MobileEvent `json:"events"`
}

type StatusRequest struct {
	DeviceID      string   `json:"device_id"`
	AppID         string   `json:"app_id"`
	SessionID     string   `json:"session_id"`
	BufferUsageMB float64  `json:"buffer_usage_mb"`
	LastTriggers  []string `json:"last_triggers"`
	ConfigVersion int      `json:"config_version"`
}

type PublishRequest struct {
	GraphJSON   string `json:"graph_json"`
	DSLJSON     string `json:"dsl_json"`
	PublishedBy string `json:"published_by"`
}

type RollbackRequest struct {
	Version int `json:"version"`
}

func NewHandler(database *db.Database, exporter *otel.LogExporter, configMgr *config.Manager) *Handler {
	return &Handler{
		db:        database,
		exporter:  exporter,
		configMgr: configMgr,
	}
}

// HandleIngest receives JSON batches of events from mobile devices
func (h *Handler) HandleIngest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "Failed to read request body", http.StatusBadRequest)
		return
	}
	defer r.Body.Close()

	var req IngestRequest
	if err := json.Unmarshal(body, &req); err != nil {
		http.Error(w, fmt.Sprintf("Invalid JSON: %v", err), http.StatusBadRequest)
		return
	}

	if len(req.Events) == 0 {
		http.Error(w, "No events provided", http.StatusBadRequest)
		return
	}

	// Export events to OTEL Collector
	if err := h.exporter.ExportEvents(r.Context(), req.Events); err != nil {
		log.Printf("Failed to export events: %v", err)
		http.Error(w, "Failed to export events", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":         "ok",
		"events_ingested": len(req.Events),
	})
}

// HandleGetConfig returns the active DSL configuration for a device
func (h *Handler) HandleGetConfig(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	appID := r.URL.Query().Get("app_id")
	deviceID := r.URL.Query().Get("device_id")

	if appID == "" || deviceID == "" {
		http.Error(w, "app_id and device_id required", http.StatusBadRequest)
		return
	}

	// Get active config
	dslConfig, err := h.configMgr.GetActiveConfig()
	if err != nil {
		log.Printf("Failed to get active config: %v", err)
		http.Error(w, "Failed to get config", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(dslConfig)
}

// HandleStatus receives device heartbeats
func (h *Handler) HandleStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "Failed to read request body", http.StatusBadRequest)
		return
	}
	defer r.Body.Close()

	var req StatusRequest
	if err := json.Unmarshal(body, &req); err != nil {
		http.Error(w, fmt.Sprintf("Invalid JSON: %v", err), http.StatusBadRequest)
		return
	}

	// Convert last_triggers to JSON string
	lastTriggersJSON := "[]"
	if len(req.LastTriggers) > 0 {
		triggersBytes, _ := json.Marshal(req.LastTriggers)
		lastTriggersJSON = string(triggersBytes)
	}

	// Record heartbeat
	heartbeat := &db.DeviceHeartbeat{
		DeviceID:      req.DeviceID,
		AppID:         req.AppID,
		SessionID:     req.SessionID,
		BufferUsageMB: req.BufferUsageMB,
		LastTriggers:  lastTriggersJSON,
		ConfigVersion: req.ConfigVersion,
		Timestamp:     time.Now(),
	}

	if err := h.db.RecordHeartbeat(heartbeat); err != nil {
		log.Printf("Failed to record heartbeat: %v", err)
		http.Error(w, "Failed to record heartbeat", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{
		"status": "ok",
	})
}

// HandlePublish publishes a new workflow version
func (h *Handler) HandlePublish(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "Failed to read request body", http.StatusBadRequest)
		return
	}
	defer r.Body.Close()

	var req PublishRequest
	if err := json.Unmarshal(body, &req); err != nil {
		http.Error(w, fmt.Sprintf("Invalid JSON: %v", err), http.StatusBadRequest)
		return
	}

	if req.GraphJSON == "" || req.DSLJSON == "" {
		http.Error(w, "graph_json and dsl_json required", http.StatusBadRequest)
		return
	}

	if req.PublishedBy == "" {
		req.PublishedBy = "admin"
	}

	// Publish config
	cv, err := h.configMgr.PublishWorkflow(req.GraphJSON, req.DSLJSON, req.PublishedBy)
	if err != nil {
		log.Printf("Failed to publish config: %v", err)
		http.Error(w, fmt.Sprintf("Failed to publish: %v", err), http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":  "ok",
		"version": cv.Version,
	})
}

// HandleRollback rolls back to a previous config version
func (h *Handler) HandleRollback(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "Failed to read request body", http.StatusBadRequest)
		return
	}
	defer r.Body.Close()

	var req RollbackRequest
	if err := json.Unmarshal(body, &req); err != nil {
		http.Error(w, fmt.Sprintf("Invalid JSON: %v", err), http.StatusBadRequest)
		return
	}

	if req.Version <= 0 {
		http.Error(w, "version required", http.StatusBadRequest)
		return
	}

	// Rollback
	if err := h.configMgr.RollbackToVersion(req.Version); err != nil {
		log.Printf("Failed to rollback: %v", err)
		http.Error(w, fmt.Sprintf("Failed to rollback: %v", err), http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"status":  "ok",
		"version": req.Version,
	})
}

// HandleVersions lists config versions
func (h *Handler) HandleVersions(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	limitStr := r.URL.Query().Get("limit")
	limit := 50
	if limitStr != "" {
		if parsedLimit, err := strconv.Atoi(limitStr); err == nil && parsedLimit > 0 {
			limit = parsedLimit
		}
	}

	versions, err := h.configMgr.ListVersions(limit)
	if err != nil {
		log.Printf("Failed to list versions: %v", err)
		http.Error(w, "Failed to list versions", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"versions": versions,
	})
}
