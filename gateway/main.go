// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package main

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/mobile-observability/gateway/internal/config"
	"github.com/mobile-observability/gateway/internal/db"
	"github.com/mobile-observability/gateway/internal/handlers"
	"github.com/mobile-observability/gateway/internal/otel"
)

func main() {
	// Configuration
	port := getEnv("PORT", "8080")
	dbPath := getEnv("DB_PATH", "./data/gateway.db")
	collectorEndpoint := getEnv("OTEL_COLLECTOR_ENDPOINT", "otel-collector.mobile-observability.svc.cluster.local:4317")
	otelAuthToken := getEnv("OTEL_AUTH_TOKEN", "")

	log.Printf("Starting Mobile Observability Gateway")
	log.Printf("Port: %s", port)
	log.Printf("Database: %s", dbPath)
	log.Printf("Collector: %s", collectorEndpoint)
	if otelAuthToken != "" {
		log.Printf("OTEL Auth Token: configured")
	}

	// Ensure database directory exists
	dbDir := "./data"
	if err := os.MkdirAll(dbDir, 0755); err != nil {
		log.Fatalf("Failed to create database directory: %v", err)
	}

	// Initialize database
	database, err := db.NewDatabase(dbPath)
	if err != nil {
		log.Fatalf("Failed to initialize database: %v", err)
	}
	defer database.Close()

	// Initialize OTEL exporter
	ctx := context.Background()
	exporter, err := otel.NewLogExporter(ctx, collectorEndpoint, otelAuthToken)
	if err != nil {
		log.Fatalf("Failed to initialize OTEL exporter: %v", err)
	}
	defer exporter.Shutdown(ctx)

	// Initialize config manager
	configMgr := config.NewManager(database)

	// Initialize handlers
	h := handlers.NewHandler(database, exporter, configMgr)

	// Setup HTTP routes
	mux := http.NewServeMux()

	// Device endpoints
	mux.HandleFunc("POST /ingest", h.HandleIngest)
	mux.HandleFunc("GET /config", h.HandleGetConfig)
	mux.HandleFunc("POST /status", h.HandleStatus)

	// Device management endpoints
	mux.HandleFunc("POST /api/v1/devices/register", h.HandleRegisterDevice)
	mux.HandleFunc("GET /api/v1/devices", h.HandleListDevices)
	mux.HandleFunc("GET /api/v1/devices/detail", h.HandleGetDevice)
	mux.HandleFunc("PATCH /api/v1/devices/group", h.HandleUpdateDeviceGroup)
	mux.HandleFunc("GET /api/v1/device-groups", h.HandleListDeviceGroups)
	mux.HandleFunc("GET /api/v1/heartbeats", h.HandleListHeartbeats)

	// OTEL Configuration management endpoints
	mux.HandleFunc("POST /api/v1/otel-configs", h.HandleCreateOTELConfig)
	mux.HandleFunc("GET /api/v1/otel-configs", h.HandleListOTELConfigs)
	mux.HandleFunc("GET /api/v1/otel-configs/active", h.HandleGetActiveOTELConfig)
	mux.HandleFunc("POST /api/v1/otel-configs/activate", h.HandleActivateOTELConfig)
	mux.HandleFunc("GET /api/v1/otel-configs/rollout-status", h.HandleGetConfigRolloutStatus)

	// Admin endpoints
	mux.HandleFunc("POST /admin/publish", h.HandlePublish)
	mux.HandleFunc("POST /admin/rollback", h.HandleRollback)
	mux.HandleFunc("GET /admin/versions", h.HandleVersions)

	// Health check
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
	})

	// Create server
	server := &http.Server{
		Addr:         ":" + port,
		Handler:      loggingMiddleware(corsMiddleware(mux)),
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	// Graceful shutdown
	go func() {
		sigChan := make(chan os.Signal, 1)
		signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM)
		<-sigChan

		log.Println("Shutting down gracefully...")
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()

		if err := server.Shutdown(ctx); err != nil {
			log.Printf("Server shutdown error: %v", err)
		}
	}()

	// Start server
	log.Printf("Server listening on :%s", port)
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatalf("Server error: %v", err)
	}

	log.Println("Server stopped")
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}

func loggingMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		log.Printf("%s %s %s", r.Method, r.URL.Path, r.RemoteAddr)
		next.ServeHTTP(w, r)
		log.Printf("%s %s - %v", r.Method, r.URL.Path, time.Since(start))
	})
}

func corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type")

		if r.Method == "OPTIONS" {
			w.WriteHeader(http.StatusOK)
			return
		}

		next.ServeHTTP(w, r)
	})
}
