// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package db

import (
	"database/sql"
	"fmt"
	"time"

	_ "github.com/mattn/go-sqlite3"
)

type Database struct {
	conn *sql.DB
}

type DeviceHeartbeat struct {
	DeviceID      string    `json:"device_id"`
	AppID         string    `json:"app_id"`
	SessionID     string    `json:"session_id"`
	BufferUsageMB float64   `json:"buffer_usage_mb"`
	LastTriggers  string    `json:"last_triggers"` // JSON array
	ConfigVersion int       `json:"config_version"`
	Timestamp     time.Time `json:"timestamp"`
}

type ConfigVersion struct {
	Version     int       `json:"version"`
	GraphJSON   string    `json:"graph_json"`
	DSLJSON     string    `json:"dsl_json"`
	PublishedAt time.Time `json:"published_at"`
	PublishedBy string    `json:"published_by"`
	IsActive    bool      `json:"is_active"`
}

type Device struct {
	DeviceID               string    `json:"device_id"`
	DeviceToken            string    `json:"device_token"`
	DeviceGroup            string    `json:"device_group"`
	OSVersion              string    `json:"os_version"`
	AppVersion             string    `json:"app_version"`
	RegisteredAt           time.Time `json:"registered_at"`
	LastSeen               time.Time `json:"last_seen"`
	LastConfigFetch        time.Time `json:"last_config_fetch"`
	CurrentConfigVersion   int       `json:"current_config_version"`
	ConfigAppliedSuccessfully bool   `json:"config_applied_successfully"`
}

type DeviceGroup struct {
	Name        string    `json:"name"`
	Description string    `json:"description"`
	Environment string    `json:"environment"`
	CreatedAt   time.Time `json:"created_at"`
}

type OTELConfiguration struct {
	ID                     int       `json:"id"`
	DeviceGroup            string    `json:"device_group"`
	Version                string    `json:"version"`
	Protocol               string    `json:"protocol"`
	CollectorEndpoint      string    `json:"collector_endpoint"`
	AuthToken              string    `json:"auth_token"`
	Dataset                string    `json:"dataset"`
	RAMBufferSize          int       `json:"ram_buffer_size"`
	DiskBufferMB           int       `json:"disk_buffer_mb"`
	DiskBufferTTLHours     int       `json:"disk_buffer_ttl_hours"`
	ExportTimeoutSeconds   int       `json:"export_timeout_seconds"`
	MaxExportRetries       int       `json:"max_export_retries"`
	EnvironmentVars        string    `json:"environment_vars"` // JSON string
	FeatureFlags           string    `json:"feature_flags"`    // JSON string
	CreatedAt              time.Time `json:"created_at"`
	CreatedBy              string    `json:"created_by"`
	IsActive               bool      `json:"is_active"`
}

func NewDatabase(path string) (*Database, error) {
	conn, err := sql.Open("sqlite3", path)
	if err != nil {
		return nil, fmt.Errorf("failed to open database: %w", err)
	}

	if err := conn.Ping(); err != nil {
		return nil, fmt.Errorf("failed to ping database: %w", err)
	}

	db := &Database{conn: conn}

	if err := db.migrate(); err != nil {
		return nil, fmt.Errorf("failed to migrate database: %w", err)
	}

	return db, nil
}

func (db *Database) migrate() error {
	schema := `
	CREATE TABLE IF NOT EXISTS config_versions (
		version INTEGER PRIMARY KEY AUTOINCREMENT,
		graph_json TEXT NOT NULL,
		dsl_json TEXT NOT NULL,
		published_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
		published_by TEXT DEFAULT 'system',
		is_active BOOLEAN DEFAULT 0
	);

	CREATE TABLE IF NOT EXISTS device_heartbeats (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		device_id TEXT NOT NULL,
		app_id TEXT NOT NULL,
		session_id TEXT NOT NULL,
		buffer_usage_mb REAL,
		last_triggers TEXT,
		config_version INTEGER,
		timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP
	);

	CREATE TABLE IF NOT EXISTS device_groups (
		name TEXT PRIMARY KEY,
		description TEXT,
		environment TEXT,
		created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
	);

	CREATE TABLE IF NOT EXISTS devices (
		device_id TEXT PRIMARY KEY,
		device_token TEXT NOT NULL,
		device_group TEXT NOT NULL,
		os_version TEXT,
		app_version TEXT,
		registered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
		last_seen TIMESTAMP,
		last_config_fetch TIMESTAMP,
		current_config_version INTEGER,
		config_applied_successfully BOOLEAN DEFAULT 1,
		FOREIGN KEY (device_group) REFERENCES device_groups(name)
	);

	CREATE INDEX IF NOT EXISTS idx_device_timestamp
		ON device_heartbeats(device_id, timestamp DESC);

	CREATE INDEX IF NOT EXISTS idx_config_active
		ON config_versions(is_active) WHERE is_active = 1;

	CREATE INDEX IF NOT EXISTS idx_devices_group
		ON devices(device_group);

	CREATE INDEX IF NOT EXISTS idx_devices_last_seen
		ON devices(last_seen DESC);

	-- Insert default device group
	INSERT OR IGNORE INTO device_groups (name, description, environment)
	VALUES ('default', 'Default device group', 'development');

	CREATE TABLE IF NOT EXISTS otel_configurations (
		id INTEGER PRIMARY KEY AUTOINCREMENT,
		device_group TEXT NOT NULL,
		version TEXT NOT NULL,
		protocol TEXT NOT NULL,
		collector_endpoint TEXT NOT NULL,
		auth_token TEXT,
		dataset TEXT,
		ram_buffer_size INTEGER DEFAULT 5000,
		disk_buffer_mb INTEGER DEFAULT 50,
		disk_buffer_ttl_hours INTEGER DEFAULT 24,
		export_timeout_seconds INTEGER DEFAULT 30,
		max_export_retries INTEGER DEFAULT 3,
		environment_vars TEXT,
		feature_flags TEXT,
		created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
		created_by TEXT DEFAULT 'admin',
		is_active BOOLEAN DEFAULT 0,
		FOREIGN KEY (device_group) REFERENCES device_groups(name)
	);

	CREATE INDEX IF NOT EXISTS idx_otel_configs_group
		ON otel_configurations(device_group);

	CREATE INDEX IF NOT EXISTS idx_otel_configs_active
		ON otel_configurations(is_active) WHERE is_active = 1;
	`

	_, err := db.conn.Exec(schema)
	return err
}

func (db *Database) Close() error {
	return db.conn.Close()
}

// Config version operations

func (db *Database) GetActiveConfig() (*ConfigVersion, error) {
	var cv ConfigVersion
	err := db.conn.QueryRow(`
		SELECT version, graph_json, dsl_json, published_at, published_by, is_active
		FROM config_versions
		WHERE is_active = 1
		ORDER BY version DESC
		LIMIT 1
	`).Scan(&cv.Version, &cv.GraphJSON, &cv.DSLJSON, &cv.PublishedAt, &cv.PublishedBy, &cv.IsActive)

	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}

	return &cv, nil
}

func (db *Database) GetConfigByVersion(version int) (*ConfigVersion, error) {
	var cv ConfigVersion
	err := db.conn.QueryRow(`
		SELECT version, graph_json, dsl_json, published_at, published_by, is_active
		FROM config_versions
		WHERE version = ?
	`, version).Scan(&cv.Version, &cv.GraphJSON, &cv.DSLJSON, &cv.PublishedAt, &cv.PublishedBy, &cv.IsActive)

	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}

	return &cv, nil
}

func (db *Database) PublishConfig(graphJSON, dslJSON, publishedBy string) (*ConfigVersion, error) {
	tx, err := db.conn.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()

	// Deactivate current active config
	_, err = tx.Exec("UPDATE config_versions SET is_active = 0 WHERE is_active = 1")
	if err != nil {
		return nil, err
	}

	// Insert new config
	result, err := tx.Exec(`
		INSERT INTO config_versions (graph_json, dsl_json, published_by, is_active)
		VALUES (?, ?, ?, 1)
	`, graphJSON, dslJSON, publishedBy)
	if err != nil {
		return nil, err
	}

	version, err := result.LastInsertId()
	if err != nil {
		return nil, err
	}

	if err := tx.Commit(); err != nil {
		return nil, err
	}

	return db.GetConfigByVersion(int(version))
}

func (db *Database) RollbackToVersion(version int) error {
	tx, err := db.conn.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	// Check if version exists
	var exists bool
	err = tx.QueryRow("SELECT 1 FROM config_versions WHERE version = ?", version).Scan(&exists)
	if err != nil {
		return fmt.Errorf("version %d not found", version)
	}

	// Deactivate all
	_, err = tx.Exec("UPDATE config_versions SET is_active = 0")
	if err != nil {
		return err
	}

	// Activate target version
	_, err = tx.Exec("UPDATE config_versions SET is_active = 1 WHERE version = ?", version)
	if err != nil {
		return err
	}

	return tx.Commit()
}

func (db *Database) ListVersions(limit int) ([]ConfigVersion, error) {
	if limit <= 0 {
		limit = 50
	}

	rows, err := db.conn.Query(`
		SELECT version, graph_json, dsl_json, published_at, published_by, is_active
		FROM config_versions
		ORDER BY version DESC
		LIMIT ?
	`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var versions []ConfigVersion
	for rows.Next() {
		var cv ConfigVersion
		if err := rows.Scan(&cv.Version, &cv.GraphJSON, &cv.DSLJSON, &cv.PublishedAt, &cv.PublishedBy, &cv.IsActive); err != nil {
			return nil, err
		}
		versions = append(versions, cv)
	}

	return versions, rows.Err()
}

// Device heartbeat operations

func (db *Database) RecordHeartbeat(hb *DeviceHeartbeat) error {
	_, err := db.conn.Exec(`
		INSERT INTO device_heartbeats
		(device_id, app_id, session_id, buffer_usage_mb, last_triggers, config_version, timestamp)
		VALUES (?, ?, ?, ?, ?, ?, ?)
	`, hb.DeviceID, hb.AppID, hb.SessionID, hb.BufferUsageMB, hb.LastTriggers, hb.ConfigVersion, hb.Timestamp)

	return err
}

func (db *Database) GetRecentHeartbeats(limit int) ([]DeviceHeartbeat, error) {
	if limit <= 0 {
		limit = 100
	}

	rows, err := db.conn.Query(`
		SELECT device_id, app_id, session_id, buffer_usage_mb, last_triggers, config_version, timestamp
		FROM device_heartbeats
		ORDER BY timestamp DESC
		LIMIT ?
	`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var heartbeats []DeviceHeartbeat
	for rows.Next() {
		var hb DeviceHeartbeat
		if err := rows.Scan(&hb.DeviceID, &hb.AppID, &hb.SessionID, &hb.BufferUsageMB, &hb.LastTriggers, &hb.ConfigVersion, &hb.Timestamp); err != nil {
			return nil, err
		}
		heartbeats = append(heartbeats, hb)
	}

	return heartbeats, rows.Err()
}

func (db *Database) GetDeviceHeartbeats(deviceID string, limit int) ([]DeviceHeartbeat, error) {
	if limit <= 0 {
		limit = 100
	}

	rows, err := db.conn.Query(`
		SELECT device_id, app_id, session_id, buffer_usage_mb, last_triggers, config_version, timestamp
		FROM device_heartbeats
		WHERE device_id = ?
		ORDER BY timestamp DESC
		LIMIT ?
	`, deviceID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var heartbeats []DeviceHeartbeat
	for rows.Next() {
		var hb DeviceHeartbeat
		if err := rows.Scan(&hb.DeviceID, &hb.AppID, &hb.SessionID, &hb.BufferUsageMB, &hb.LastTriggers, &hb.ConfigVersion, &hb.Timestamp); err != nil {
			return nil, err
		}
		heartbeats = append(heartbeats, hb)
	}

	return heartbeats, rows.Err()
}

// Device management operations

func (db *Database) RegisterDevice(device *Device) error {
	_, err := db.conn.Exec(`
		INSERT INTO devices (device_id, device_token, device_group, os_version, app_version, registered_at, last_seen)
		VALUES (?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(device_id) DO UPDATE SET
			device_token = excluded.device_token,
			os_version = excluded.os_version,
			app_version = excluded.app_version,
			last_seen = excluded.last_seen
	`, device.DeviceID, device.DeviceToken, device.DeviceGroup, device.OSVersion, device.AppVersion, device.RegisteredAt, device.LastSeen)

	return err
}

func (db *Database) GetDevice(deviceID string) (*Device, error) {
	var d Device
	err := db.conn.QueryRow(`
		SELECT device_id, device_token, device_group, os_version, app_version,
		       registered_at, COALESCE(last_seen, registered_at),
		       COALESCE(last_config_fetch, registered_at),
		       COALESCE(current_config_version, 0), config_applied_successfully
		FROM devices
		WHERE device_id = ?
	`, deviceID).Scan(&d.DeviceID, &d.DeviceToken, &d.DeviceGroup, &d.OSVersion, &d.AppVersion,
		&d.RegisteredAt, &d.LastSeen, &d.LastConfigFetch, &d.CurrentConfigVersion, &d.ConfigAppliedSuccessfully)

	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}

	return &d, nil
}

func (db *Database) ListDevices(group string, limit, offset int) ([]Device, int, error) {
	if limit <= 0 {
		limit = 50
	}

	// Build query
	query := `
		SELECT device_id, device_token, device_group, os_version, app_version,
		       registered_at, COALESCE(last_seen, registered_at),
		       COALESCE(last_config_fetch, registered_at),
		       COALESCE(current_config_version, 0), config_applied_successfully
		FROM devices
	`
	countQuery := "SELECT COUNT(*) FROM devices"
	args := []interface{}{}

	if group != "" && group != "all" {
		query += " WHERE device_group = ?"
		countQuery += " WHERE device_group = ?"
		args = append(args, group)
	}

	// Get total count
	var total int
	err := db.conn.QueryRow(countQuery, args...).Scan(&total)
	if err != nil {
		return nil, 0, err
	}

	// Get paginated results
	query += " ORDER BY last_seen DESC LIMIT ? OFFSET ?"
	args = append(args, limit, offset)

	rows, err := db.conn.Query(query, args...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	var devices []Device
	for rows.Next() {
		var d Device
		if err := rows.Scan(&d.DeviceID, &d.DeviceToken, &d.DeviceGroup, &d.OSVersion, &d.AppVersion,
			&d.RegisteredAt, &d.LastSeen, &d.LastConfigFetch, &d.CurrentConfigVersion, &d.ConfigAppliedSuccessfully); err != nil {
			return nil, 0, err
		}
		devices = append(devices, d)
	}

	return devices, total, rows.Err()
}

func (db *Database) UpdateDeviceGroup(deviceID, group string) error {
	_, err := db.conn.Exec(`
		UPDATE devices
		SET device_group = ?
		WHERE device_id = ?
	`, group, deviceID)

	return err
}

func (db *Database) UpdateDeviceLastSeen(deviceID string) error {
	_, err := db.conn.Exec(`
		UPDATE devices
		SET last_seen = CURRENT_TIMESTAMP
		WHERE device_id = ?
	`, deviceID)

	return err
}

func (db *Database) UpdateDeviceConfigStatus(deviceID string, version int, success bool) error {
	_, err := db.conn.Exec(`
		UPDATE devices
		SET last_config_fetch = CURRENT_TIMESTAMP,
		    current_config_version = ?,
		    config_applied_successfully = ?
		WHERE device_id = ?
	`, version, success, deviceID)

	return err
}

// Device group operations

func (db *Database) CreateDeviceGroup(group *DeviceGroup) error {
	_, err := db.conn.Exec(`
		INSERT INTO device_groups (name, description, environment, created_at)
		VALUES (?, ?, ?, ?)
	`, group.Name, group.Description, group.Environment, group.CreatedAt)

	return err
}

func (db *Database) ListDeviceGroups() ([]DeviceGroup, error) {
	rows, err := db.conn.Query(`
		SELECT name, description, environment, created_at
		FROM device_groups
		ORDER BY name
	`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var groups []DeviceGroup
	for rows.Next() {
		var g DeviceGroup
		if err := rows.Scan(&g.Name, &g.Description, &g.Environment, &g.CreatedAt); err != nil {
			return nil, err
		}
		groups = append(groups, g)
	}

	return groups, rows.Err()
}

// OTEL Configuration operations

func (db *Database) CreateOTELConfig(config *OTELConfiguration) error {
	tx, err := db.conn.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	// Deactivate previous active config for this group
	_, err = tx.Exec(`
		UPDATE otel_configurations
		SET is_active = 0
		WHERE device_group = ? AND is_active = 1
	`, config.DeviceGroup)
	if err != nil {
		return err
	}

	// Insert new config
	result, err := tx.Exec(`
		INSERT INTO otel_configurations (
			device_group, version, protocol, collector_endpoint, auth_token, dataset,
			ram_buffer_size, disk_buffer_mb, disk_buffer_ttl_hours,
			export_timeout_seconds, max_export_retries,
			environment_vars, feature_flags, created_by, is_active
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
	`, config.DeviceGroup, config.Version, config.Protocol, config.CollectorEndpoint,
		config.AuthToken, config.Dataset, config.RAMBufferSize, config.DiskBufferMB,
		config.DiskBufferTTLHours, config.ExportTimeoutSeconds, config.MaxExportRetries,
		config.EnvironmentVars, config.FeatureFlags, config.CreatedBy, true)

	if err != nil {
		return err
	}

	id, err := result.LastInsertId()
	if err != nil {
		return err
	}

	if err := tx.Commit(); err != nil {
		return err
	}

	config.ID = int(id)
	return nil
}

func (db *Database) GetActiveOTELConfig(deviceGroup string) (*OTELConfiguration, error) {
	var config OTELConfiguration
	err := db.conn.QueryRow(`
		SELECT id, device_group, version, protocol, collector_endpoint, auth_token, dataset,
		       ram_buffer_size, disk_buffer_mb, disk_buffer_ttl_hours,
		       export_timeout_seconds, max_export_retries,
		       environment_vars, feature_flags, created_at, created_by, is_active
		FROM otel_configurations
		WHERE device_group = ? AND is_active = 1
		ORDER BY created_at DESC
		LIMIT 1
	`, deviceGroup).Scan(&config.ID, &config.DeviceGroup, &config.Version, &config.Protocol,
		&config.CollectorEndpoint, &config.AuthToken, &config.Dataset,
		&config.RAMBufferSize, &config.DiskBufferMB, &config.DiskBufferTTLHours,
		&config.ExportTimeoutSeconds, &config.MaxExportRetries,
		&config.EnvironmentVars, &config.FeatureFlags, &config.CreatedAt,
		&config.CreatedBy, &config.IsActive)

	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}

	return &config, nil
}

func (db *Database) ListOTELConfigs(deviceGroup string, limit int) ([]OTELConfiguration, error) {
	if limit <= 0 {
		limit = 50
	}

	query := `
		SELECT id, device_group, version, protocol, collector_endpoint, auth_token, dataset,
		       ram_buffer_size, disk_buffer_mb, disk_buffer_ttl_hours,
		       export_timeout_seconds, max_export_retries,
		       environment_vars, feature_flags, created_at, created_by, is_active
		FROM otel_configurations
	`
	args := []interface{}{}

	if deviceGroup != "" && deviceGroup != "all" {
		query += " WHERE device_group = ?"
		args = append(args, deviceGroup)
	}

	query += " ORDER BY created_at DESC LIMIT ?"
	args = append(args, limit)

	rows, err := db.conn.Query(query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var configs []OTELConfiguration
	for rows.Next() {
		var config OTELConfiguration
		if err := rows.Scan(&config.ID, &config.DeviceGroup, &config.Version, &config.Protocol,
			&config.CollectorEndpoint, &config.AuthToken, &config.Dataset,
			&config.RAMBufferSize, &config.DiskBufferMB, &config.DiskBufferTTLHours,
			&config.ExportTimeoutSeconds, &config.MaxExportRetries,
			&config.EnvironmentVars, &config.FeatureFlags, &config.CreatedAt,
			&config.CreatedBy, &config.IsActive); err != nil {
			return nil, err
		}
		configs = append(configs, config)
	}

	return configs, rows.Err()
}

func (db *Database) GetOTELConfigByID(id int) (*OTELConfiguration, error) {
	var config OTELConfiguration
	err := db.conn.QueryRow(`
		SELECT id, device_group, version, protocol, collector_endpoint, auth_token, dataset,
		       ram_buffer_size, disk_buffer_mb, disk_buffer_ttl_hours,
		       export_timeout_seconds, max_export_retries,
		       environment_vars, feature_flags, created_at, created_by, is_active
		FROM otel_configurations
		WHERE id = ?
	`, id).Scan(&config.ID, &config.DeviceGroup, &config.Version, &config.Protocol,
		&config.CollectorEndpoint, &config.AuthToken, &config.Dataset,
		&config.RAMBufferSize, &config.DiskBufferMB, &config.DiskBufferTTLHours,
		&config.ExportTimeoutSeconds, &config.MaxExportRetries,
		&config.EnvironmentVars, &config.FeatureFlags, &config.CreatedAt,
		&config.CreatedBy, &config.IsActive)

	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}

	return &config, nil
}

func (db *Database) ActivateOTELConfig(id int) error {
	tx, err := db.conn.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	// Get the config to find its device group
	var deviceGroup string
	err = tx.QueryRow("SELECT device_group FROM otel_configurations WHERE id = ?", id).Scan(&deviceGroup)
	if err != nil {
		return err
	}

	// Deactivate all configs for this group
	_, err = tx.Exec("UPDATE otel_configurations SET is_active = 0 WHERE device_group = ?", deviceGroup)
	if err != nil {
		return err
	}

	// Activate the specified config
	_, err = tx.Exec("UPDATE otel_configurations SET is_active = 1 WHERE id = ?", id)
	if err != nil {
		return err
	}

	return tx.Commit()
}
