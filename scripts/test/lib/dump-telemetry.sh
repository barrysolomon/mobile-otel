#!/usr/bin/env bash
# Parse OTel Collector output and display a formatted timeline.
# Source this file — do not execute directly.
# Requires: OUTPUT_DIR (from common.sh), jq on PATH

dump_telemetry() {
  local logs_file="$OUTPUT_DIR/logs.json"
  local traces_file="$OUTPUT_DIR/traces.json"

  if [ ! -s "$logs_file" ] && [ ! -s "$traces_file" ]; then
    warn "No telemetry found in $OUTPUT_DIR"
    warn "Run a crash demo first, or check that the collector is running"
    return
  fi

  if ! command -v jq > /dev/null 2>&1; then
    err "jq is required for telemetry dump. Install with: brew install jq"
    return 1
  fi

  # Count events
  local log_count=0
  local span_count=0
  if [ -s "$logs_file" ]; then
    log_count=$(jq -s '[.[].resourceLogs[].scopeLogs[].logRecords[]] | length' "$logs_file" 2>/dev/null || echo 0)
  fi
  if [ -s "$traces_file" ]; then
    span_count=$(jq -s '[.[].resourceSpans[].scopeSpans[].spans[]] | length' "$traces_file" 2>/dev/null || echo 0)
  fi

  log "Telemetry received"
  echo "  $log_count log events, $span_count spans"
  echo ""

  # Dump logs as timeline
  if [ "$log_count" -gt 0 ]; then
    echo "  LOGS"
    echo "  ──────────────────────────────────────────────────────────"
    jq -rs '
      [.[].resourceLogs[].scopeLogs[].logRecords[]] |
      sort_by(.observedTimeUnixNano) |
      .[] |
      {
        time: (.observedTimeUnixNano | tonumber / 1e9 | strftime("%H:%M:%S")),
        body: .body.stringValue,
        attrs: ([.attributes[]? | {(.key): .value.stringValue // .value.intValue // .value.boolValue}] | add // {})
      } |
      "  \(.time)  \(.body)\t\(.attrs | to_entries | map("\(.key)=\(.value)") | join(" ") | .[0:60])"
    ' "$logs_file" 2>/dev/null || warn "  (could not parse logs — jq error)"
    echo ""
  fi

  # Dump spans
  if [ "$span_count" -gt 0 ]; then
    echo "  SPANS"
    echo "  ──────────────────────────────────────────────────────────"
    jq -rs '
      [.[].resourceSpans[].scopeSpans[].spans[]] |
      sort_by(.startTimeUnixNano) |
      .[] |
      {
        start: (.startTimeUnixNano | tonumber / 1e9 | strftime("%H:%M:%S")),
        end: (.endTimeUnixNano | tonumber / 1e9 | strftime("%H:%M:%S")),
        name: .name
      } |
      "  \(.start)-\(.end)  \(.name)"
    ' "$traces_file" 2>/dev/null || warn "  (could not parse traces — jq error)"
    echo ""
  fi

  # Summary
  echo "  SUMMARY"
  echo "  ──────────────────────────────────────────────────────────"
  if [ -s "$logs_file" ]; then
    local crash_count recovery_count precrash
    crash_count=$(jq -rs '[.[].resourceLogs[].scopeLogs[].logRecords[] | select(.body.stringValue == "app.crash")] | length' "$logs_file" 2>/dev/null || echo 0)
    recovery_count=$(jq -rs '[.[].resourceLogs[].scopeLogs[].logRecords[] | select(.body.stringValue == "app.recovery")] | length' "$logs_file" 2>/dev/null || echo 0)
    precrash=$((log_count - crash_count - recovery_count))
    echo "  Pre-crash events:  $precrash"
    echo "  Crash events:      $crash_count"
    echo "  Recovery events:   $recovery_count"
  fi
  echo "  Total:             $log_count logs, $span_count spans"
  echo ""
}
