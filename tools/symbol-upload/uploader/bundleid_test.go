package uploader

import (
	"os"
	"path/filepath"
	"testing"
)

func TestBundleBuildIDIsSHA256OfBundleContent(t *testing.T) {
	path := filepath.Join(t.TempDir(), "main.jsbundle")
	if err := os.WriteFile(path, []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}

	id, err := BundleBuildID(path)
	if err != nil {
		t.Fatalf("BundleBuildID: %v", err)
	}
	// sha256("hello")
	want := "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
	if id != want {
		t.Errorf("BundleBuildID = %q, want %q", id, want)
	}
}

func TestBundleBuildIDErrorsOnMissingFile(t *testing.T) {
	if _, err := BundleBuildID("/nonexistent/main.jsbundle"); err == nil {
		t.Fatal("expected error for missing bundle")
	}
}
