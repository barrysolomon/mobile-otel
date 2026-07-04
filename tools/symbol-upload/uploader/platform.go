// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package uploader

import "fmt"

// Platform key constants used to namespace stored mappings.
const (
	PlatformAndroid     = "android"
	PlatformIOS         = "ios"
	PlatformReactNative = "react-native"
)

// UploadMapping uploads an R8/ProGuard mapping.txt keyed by the given Android
// build id (the value stamped into io.dash0.mobile.BUILD_ID / app.build.id).
// Returns whether the artifact was newly uploaded.
func UploadMapping(cfg Config, mappingPath, buildID, appVersion string) (bool, error) {
	return Upload(cfg, Artifact{
		Platform:   PlatformAndroid,
		BuildID:    buildID,
		Path:       mappingPath,
		AppVersion: appVersion,
	})
}

// UploadDSYM extracts every Mach-O UUID from a dSYM (bundle dir or DWARF binary)
// and uploads the debug binary once per UUID, since a fat/multi-arch image can
// carry several. Returns the number of artifacts newly uploaded.
func UploadDSYM(cfg Config, dsymPath, appVersion string) (int, error) {
	uuids, err := DSYMUUIDs(dsymPath)
	if err != nil {
		return 0, err
	}
	binPath, err := resolveDWARFBinary(dsymPath)
	if err != nil {
		return 0, err
	}
	uploaded := 0
	for _, u := range uuids {
		ok, err := Upload(cfg, Artifact{
			Platform:   PlatformIOS,
			BuildID:    u,
			Path:       binPath,
			AppVersion: appVersion,
		})
		if err != nil {
			return uploaded, fmt.Errorf("upload dSYM %s: %w", u, err)
		}
		if ok {
			uploaded++
		}
	}
	return uploaded, nil
}

// UploadBundle derives the RN build id from the JS bundle's content hash and
// uploads the source-map keyed by it. The same id must be passed to
// Dash0Mobile.start({ buildId }) so runtime crashes match this source-map.
// Returns the derived build id.
func UploadBundle(cfg Config, bundlePath, sourceMapPath, appVersion string) (string, error) {
	id, err := BundleBuildID(bundlePath)
	if err != nil {
		return "", err
	}
	if _, err := Upload(cfg, Artifact{
		Platform:   PlatformReactNative,
		BuildID:    id,
		Path:       sourceMapPath,
		AppVersion: appVersion,
	}); err != nil {
		return id, err
	}
	return id, nil
}
