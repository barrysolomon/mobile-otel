// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package uploader

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"testing"
)

// captureStore is a fake mapping store that records every PUT key.
type captureStore struct {
	mu   sync.Mutex
	puts []string
}

func (c *captureStore) server() *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodHead:
			w.WriteHeader(http.StatusNotFound)
		case http.MethodPut:
			c.mu.Lock()
			c.puts = append(c.puts, r.URL.Path)
			c.mu.Unlock()
			w.WriteHeader(http.StatusCreated)
		}
	}))
}

func TestUploadDSYMUploadsEachUUID(t *testing.T) {
	// A dSYM bundle whose DWARF binary carries one UUID.
	root := filepath.Join(t.TempDir(), "MyApp.dSYM")
	dwarfDir := filepath.Join(root, "Contents", "Resources", "DWARF")
	if err := os.MkdirAll(dwarfDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dwarfDir, "MyApp"), buildMachOWithUUID(t, testUUID), 0o644); err != nil {
		t.Fatal(err)
	}

	store := &captureStore{}
	srv := store.server()
	defer srv.Close()

	n, err := UploadDSYM(Config{Endpoint: srv.URL, Token: "t"}, root, "1.0.0")
	if err != nil {
		t.Fatalf("UploadDSYM: %v", err)
	}
	if n != 1 {
		t.Errorf("uploaded %d, want 1", n)
	}
	want := "/v1/symbol-mappings/ios/" + testUUIDCanonical
	if len(store.puts) != 1 || store.puts[0] != want {
		t.Errorf("puts = %v, want [%s]", store.puts, want)
	}
}

func TestUploadBundleKeysSourceMapByBundleHash(t *testing.T) {
	dir := t.TempDir()
	bundlePath := filepath.Join(dir, "main.jsbundle")
	mapPath := filepath.Join(dir, "main.jsbundle.map")
	if err := os.WriteFile(bundlePath, []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(mapPath, []byte(`{"version":3}`), 0o644); err != nil {
		t.Fatal(err)
	}

	store := &captureStore{}
	srv := store.server()
	defer srv.Close()

	id, err := UploadBundle(Config{Endpoint: srv.URL, Token: "t"}, bundlePath, mapPath, "1.0.0")
	if err != nil {
		t.Fatalf("UploadBundle: %v", err)
	}
	// sha256("hello")
	wantID := "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
	if id != wantID {
		t.Errorf("build id = %q, want %q", id, wantID)
	}
	want := "/v1/symbol-mappings/react-native/" + wantID
	if len(store.puts) != 1 || store.puts[0] != want {
		t.Errorf("puts = %v, want [%s]", store.puts, want)
	}
}
