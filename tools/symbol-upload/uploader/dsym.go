// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package uploader

import (
	"debug/macho"
	"fmt"
	"os"
	"path/filepath"
)

// DSYMUUIDs extracts the Mach-O LC_UUID(s) from an iOS debug artifact, canonical
// lowercase (8-4-4-4-12). These are the SAME UUIDs the SDK's BuildIdReader emits
// as app.build.id, so the uploaded dSYM is matchable to its crashes.
//
// path may be either the DWARF binary itself or a *.dSYM bundle directory
// (MyApp.dSYM/Contents/Resources/DWARF/MyApp), which is what xcodebuild produces.
func DSYMUUIDs(path string) ([]string, error) {
	binPath, err := resolveDWARFBinary(path)
	if err != nil {
		return nil, err
	}

	f, err := macho.Open(binPath)
	if err != nil {
		return nil, fmt.Errorf("open Mach-O %s: %w", binPath, err)
	}
	defer f.Close()

	// debug/macho does not type LC_UUID (0x1b); it surfaces as raw LoadBytes with
	// layout [cmd uint32][cmdsize uint32][uuid 16]. Byte order matches the file.
	const lcUUID = 0x1b
	var uuids []string
	for _, load := range f.Loads {
		raw, ok := load.(macho.LoadBytes)
		if !ok || len(raw) < 24 {
			continue
		}
		if f.ByteOrder.Uint32(raw[0:4]) != lcUUID {
			continue
		}
		var id [16]byte
		copy(id[:], raw[8:24])
		uuids = append(uuids, canonicalUUID(id))
	}
	if len(uuids) == 0 {
		return nil, fmt.Errorf("no LC_UUID in %s", binPath)
	}
	return uuids, nil
}

// resolveDWARFBinary maps a .dSYM bundle to its inner DWARF binary; a plain file
// is returned unchanged.
func resolveDWARFBinary(path string) (string, error) {
	info, err := os.Stat(path)
	if err != nil {
		return "", fmt.Errorf("stat %s: %w", path, err)
	}
	if !info.IsDir() {
		return path, nil
	}
	dwarfDir := filepath.Join(path, "Contents", "Resources", "DWARF")
	entries, err := os.ReadDir(dwarfDir)
	if err != nil {
		return "", fmt.Errorf("read dSYM DWARF dir: %w", err)
	}
	for _, e := range entries {
		if !e.IsDir() {
			return filepath.Join(dwarfDir, e.Name()), nil
		}
	}
	return "", fmt.Errorf("no DWARF binary in %s", dwarfDir)
}

func canonicalUUID(b [16]byte) string {
	return fmt.Sprintf("%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
		b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7],
		b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15])
}
