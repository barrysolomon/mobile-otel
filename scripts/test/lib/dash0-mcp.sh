#!/usr/bin/env bash
# Platform-neutral alias for the Dash0 MCP helper library.
#
# The canonical implementation still lives at `scripts/test/lib-ios/dash0-mcp.sh`
# for historical reasons (it shipped first with the iOS validate scripts).
# Nothing in that file is iOS-specific — it's pure JSON-RPC + jq + bash. Any
# platform's end-to-end validator should source this neutral path, so future
# renames/reorganization touch only one indirection.
#
# Usage:
#   source "$SCRIPT_DIR/lib/dash0-mcp.sh"
#
# Exposes: dash0_auth_token, dash0_dataset, dash0_mcp_query, dash0_row_count,
#          dash0_log_count, dash0_span_count, dash0_span_names,
#          dash0_logs_bodies, dash0_metric_names, dash0_span_parent_count,
#          iso_now, iso_minus_min, log, ok, fail, warn
#
# Requires: CONFIG_PATH, SERVICE_NAME set by the caller before sourcing.

_this_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$_this_dir/../lib-ios/dash0-mcp.sh"
