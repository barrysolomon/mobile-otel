#!/usr/bin/env bash
# UAT cell runner — single-cell execution.
# Phase 0: supports Android-native + CONT + online + no-crash only.
# Subsequent tasks generalize this.

set -uo pipefail

usage() {
    cat <<EOF
Usage: $0 \\
  --platform=android-native \\
  --mode=cont|cond|hyb \\
  --connectivity=online|offline \\
  --crash=no|yes \\
  [--run-id=<uuid>] \\
  [--evidence-dir=<path>] \\
  [--keep-app]
EOF
}

# --- Arg parsing ---
PLATFORM=""
MODE=""
CONNECTIVITY=""
CRASH=""
RUN_ID=""
EVIDENCE_DIR=""
KEEP_APP=0

for arg in "$@"; do
    case "$arg" in
        --platform=*)     PLATFORM="${arg#*=}" ;;
        --mode=*)         MODE="${arg#*=}" ;;
        --connectivity=*) CONNECTIVITY="${arg#*=}" ;;
        --crash=*)        CRASH="${arg#*=}" ;;
        --run-id=*)       RUN_ID="${arg#*=}" ;;
        --evidence-dir=*) EVIDENCE_DIR="${arg#*=}" ;;
        --keep-app)       KEEP_APP=1 ;;
        -h|--help)        usage; exit 0 ;;
        *) echo "Unknown arg: $arg" >&2; usage; exit 2 ;;
    esac
done

[[ -n "$PLATFORM" && -n "$MODE" && -n "$CONNECTIVITY" && -n "$CRASH" ]] || { usage; exit 2; }

# --- Setup ---
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UAT_REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
export UAT_REPO_ROOT

# RUN_ID groups cells of one matrix run (shared evidence dir).
# CELL_UUID is per-cell (shared between original and recovery launch within
# this cell). Earlier versions reused RUN_ID as cell_id, which collided
# across cells in a matrix run and made queries unfilterable.
RUN_ID="${RUN_ID:-$(uuidgen | tr 'A-Z' 'a-z')}"
CELL_UUID="$(uuidgen | tr 'A-Z' 'a-z')"
ORIGINAL_CELL_ID="${CELL_UUID}"
RECOVERY_CELL_ID="${CELL_UUID}-recov"
EVIDENCE_DIR="${EVIDENCE_DIR:-${SCRIPT_DIR}/evidence/${RUN_ID}}"
mkdir -p "$EVIDENCE_DIR"
EVIDENCE_FILE="${EVIDENCE_DIR}/${PLATFORM}-${MODE}-${CONNECTIVITY}-${CRASH}.jsonl"
export UAT_EVIDENCE_FILE="$EVIDENCE_FILE"
: > "$EVIDENCE_FILE"  # truncate

echo "[UAT] cell=${PLATFORM}/${MODE}/${CONNECTIVITY}/${CRASH} cell_id=${ORIGINAL_CELL_ID}"
echo "[UAT] evidence=${EVIDENCE_FILE}"

# --- Source platform primitives ---
case "$PLATFORM" in
    android-native) source "${SCRIPT_DIR}/lib-uat-platform-android.sh" ;;
    *) echo "ERROR: platform $PLATFORM not yet supported" >&2; exit 3 ;;
esac

source "${SCRIPT_DIR}/lib-uat-assertions.sh"

# Per-platform service name resolution.
__uat_service_name_for_platform() {
    case "$1" in
        android-native|rn-android) echo "otel-android-astronomy-shop" ;;
        ios-native|rn-ios) echo "otel-ios-astronomy-shop" ;;
        *) echo "ERROR: unknown platform: $1" >&2; return 1 ;;
    esac
}
SERVICE_NAME=$(__uat_service_name_for_platform "$PLATFORM")

# --- Trigger sequence ---

# t=0: install + launch with cell_id
uat::install "$MODE"
uat::launch "$MODE" "$ORIGINAL_CELL_ID"
sleep 3

# t=10: lifecycle cycle 1
uat::cycle_lifecycle "$MODE"
sleep 5

# t=20: lifecycle cycle 2
uat::cycle_lifecycle "$MODE"
sleep 5

# t=30: go offline (only if connectivity=offline)
if [[ "$CONNECTIVITY" == "offline" ]]; then
    uat::offline
    sleep 2
fi

# t=40: app-driven GET attempt (relies on app emitting periodic GETs;
# in the upstream-demo-app this happens on resume — we trigger one more
# foreground cycle to provoke it)
if [[ "$CONNECTIVITY" == "offline" ]]; then
    uat::cycle_lifecycle "$MODE"
    sleep 5
fi

# t=50: trigger crash (only if crash=yes)
if [[ "$CRASH" == "yes" ]]; then
    uat::trigger_crash "$MODE"
    sleep 3
fi

# t=60: go online (only if connectivity=offline)
if [[ "$CONNECTIVITY" == "offline" ]]; then
    uat::online
    sleep 5
fi

# t=70: relaunch (only if crash=yes OR connectivity=offline)
if [[ "$CRASH" == "yes" || "$CONNECTIVITY" == "offline" ]]; then
    uat::launch "$MODE" "$RECOVERY_CELL_ID"
    sleep 8
fi

# Cell 7 disk probe — run BEFORE relaunch erases buffer state
# (cell 7 has connectivity=offline, crash=no; no relaunch happens)
DISK_BUFFER_COUNT=0
if [[ "$CONNECTIVITY" == "offline" && "$CRASH" == "no" && "$MODE" == "cond" ]]; then
    DISK_BUFFER_COUNT=$(uat::probe_disk_buffer "$MODE")
fi

# t=90: Dash0 ingestion grace period
# Must exceed CONT mode's 30s log flush interval. Tighter values race the
# flush (verified 2026-05-05) — records arrive in Dash0 *after* assertions
# already read 0.
sleep 40

# --- Dash0 query batch ---
QUERY_FROM="now-3m"

# Each filter clause is a separate --filter; the dash0 CLI ANDs them.
# A single --filter with " and " inside the string is silently broken
# (verified 2026-05-05: matches zero records even when each clause matches
# many).  See docs/superpowers/specs/2026-05-01-uat-matrix-design.md.
run_logs_query() {
    local args=()
    for clause in "$@"; do args+=(--filter "$clause"); done
    dash0 -X logs query "${args[@]}" --from "$QUERY_FROM" -o json 2>/dev/null \
        | python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print(0); sys.exit(0)
n = sum(len(s.get("logRecords", []))
        for r in d.get("resourceLogs", [])
        for s in r.get("scopeLogs", []))
print(n)
'
}

run_spans_query() {
    local args=()
    for clause in "$@"; do args+=(--filter "$clause"); done
    dash0 -X spans query "${args[@]}" --from "$QUERY_FROM" -o json 2>/dev/null \
        | python3 -c '
import sys, json
try:
    d = json.load(sys.stdin)
except Exception:
    print(0); sys.exit(0)
n = sum(len(s.get("spans", []))
        for r in d.get("resourceSpans", [])
        for s in r.get("scopeSpans", []))
print(n)
'
}

# Recovery query uses recovery_cell_id (per spec §7).
RECOVERY_FOR_CELL="$ORIGINAL_CELL_ID"
if [[ "$CRASH" == "yes" || "$CONNECTIVITY" == "offline" ]]; then
    RECOVERY_FOR_CELL="$RECOVERY_CELL_ID"
fi

LIFE_FG=$(run_logs_query "service.name is ${SERVICE_NAME}" "event.name is app.foreground" "dash0.test.cell_id is ${ORIGINAL_CELL_ID}")
LIFE_BG=$(run_logs_query "service.name is ${SERVICE_NAME}" "event.name is app.background" "dash0.test.cell_id is ${ORIGINAL_CELL_ID}")
NET=$(run_spans_query "service.name is ${SERVICE_NAME}" "http.request.method is GET" "dash0.test.cell_id is ${ORIGINAL_CELL_ID}")
CRASH_COUNT=$(run_logs_query "service.name is ${SERVICE_NAME}" "event.name is app.crash" "dash0.test.cell_id is ${ORIGINAL_CELL_ID}")
RECOVERY_COUNT=$(run_logs_query "service.name is ${SERVICE_NAME}" "event.name is app.recovery_start" "dash0.test.cell_id is ${RECOVERY_FOR_CELL}")
PRESENCE=$(run_logs_query "service.name is ${SERVICE_NAME}" "dash0.test.cell_id is ${ORIGINAL_CELL_ID}")

EXIT=0

# Map (mode, connectivity, crash) to assertions.
KEY="${MODE}-${CONNECTIVITY}-${CRASH}"
case "$KEY" in
    cont-online-no)
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        must::ge "lifecycle_bg" "$LIFE_BG" 2 || EXIT=1
        must::zero "no_crash" "$CRASH_COUNT" || EXIT=1
        must::zero "no_recovery" "$RECOVERY_COUNT" || EXIT=1
        warn::eq "fg_exact" "$LIFE_FG" 3
        warn::eq "bg_exact" "$LIFE_BG" 2
        ;;
    cont-online-yes)
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        must::eq "crash_present" "$CRASH_COUNT" 1 || EXIT=1
        ;;
    cont-offline-no)
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        must::eq "recovery_present" "$RECOVERY_COUNT" 1 || EXIT=1
        must::zero "no_crash" "$CRASH_COUNT" || EXIT=1
        ;;
    cont-offline-yes)
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        must::eq "recovery_present" "$RECOVERY_COUNT" 1 || EXIT=1
        must::eq "crash_present" "$CRASH_COUNT" 1 || EXIT=1
        ;;
    cond-online-no)
        # "Expected nothing" — four-gate signals all zero.
        must::zero "no_lifecycle_fg" "$LIFE_FG" || EXIT=1
        must::zero "no_lifecycle_bg" "$LIFE_BG" || EXIT=1
        must::zero "no_network" "$NET" || EXIT=1
        must::zero "no_crash" "$CRASH_COUNT" || EXIT=1
        must::zero "no_recovery" "$RECOVERY_COUNT" || EXIT=1
        warn::eq "presence_zero" "$PRESENCE" 0
        ;;
    cond-online-yes)
        # Crash drains buffered events synchronously online.
        must::eq "crash_present" "$CRASH_COUNT" 1 || EXIT=1
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        must::zero "no_recovery" "$RECOVERY_COUNT" || EXIT=1
        ;;
    cond-offline-no)
        # No four-gate wire signals; disk buffer must contain events.
        must::zero "no_lifecycle_fg" "$LIFE_FG" || EXIT=1
        must::zero "no_network" "$NET" || EXIT=1
        must::ge "disk_buffered" "$DISK_BUFFER_COUNT" 4 || EXIT=1
        ;;
    cond-offline-yes)
        must::eq "recovery_present" "$RECOVERY_COUNT" 1 || EXIT=1
        must::eq "crash_present" "$CRASH_COUNT" 1 || EXIT=1
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        ;;
    hyb-online-no)
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        must::ge "lifecycle_bg" "$LIFE_BG" 2 || EXIT=1
        must::zero "no_crash" "$CRASH_COUNT" || EXIT=1
        must::zero "no_recovery" "$RECOVERY_COUNT" || EXIT=1
        ;;
    hyb-online-yes)
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        must::eq "crash_present" "$CRASH_COUNT" 1 || EXIT=1
        ;;
    hyb-offline-no)
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        must::eq "recovery_present" "$RECOVERY_COUNT" 1 || EXIT=1
        must::zero "no_crash" "$CRASH_COUNT" || EXIT=1
        ;;
    hyb-offline-yes)
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        must::eq "recovery_present" "$RECOVERY_COUNT" 1 || EXIT=1
        must::eq "crash_present" "$CRASH_COUNT" 1 || EXIT=1
        ;;
    *)
        echo "ERROR: unknown cell key $KEY" >&2; EXIT=2 ;;
esac

# --- Cleanup ---
[[ "$KEEP_APP" -eq 1 ]] || uat::cleanup "$MODE"

if [[ "$EXIT" -eq 0 ]]; then
    echo "[UAT] CELL ${ORIGINAL_CELL_ID} ${PLATFORM}/${MODE}/${CONNECTIVITY}/${CRASH} result=pass"
else
    echo "[UAT] CELL ${ORIGINAL_CELL_ID} ${PLATFORM}/${MODE}/${CONNECTIVITY}/${CRASH} result=fail" >&2
fi
exit "$EXIT"
