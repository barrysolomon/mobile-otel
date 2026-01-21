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

	CREATE INDEX IF NOT EXISTS idx_device_timestamp
		ON device_heartbeats(device_id, timestamp DESC);

	CREATE INDEX IF NOT EXISTS idx_config_active
		ON config_versions(is_active) WHERE is_active = 1;
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
