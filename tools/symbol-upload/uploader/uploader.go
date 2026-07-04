// Package uploader pushes crash-symbolication mapping artifacts (R8 mapping.txt,
// iOS dSYM, RN source-maps) to a mapping store, keyed by the SAME build id the
// SDK stamps into app.build.id at runtime (see docs/design/symbolication.md).
//
// The store is content-addressed by (platform, build-id): a given build id is
// immutable, so an artifact that is already present is never re-uploaded. This
// keeps the tool safe to run on every CI build.
package uploader

import (
	"bytes"
	"compress/gzip"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
)

// Config carries the destination + auth for the mapping store.
type Config struct {
	// Endpoint is the base URL of the mapping store (e.g. a Dash0 ingress).
	Endpoint string
	// Token is the bearer auth token.
	Token string
	// Dataset is an optional Dash0 dataset name sent as the Dash0-Dataset header.
	Dataset string
}

// Artifact describes one mapping file to upload.
type Artifact struct {
	// Platform is "android", "ios", or "react-native".
	Platform string
	// BuildID matches the SDK's app.build.id for the build that produced Path.
	BuildID string
	// Path is the local mapping file (mapping.txt / DWARF / .map).
	Path string
	// AppVersion is optional; forwarded so the backend can disambiguate.
	AppVersion string
}

// Upload gzips the artifact and PUTs it to the mapping store, keyed by platform
// and build id. It first issues a HEAD: if the key already exists the upload is
// skipped and (false, nil) is returned. On a successful upload it returns
// (true, nil).
func Upload(cfg Config, a Artifact) (bool, error) {
	if err := validate(cfg, a); err != nil {
		return false, err
	}

	raw, err := os.ReadFile(a.Path)
	if err != nil {
		return false, fmt.Errorf("read artifact: %w", err)
	}

	key := fmt.Sprintf("%s/v1/symbol-mappings/%s/%s",
		strings.TrimRight(cfg.Endpoint, "/"),
		url.PathEscape(a.Platform),
		url.PathEscape(a.BuildID))
	if a.AppVersion != "" {
		key += "?app_version=" + url.QueryEscape(a.AppVersion)
	}

	client := &http.Client{}

	// Idempotency: skip if the store already has this build id.
	exists, err := headExists(client, cfg, key)
	if err != nil {
		return false, err
	}
	if exists {
		return false, nil
	}

	var body bytes.Buffer
	gz := gzip.NewWriter(&body)
	if _, err := gz.Write(raw); err != nil {
		return false, fmt.Errorf("gzip artifact: %w", err)
	}
	if err := gz.Close(); err != nil {
		return false, fmt.Errorf("gzip close: %w", err)
	}

	req, err := http.NewRequest(http.MethodPut, key, &body)
	if err != nil {
		return false, err
	}
	setHeaders(req, cfg)
	req.Header.Set("Content-Encoding", "gzip")
	req.Header.Set("Content-Type", "application/octet-stream")

	resp, err := client.Do(req)
	if err != nil {
		return false, fmt.Errorf("upload: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		msg, _ := io.ReadAll(io.LimitReader(resp.Body, 512))
		return false, fmt.Errorf("upload failed: %s: %s", resp.Status, strings.TrimSpace(string(msg)))
	}
	return true, nil
}

func headExists(client *http.Client, cfg Config, key string) (bool, error) {
	req, err := http.NewRequest(http.MethodHead, key, nil)
	if err != nil {
		return false, err
	}
	setHeaders(req, cfg)
	resp, err := client.Do(req)
	if err != nil {
		return false, fmt.Errorf("existence check: %w", err)
	}
	defer resp.Body.Close()
	return resp.StatusCode == http.StatusOK, nil
}

func setHeaders(req *http.Request, cfg Config) {
	req.Header.Set("Authorization", "Bearer "+cfg.Token)
	if cfg.Dataset != "" {
		req.Header.Set("Dash0-Dataset", cfg.Dataset)
	}
}

func validate(cfg Config, a Artifact) error {
	switch {
	case cfg.Endpoint == "":
		return fmt.Errorf("config: endpoint is required")
	case cfg.Token == "":
		return fmt.Errorf("config: token is required")
	case a.Platform == "":
		return fmt.Errorf("artifact: platform is required")
	case a.BuildID == "":
		return fmt.Errorf("artifact: build id is required")
	case a.Path == "":
		return fmt.Errorf("artifact: path is required")
	}
	if _, err := os.Stat(a.Path); err != nil {
		return fmt.Errorf("artifact: %w", err)
	}
	return nil
}
