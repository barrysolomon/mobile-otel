// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

// Command symbol-upload pushes crash-symbolication mappings to a mapping store,
// keyed by the same build id the SDK stamps into app.build.id at runtime.
//
// Usage:
//
//	symbol-upload android --mapping app/build/outputs/mapping/release/mapping.txt \
//	    --build-id $DASH0_BUILD_ID [--app-version 1.2.3]
//	symbol-upload ios --dsym MyApp.app.dSYM [--app-version 1.2.3]
//	symbol-upload react-native --bundle main.jsbundle --source-map main.jsbundle.map \
//	    [--app-version 1.2.3]
//
// Endpoint and token come from --endpoint/--token or DASH0_SYMBOL_ENDPOINT /
// DASH0_AUTH_TOKEN. Optional dataset via --dataset / DASH0_DATASET.
package main

import (
	"flag"
	"fmt"
	"os"

	"github.com/barrysolomon/mobile-otel/tools/symbol-upload/uploader"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	cmd := os.Args[1]
	args := os.Args[2:]

	var err error
	switch cmd {
	case "android":
		err = runAndroid(args)
	case "ios":
		err = runIOS(args)
	case "react-native", "rn":
		err = runRN(args)
	case "-h", "--help", "help":
		usage()
		return
	default:
		fmt.Fprintf(os.Stderr, "unknown command %q\n\n", cmd)
		usage()
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "error: %v\n", err)
		os.Exit(1)
	}
}

func runAndroid(args []string) error {
	fs := flag.NewFlagSet("android", flag.ExitOnError)
	cfg := configFlags(fs)
	mapping := fs.String("mapping", "", "path to R8/ProGuard mapping.txt")
	buildID := fs.String("build-id", "", "build id stamped into io.dash0.mobile.BUILD_ID")
	appVersion := fs.String("app-version", "", "optional app version")
	_ = fs.Parse(args)

	uploaded, err := uploader.UploadMapping(cfg.resolve(), *mapping, *buildID, *appVersion)
	if err != nil {
		return err
	}
	report(uploaded, "android", *buildID)
	return nil
}

func runIOS(args []string) error {
	fs := flag.NewFlagSet("ios", flag.ExitOnError)
	cfg := configFlags(fs)
	dsym := fs.String("dsym", "", "path to .dSYM bundle or DWARF binary")
	appVersion := fs.String("app-version", "", "optional app version")
	_ = fs.Parse(args)

	n, err := uploader.UploadDSYM(cfg.resolve(), *dsym, *appVersion)
	if err != nil {
		return err
	}
	fmt.Printf("uploaded %d dSYM UUID(s)\n", n)
	return nil
}

func runRN(args []string) error {
	fs := flag.NewFlagSet("react-native", flag.ExitOnError)
	cfg := configFlags(fs)
	bundle := fs.String("bundle", "", "path to the JS bundle")
	sourceMap := fs.String("source-map", "", "path to the source-map (.map)")
	appVersion := fs.String("app-version", "", "optional app version")
	_ = fs.Parse(args)

	id, err := uploader.UploadBundle(cfg.resolve(), *bundle, *sourceMap, *appVersion)
	if err != nil {
		return err
	}
	fmt.Printf("uploaded react-native source-map, build id %s\n", id)
	fmt.Printf("  pass this to Dash0Mobile.start({ buildId: %q })\n", id)
	return nil
}

type cliConfig struct {
	endpoint, token, dataset *string
}

func configFlags(fs *flag.FlagSet) cliConfig {
	return cliConfig{
		endpoint: fs.String("endpoint", os.Getenv("DASH0_SYMBOL_ENDPOINT"), "mapping store base URL (or DASH0_SYMBOL_ENDPOINT)"),
		token:    fs.String("token", os.Getenv("DASH0_AUTH_TOKEN"), "bearer token (or DASH0_AUTH_TOKEN)"),
		dataset:  fs.String("dataset", os.Getenv("DASH0_DATASET"), "optional dataset (or DASH0_DATASET)"),
	}
}

func (c cliConfig) resolve() uploader.Config {
	return uploader.Config{Endpoint: *c.endpoint, Token: *c.token, Dataset: *c.dataset}
}

func report(uploaded bool, platform, buildID string) {
	if uploaded {
		fmt.Printf("uploaded %s mapping for build id %s\n", platform, buildID)
	} else {
		fmt.Printf("skipped %s mapping for build id %s (already stored)\n", platform, buildID)
	}
}

func usage() {
	fmt.Fprint(os.Stderr, `symbol-upload — push crash-symbolication mappings keyed by app.build.id

Commands:
  android        upload R8/ProGuard mapping.txt   (--mapping --build-id [--app-version])
  ios            upload dSYM keyed by Mach-O UUID  (--dsym [--app-version])
  react-native   upload JS source-map keyed by bundle hash (--bundle --source-map [--app-version])

Common flags (or env):
  --endpoint   DASH0_SYMBOL_ENDPOINT   mapping store base URL
  --token      DASH0_AUTH_TOKEN        bearer token
  --dataset    DASH0_DATASET           optional dataset name
`)
}
