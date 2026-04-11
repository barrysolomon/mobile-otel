#!/usr/bin/env bash
# Export target management: switch between local OTel Collector and Dash0.
# Source this file — do not execute directly.
# Requires: SERIAL, PACKAGE, DEMO_APP (from common.sh)

get_export_target() {
  local endpoint
  endpoint=$(adb -s "$SERIAL" shell "run-as $PACKAGE cat shared_prefs/otel_config.xml" 2>/dev/null \
    | grep collector_endpoint | sed 's/.*>\(.*\)<.*/\1/')
  if echo "$endpoint" | grep -q "10.0.2.2:14317"; then
    echo "local"
  else
    echo "dash0"
  fi
}

get_export_target_label() {
  local target
  target=$(get_export_target)
  if [ "$target" = "local" ]; then
    echo "Local OTel Collector"
  else
    echo "Dash0"
  fi
}

write_collector_prefs() {
  local prefs='<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map>
  <string name="collector_endpoint">http://10.0.2.2:14317</string>
  <string name="export_mode">CONTINUOUS</string>
  <string name="service_name">validated-test</string>
  <string name="service_version">1.0.0</string>
  <string name="auth_token">local-test</string>
  <boolean name="config_loaded_from_bundle" value="true" />
</map>'
  adb -s "$SERIAL" shell "run-as $PACKAGE mkdir -p shared_prefs"
  echo "$prefs" | adb -s "$SERIAL" shell "run-as $PACKAGE sh -c 'cat > shared_prefs/otel_config.xml'"
}

write_dash0_prefs() {
  local config_file="$DEMO_APP/android/src/debug/assets/otel-config.json"
  if [ ! -f "$config_file" ]; then
    err "Dash0 config not found at $config_file"
    err "Copy from .json.template and fill in credentials"
    return 1
  fi
  local endpoint auth dataset
  endpoint=$(jq -r '.collector_endpoint' "$config_file")
  auth=$(jq -r '.auth_token' "$config_file")
  dataset=$(jq -r '.dataset // "otel-mobile"' "$config_file")
  local prefs="<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>
<map>
  <string name=\"collector_endpoint\">$endpoint</string>
  <string name=\"export_mode\">CONTINUOUS</string>
  <string name=\"service_name\">otel-mobile-demo</string>
  <string name=\"service_version\">1.0.0</string>
  <string name=\"auth_token\">$auth</string>
  <string name=\"dataset\">$dataset</string>
  <boolean name=\"config_loaded_from_bundle\" value=\"true\" />
</map>"
  adb -s "$SERIAL" shell "run-as $PACKAGE mkdir -p shared_prefs"
  echo "$prefs" | adb -s "$SERIAL" shell "run-as $PACKAGE sh -c 'cat > shared_prefs/otel_config.xml'"
}

toggle_export_target() {
  local current
  current=$(get_export_target)
  echo ""
  if [ "$current" = "local" ]; then
    log "Switching export target: Local Collector → Dash0"
    write_dash0_prefs
    ok "Now exporting to Dash0"
  else
    log "Switching export target: Dash0 → Local Collector"
    write_collector_prefs
    ok "Now exporting to Local Collector (10.0.2.2:14317)"
  fi
  adb -s "$SERIAL" shell am force-stop "$PACKAGE"
  ok "App stopped — will use new config on next launch"
}
