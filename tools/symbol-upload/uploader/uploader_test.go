package uploader

import (
	"compress/gzip"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

// writeTempFile creates a file with the given content and returns its path.
func writeTempFile(t *testing.T, name, content string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), name)
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestUploadPutsGzippedArtifactKeyedByPlatformAndBuildID(t *testing.T) {
	mapping := writeTempFile(t, "mapping.txt", "com.example.a -> a.b:\n")

	var putPath, auth, dataset, contentEncoding, appVersion string
	var body []byte
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodHead:
			w.WriteHeader(http.StatusNotFound)
		case http.MethodPut:
			putPath = r.URL.Path
			auth = r.Header.Get("Authorization")
			dataset = r.Header.Get("Dash0-Dataset")
			contentEncoding = r.Header.Get("Content-Encoding")
			appVersion = r.URL.Query().Get("app_version")
			gz, err := gzip.NewReader(r.Body)
			if err != nil {
				t.Errorf("body is not gzip: %v", err)
				w.WriteHeader(http.StatusBadRequest)
				return
			}
			body, _ = io.ReadAll(gz)
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusMethodNotAllowed)
		}
	}))
	defer srv.Close()

	uploaded, err := Upload(Config{Endpoint: srv.URL, Token: "tok-123", Dataset: "otel-mobile"}, Artifact{
		Platform:   "android",
		BuildID:    "0a1b2c3d-0000-1111-2222-333344445555",
		Path:       mapping,
		AppVersion: "1.2.3",
	})
	if err != nil {
		t.Fatalf("Upload: %v", err)
	}
	if !uploaded {
		t.Fatal("expected uploaded=true")
	}
	if want := "/v1/symbol-mappings/android/0a1b2c3d-0000-1111-2222-333344445555"; putPath != want {
		t.Errorf("PUT path = %q, want %q", putPath, want)
	}
	if auth != "Bearer tok-123" {
		t.Errorf("Authorization = %q", auth)
	}
	if dataset != "otel-mobile" {
		t.Errorf("Dash0-Dataset = %q", dataset)
	}
	if contentEncoding != "gzip" {
		t.Errorf("Content-Encoding = %q", contentEncoding)
	}
	if appVersion != "1.2.3" {
		t.Errorf("app_version = %q", appVersion)
	}
	if string(body) != "com.example.a -> a.b:\n" {
		t.Errorf("uploaded body = %q", body)
	}
}

func TestUploadIsIdempotentWhenMappingAlreadyStored(t *testing.T) {
	mapping := writeTempFile(t, "mapping.txt", "x")

	putCalls := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.Method {
		case http.MethodHead:
			w.WriteHeader(http.StatusOK) // already stored
		case http.MethodPut:
			putCalls++
			w.WriteHeader(http.StatusCreated)
		}
	}))
	defer srv.Close()

	uploaded, err := Upload(Config{Endpoint: srv.URL, Token: "t"}, Artifact{
		Platform: "android", BuildID: "abc", Path: mapping,
	})
	if err != nil {
		t.Fatalf("Upload: %v", err)
	}
	if uploaded {
		t.Error("expected uploaded=false when artifact already stored")
	}
	if putCalls != 0 {
		t.Errorf("PUT called %d times, want 0", putCalls)
	}
}

func TestUploadValidatesRequiredFields(t *testing.T) {
	mapping := writeTempFile(t, "mapping.txt", "x")
	valid := Artifact{Platform: "android", BuildID: "abc", Path: mapping}

	cases := []struct {
		name string
		cfg  Config
		a    Artifact
	}{
		{"missing endpoint", Config{Token: "t"}, valid},
		{"missing token", Config{Endpoint: "http://x"}, valid},
		{"missing build id", Config{Endpoint: "http://x", Token: "t"}, Artifact{Platform: "android", Path: mapping}},
		{"missing platform", Config{Endpoint: "http://x", Token: "t"}, Artifact{BuildID: "abc", Path: mapping}},
		{"missing file", Config{Endpoint: "http://x", Token: "t"}, Artifact{Platform: "android", BuildID: "abc", Path: "/nonexistent/mapping.txt"}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := Upload(tc.cfg, tc.a); err == nil {
				t.Error("expected error, got nil")
			}
		})
	}
}

func TestUploadReturnsErrorOnServerFailure(t *testing.T) {
	mapping := writeTempFile(t, "mapping.txt", "x")
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodHead {
			w.WriteHeader(http.StatusNotFound)
			return
		}
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	if _, err := Upload(Config{Endpoint: srv.URL, Token: "t"}, Artifact{
		Platform: "android", BuildID: "abc", Path: mapping,
	}); err == nil {
		t.Fatal("expected error on 500, got nil")
	}
}
