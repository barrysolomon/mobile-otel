#!/usr/bin/env bash
# Export target management: switch between local OTel Collector, Dash0 (from bundled config),
# or a custom endpoint.
# Source this file — do not execute directly.
# Requires: SERIAL, PACKAGE, DEMO_APP (from common.sh)

get_current_endpoint() {
  adb -s "$SERIAL" shell "run-as $PACKAGE cat shared_prefs/otel_config.xml" 2>/dev/null \
    | grep collector_endpoint | sed 's/.*>\(.*\)<.*/\1/'
}

get_export_target() {
  local endpoint
  endpoint=$(get_current_endpoint)
  if echo "$endpoint" | grep -q "10.0.2.2:14317"; then
    echo "local"
  elif echo "$endpoint" | grep -q "dash0.com"; then
    echo "dash0"
  elif [ -n "$endpoint" ]; then
    echo "custom"
  else
    echo "unknown"
  fi
}

get_export_target_label() {
  local endpoint
  endpoint=$(get_current_endpoint)
  local target
  target=$(get_export_target)
  case "$target" in
    local)   echo "Local Collector (10.0.2.2:14317)" ;;
    dash0)   echo "Dash0 ($endpoint)" ;;
    custom)  echo "Custom ($endpoint)" ;;
    *)       echo "Unknown" ;;
  esac
}

# ── Write prefs helpers ──────────────────────────────────────────────────────

_write_otel_prefs() {
  local endpoint="$1" auth="$2" service_name="$3" dataset="${4:-}"
  local dataset_line=""
  if [ -n "$dataset" ]; then
    dataset_line="  <string name=\"dataset\">$dataset</string>"
  fi
  local prefs="<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>
<map>
  <string name=\"collector_endpoint\">$endpoint</string>
  <string name=\"export_mode\">CONTINUOUS</string>
  <string name=\"service_name\">$service_name</string>
  <string name=\"service_version\">1.0.0</string>
  <string name=\"auth_token\">$auth</string>
$dataset_line
  <boolean name=\"config_loaded_from_bundle\" value=\"true\" />
  <boolean name=\"screenshot_enabled\" value=\"true\" />
  <boolean name=\"screenshot_on_screen_view\" value=\"true\" />
  <boolean name=\"wireframe_enabled\" value=\"true\" />
</map>"
  adb -s "$SERIAL" shell "run-as $PACKAGE mkdir -p shared_prefs"
  echo "$prefs" | adb -s "$SERIAL" shell "run-as $PACKAGE sh -c 'cat > shared_prefs/otel_config.xml'"
}

write_collector_prefs() {
  _write_otel_prefs "http://10.0.2.2:14317" "local-test" "validated-test"
}

write_dash0_prefs() {
  local config_file="$DEMO_APP/android/src/debug/assets/otel-config.json"
  if [ ! -f "$config_file" ]; then
    err "Dash0 config not found at $config_file"
    err "Copy from .json.template and fill in credentials"
    return 1
  fi
  local endpoint auth dataset
  # JSON uses camelCase keys
  endpoint=$(jq -r '.collectorEndpoint // .collector_endpoint // empty' "$config_file")
  auth=$(jq -r '.headers.Authorization // .auth_token // empty' "$config_file" | sed 's/^Bearer //')
  dataset=$(jq -r '.headers["Dash0-Dataset"] // .dataset // "otel-mobile"' "$config_file")

  if [ -z "$endpoint" ]; then
    err "No collectorEndpoint found in $config_file"
    return 1
  fi
  if [ -z "$auth" ]; then
    err "No auth token found in $config_file"
    return 1
  fi

  _write_otel_prefs "$endpoint" "$auth" "otel-mobile-demo" "$dataset"
}

write_custom_endpoint_prefs() {
  echo ""
  log "Configure custom endpoint"
  echo ""
  echo -n "  Endpoint (e.g. https://host:4317): "
  read -r endpoint
  if [ -z "$endpoint" ]; then
    err "No endpoint provided"
    return 1
  fi
  echo -n "  Auth token (or 'none'): "
  read -r auth
  if [ "$auth" = "none" ]; then auth=""; fi
  echo -n "  Service name [otel-mobile-demo]: "
  read -r svc
  if [ -z "$svc" ]; then svc="otel-mobile-demo"; fi
  echo -n "  Dataset [otel-mobile]: "
  read -r dataset
  if [ -z "$dataset" ]; then dataset="otel-mobile"; fi

  _write_otel_prefs "$endpoint" "$auth" "$svc" "$dataset"
}

# ── Target selection menu ────────────────────────────────────────────────────

select_export_target() {
  echo ""
  log "Select export target"
  echo ""
  echo "  Current: $(get_export_target_label)"
  echo ""
  echo "  1) Local OTel Collector (10.0.2.2:14317)"
  echo "  2) Dash0 (from otel-config.json)"
  echo "  3) Custom endpoint"
  echo "  q) Cancel"
  echo ""
  echo -n "  > "
  read -r choice

  case "$choice" in
    1)
      log "Switching to Local Collector"
      write_collector_prefs
      ok "Now exporting to Local Collector (10.0.2.2:14317)"
      ;;
    2)
      log "Switching to Dash0"
      write_dash0_prefs || return 1
      ok "Now exporting to Dash0"
      ;;
    3)
      write_custom_endpoint_prefs || return 1
      ok "Now exporting to custom endpoint"
      ;;
    q|"") return 0 ;;
    *) err "Unknown option: $choice"; return 1 ;;
  esac

  adb -s "$SERIAL" shell am force-stop "$PACKAGE"
  ok "App stopped — will use new config on next launch"
}

# Backward compat — toggle still works as quick flip between local/dash0
toggle_export_target() {
  local current
  current=$(get_export_target)
  echo ""
  if [ "$current" = "local" ]; then
    log "Switching export target: Local Collector → Dash0"
    write_dash0_prefs || return 1
    ok "Now exporting to Dash0"
  else
    log "Switching export target: → Local Collector"
    write_collector_prefs
    ok "Now exporting to Local Collector (10.0.2.2:14317)"
  fi
  adb -s "$SERIAL" shell am force-stop "$PACKAGE"
  ok "App stopped — will use new config on next launch"
}
