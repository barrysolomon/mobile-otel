// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

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

// Handler holds the shared dependencies for all HTTP route handlers.
type Handler struct {
	db        *db.Database
	exporter  *otel.LogExporter
	configMgr *config.Manager
}

// IngestRequest is the JSON body accepted by the POST /ingest endpoint.
type IngestRequest struct {
	Events []otel.MobileEvent `json:"events"`
}

// StatusRequest is the JSON body accepted by the POST /status heartbeat endpoint.
type StatusRequest struct {
	DeviceID      string   `json:"device_id"`
	AppID         string   `json:"app_id"`
	SessionID     string   `json:"session_id"`
	BufferUsageMB float64  `json:"buffer_usage_mb"`
	LastTriggers  []string `json:"last_triggers"`
	ConfigVersion int      `json:"config_version"`
}

// PublishRequest is the JSON body accepted by the POST /admin/publish endpoint.
type PublishRequest struct {
	GraphJSON   string `json:"graph_json"`
	DSLJSON     string `json:"dsl_json"`
	PublishedBy string `json:"published_by"`
}

// RollbackRequest is the JSON body accepted by the POST /admin/rollback endpoint.
type RollbackRequest struct {
	Version int `json:"version"`
}

// RegisterDeviceRequest is the JSON body accepted by the POST /api/v1/devices/register endpoint.
type RegisterDeviceRequest struct {
	DeviceID    string `json:"device_id"`
	OSVersion   string `json:"os_version"`
	AppVersion  string `json:"app_version"`
	DeviceGroup string `json:"device_group"`
}

// UpdateDeviceGroupRequest is the JSON body accepted by the PATCH /api/v1/devices/group endpoint.
type UpdateDeviceGroupRequest struct {
	DeviceGroup string `json:"device_group"`
}

// CreateOTELConfigRequest is the JSON body accepted by the POST /api/v1/otel-configs endpoint.
type CreateOTELConfigRequest struct {
	DeviceGroup          string            `json:"device_group"`
	Protocol             string            `json:"protocol"`
	CollectorEndpoint    string            `json:"collector_endpoint"`
	AuthToken            string            `json:"auth_token"`
	Dataset              string            `json:"dataset"`
	RAMBufferSize        int               `json:"ram_buffer_size"`
	DiskBufferMB         int               `json:"disk_buffer_mb"`
	DiskBufferTTLHours   int               `json:"disk_buffer_ttl_hours"`
	ExportTimeoutSeconds int               `json:"export_timeout_seconds"`
	MaxExportRetries     int               `json:"max_export_retries"`
	EnvironmentVars      map[string]string `json:"environment_vars"`
	FeatureFlags         map[string]bool   `json:"feature_flags"`
}

// NewHandler creates a Handler wiring the database, OTEL exporter, and config manager.
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
	json.NewEncoder(w).Encode(map[string]any{
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

	// Auto-register device if not already registered
	device, err := h.db.GetDevice(req.DeviceID)
	if err != nil || device == nil {
		log.Printf("Device %s not registered, auto-registering from heartbeat", req.DeviceID)

		// Create device record with info from heartbeat
		newDevice := &db.Device{
			DeviceID:     req.DeviceID,
			DeviceToken:  fmt.Sprintf("auto_%s_%d", req.DeviceID, time.Now().Unix()),
			DeviceGroup:  "default", // Default group for auto-registered devices
			OSVersion:    "unknown",
			AppVersion:   "unknown",
			RegisteredAt: time.Now(),
			LastSeen:     time.Now(),
		}

		if err := h.db.RegisterDevice(newDevice); err != nil {
			log.Printf("Failed to auto-register device: %v", err)
			// Don't fail the heartbeat if registration fails
		} else {
			log.Printf("Device %s auto-registered successfully", req.DeviceID)
		}
	} else {
		// Update device last_seen timestamp
		if err := h.db.UpdateDeviceLastSeen(req.DeviceID); err != nil {
			log.Printf("Failed to update device last_seen: %v", err)
		}
	}

	// Check if device has applied the expected config for its group
	device, err = h.db.GetDevice(req.DeviceID)
	if err == nil && device != nil {
		// Get active config for device's group
		activeConfig, err := h.db.GetActiveOTELConfig(device.DeviceGroup)
		if err == nil && activeConfig != nil {
			expectedVersion, _ := strconv.Atoi(activeConfig.Version)
			configApplied := req.ConfigVersion == expectedVersion

			// Update device config status
			if err := h.db.UpdateDeviceConfigStatus(req.DeviceID, req.ConfigVersion, configApplied); err != nil {
				log.Printf("Failed to update device config status: %v", err)
			}
		}
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
	json.NewEncoder(w).Encode(map[string]any{
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
	json.NewEncoder(w).Encode(map[string]any{
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
	json.NewEncoder(w).Encode(map[string]any{
		"versions": versions,
	})
}

// Device management handlers

// HandleRegisterDevice registers a new device or updates existing one
func (h *Handler) HandleRegisterDevice(w http.ResponseWriter, r *http.Request) {
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

	var req RegisterDeviceRequest
	if err := json.Unmarshal(body, &req); err != nil {
		http.Error(w, fmt.Sprintf("Invalid JSON: %v", err), http.StatusBadRequest)
		return
	}

	if req.DeviceID == "" {
		http.Error(w, "device_id required", http.StatusBadRequest)
		return
	}

	if req.DeviceGroup == "" {
		req.DeviceGroup = "default"
	}

	// Generate device token (simple version - use crypto.rand in production)
	deviceToken := fmt.Sprintf("token_%s_%d", req.DeviceID, time.Now().Unix())

	device := &db.Device{
		DeviceID:    req.DeviceID,
		DeviceToken: deviceToken,
		DeviceGroup: req.DeviceGroup,
		OSVersion:   req.OSVersion,
		AppVersion:  req.AppVersion,
		RegisteredAt: time.Now(),
		LastSeen:     time.Now(),
	}

	if err := h.db.RegisterDevice(device); err != nil {
		log.Printf("Failed to register device: %v", err)
		http.Error(w, "Failed to register device", http.StatusInternalServerError)
		return
	}

	// Get active config version
	activeConfig, _ := h.configMgr.GetActiveConfig()
	configVersion := 0
	if activeConfig != nil {
		configVersion = activeConfig.Version
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(map[string]any{
		"device_token":    deviceToken,
		"config_url":      fmt.Sprintf("/config?app_id=%s&device_id=%s", req.AppVersion, req.DeviceID),
		"polling_interval": 300,
		"config_version":  configVersion,
	})
}

// HandleListDevices lists all devices with optional group filter
func (h *Handler) HandleListDevices(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	group := r.URL.Query().Get("group")
	limitStr := r.URL.Query().Get("limit")
	offsetStr := r.URL.Query().Get("offset")

	limit := 50
	if limitStr != "" {
		if parsedLimit, err := strconv.Atoi(limitStr); err == nil && parsedLimit > 0 {
			limit = parsedLimit
		}
	}

	offset := 0
	if offsetStr != "" {
		if parsedOffset, err := strconv.Atoi(offsetStr); err == nil && parsedOffset >= 0 {
			offset = parsedOffset
		}
	}

	devices, total, err := h.db.ListDevices(group, limit, offset)
	if err != nil {
		log.Printf("Failed to list devices: %v", err)
		http.Error(w, "Failed to list devices", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"devices": devices,
		"total":   total,
		"limit":   limit,
		"offset":  offset,
	})
}

// HandleGetDevice gets a specific device by ID
func (h *Handler) HandleGetDevice(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	deviceID := r.URL.Query().Get("device_id")
	if deviceID == "" {
		http.Error(w, "device_id required", http.StatusBadRequest)
		return
	}

	device, err := h.db.GetDevice(deviceID)
	if err != nil {
		log.Printf("Failed to get device: %v", err)
		http.Error(w, "Failed to get device", http.StatusInternalServerError)
		return
	}

	if device == nil {
		http.Error(w, "Device not found", http.StatusNotFound)
		return
	}

	// Get recent heartbeats
	heartbeats, _ := h.db.GetDeviceHeartbeats(deviceID, 10)

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"device":     device,
		"heartbeats": heartbeats,
	})
}

// HandleUpdateDeviceGroup updates a device's group
func (h *Handler) HandleUpdateDeviceGroup(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPatch {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	deviceID := r.URL.Query().Get("device_id")
	if deviceID == "" {
		http.Error(w, "device_id required", http.StatusBadRequest)
		return
	}

	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "Failed to read request body", http.StatusBadRequest)
		return
	}
	defer r.Body.Close()

	var req UpdateDeviceGroupRequest
	if err := json.Unmarshal(body, &req); err != nil {
		http.Error(w, fmt.Sprintf("Invalid JSON: %v", err), http.StatusBadRequest)
		return
	}

	if req.DeviceGroup == "" {
		http.Error(w, "device_group required", http.StatusBadRequest)
		return
	}

	if err := h.db.UpdateDeviceGroup(deviceID, req.DeviceGroup); err != nil {
		log.Printf("Failed to update device group: %v", err)
		http.Error(w, "Failed to update device group", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]string{
		"status": "ok",
	})
}

// HandleListDeviceGroups lists all device groups
func (h *Handler) HandleListDeviceGroups(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	groups, err := h.db.ListDeviceGroups()
	if err != nil {
		log.Printf("Failed to list device groups: %v", err)
		http.Error(w, "Failed to list device groups", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"groups": groups,
	})
}

// HandleListHeartbeats returns recent device heartbeats
func (h *Handler) HandleListHeartbeats(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	limitStr := r.URL.Query().Get("limit")
	limit := 100
	if limitStr != "" {
		if parsedLimit, err := strconv.Atoi(limitStr); err == nil && parsedLimit > 0 {
			limit = parsedLimit
		}
	}

	heartbeats, err := h.db.GetRecentHeartbeats(limit)
	if err != nil {
		log.Printf("Failed to get heartbeats: %v", err)
		http.Error(w, "Failed to get heartbeats", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"devices": heartbeats,
		"count":   len(heartbeats),
	})
}

// OTEL Configuration management handlers

// HandleCreateOTELConfig creates a new OTEL configuration for a device group
func (h *Handler) HandleCreateOTELConfig(w http.ResponseWriter, r *http.Request) {
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

	var req CreateOTELConfigRequest
	if err := json.Unmarshal(body, &req); err != nil {
		http.Error(w, fmt.Sprintf("Invalid JSON: %v", err), http.StatusBadRequest)
		return
	}

	// Validate required fields
	if req.DeviceGroup == "" {
		http.Error(w, "device_group required", http.StatusBadRequest)
		return
	}
	if req.Protocol == "" {
		req.Protocol = "grpc"
	}
	if req.CollectorEndpoint == "" {
		http.Error(w, "collector_endpoint required", http.StatusBadRequest)
		return
	}

	// Set defaults
	if req.RAMBufferSize == 0 {
		req.RAMBufferSize = 5000
	}
	if req.DiskBufferMB == 0 {
		req.DiskBufferMB = 50
	}
	if req.DiskBufferTTLHours == 0 {
		req.DiskBufferTTLHours = 24
	}
	if req.ExportTimeoutSeconds == 0 {
		req.ExportTimeoutSeconds = 30
	}
	if req.MaxExportRetries == 0 {
		req.MaxExportRetries = 3
	}

	// Convert maps to JSON strings
	envVarsJSON := "{}"
	if len(req.EnvironmentVars) > 0 {
		envVarsBytes, _ := json.Marshal(req.EnvironmentVars)
		envVarsJSON = string(envVarsBytes)
	}

	featureFlagsJSON := "{}"
	if len(req.FeatureFlags) > 0 {
		featureFlagsBytes, _ := json.Marshal(req.FeatureFlags)
		featureFlagsJSON = string(featureFlagsBytes)
	}

	// Generate version
	version := fmt.Sprintf("%d.0.0", time.Now().Unix())

	config := &db.OTELConfiguration{
		DeviceGroup:          req.DeviceGroup,
		Version:              version,
		Protocol:             req.Protocol,
		CollectorEndpoint:    req.CollectorEndpoint,
		AuthToken:            req.AuthToken,
		Dataset:              req.Dataset,
		RAMBufferSize:        req.RAMBufferSize,
		DiskBufferMB:         req.DiskBufferMB,
		DiskBufferTTLHours:   req.DiskBufferTTLHours,
		ExportTimeoutSeconds: req.ExportTimeoutSeconds,
		MaxExportRetries:     req.MaxExportRetries,
		EnvironmentVars:      envVarsJSON,
		FeatureFlags:         featureFlagsJSON,
		CreatedBy:            "admin", // TODO: Get from auth token
		CreatedAt:            time.Now(),
		IsActive:             true,
	}

	if err := h.db.CreateOTELConfig(config); err != nil {
		log.Printf("Failed to create OTEL config: %v", err)
		http.Error(w, "Failed to create configuration", http.StatusInternalServerError)
		return
	}

	// Count affected devices
	devices, _, _ := h.db.ListDevices(req.DeviceGroup, 1000, 0)

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(map[string]any{
		"id":               config.ID,
		"version":          config.Version,
		"device_group":     config.DeviceGroup,
		"affected_devices": len(devices),
	})
}

// HandleListOTELConfigs lists OTEL configurations
func (h *Handler) HandleListOTELConfigs(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	deviceGroup := r.URL.Query().Get("device_group")
	limitStr := r.URL.Query().Get("limit")

	limit := 50
	if limitStr != "" {
		if parsedLimit, err := strconv.Atoi(limitStr); err == nil && parsedLimit > 0 {
			limit = parsedLimit
		}
	}

	configs, err := h.db.ListOTELConfigs(deviceGroup, limit)
	if err != nil {
		log.Printf("Failed to list OTEL configs: %v", err)
		http.Error(w, "Failed to list configurations", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"configurations": configs,
		"count":          len(configs),
	})
}

// HandleGetActiveOTELConfig gets the active configuration for a device group
func (h *Handler) HandleGetActiveOTELConfig(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	deviceGroup := r.URL.Query().Get("device_group")
	if deviceGroup == "" {
		http.Error(w, "device_group required", http.StatusBadRequest)
		return
	}

	config, err := h.db.GetActiveOTELConfig(deviceGroup)
	if err != nil {
		log.Printf("Failed to get active OTEL config: %v", err)
		http.Error(w, "Failed to get configuration", http.StatusInternalServerError)
		return
	}

	if config == nil {
		http.Error(w, "No active configuration found", http.StatusNotFound)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(config)
}

// HandleActivateOTELConfig activates a specific configuration version
func (h *Handler) HandleActivateOTELConfig(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	idStr := r.URL.Query().Get("id")
	if idStr == "" {
		http.Error(w, "id required", http.StatusBadRequest)
		return
	}

	id, err := strconv.Atoi(idStr)
	if err != nil {
		http.Error(w, "Invalid id", http.StatusBadRequest)
		return
	}

	if err := h.db.ActivateOTELConfig(id); err != nil {
		log.Printf("Failed to activate OTEL config: %v", err)
		http.Error(w, "Failed to activate configuration", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"status": "ok",
		"id":     id,
	})
}

// HandleGetConfigRolloutStatus returns configuration rollout status for device groups
func (h *Handler) HandleGetConfigRolloutStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	// Get all device groups
	groups, err := h.db.ListDeviceGroups()
	if err != nil {
		log.Printf("Failed to get device groups: %v", err)
		http.Error(w, "Failed to get device groups", http.StatusInternalServerError)
		return
	}

	type RolloutStatus struct {
		DeviceGroup       string `json:"device_group"`
		ActiveVersion     string `json:"active_version"`
		TotalDevices      int    `json:"total_devices"`
		CompliantDevices  int    `json:"compliant_devices"`
		RolloutPercentage int    `json:"rollout_percentage"`
	}

	var statuses []RolloutStatus

	for _, group := range groups {
		// Get active config for this group
		activeConfig, err := h.db.GetActiveOTELConfig(group.Name)
		if err != nil {
			// No active config - skip this group
			continue
		}

		// Get all devices in this group
		devices, _, err := h.db.ListDevices(group.Name, 1000, 0)
		if err != nil {
			log.Printf("Failed to get devices for group %s: %v", group.Name, err)
			continue
		}

		totalDevices := len(devices)
		compliantDevices := 0

		expectedVersion, _ := strconv.Atoi(activeConfig.Version)

		for _, device := range devices {
			if device.CurrentConfigVersion == expectedVersion && device.ConfigAppliedSuccessfully {
				compliantDevices++
			}
		}

		percentage := 0
		if totalDevices > 0 {
			percentage = (compliantDevices * 100) / totalDevices
		}

		statuses = append(statuses, RolloutStatus{
			DeviceGroup:       group.Name,
			ActiveVersion:     activeConfig.Version,
			TotalDevices:      totalDevices,
			CompliantDevices:  compliantDevices,
			RolloutPercentage: percentage,
		})
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{
		"rollout_statuses": statuses,
	})
}
