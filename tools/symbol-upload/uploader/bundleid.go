// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package uploader

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
)

// BundleBuildID computes a stable build id for a React Native JS bundle as the
// hex SHA-256 of its content. A source-map upload is keyed by this id, and the
// RN app forwards the same value via Dash0Mobile.start({ buildId }) so minified
// JS stacks are matchable to the source-map that resolves them.
func BundleBuildID(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", fmt.Errorf("open bundle: %w", err)
	}
	defer f.Close()

	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", fmt.Errorf("hash bundle: %w", err)
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}
