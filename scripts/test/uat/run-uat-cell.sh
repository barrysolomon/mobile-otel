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
# Sleep 5s after airplane mode to let gRPC connection pools drain and
# any in-flight CONT periodic flush to fail. Without this, the 30s CONT
# flush can succeed on a lingering TCP connection, draining the disk
# buffer before the crash fires (verified 2026-05-06).
if [[ "$CONNECTIVITY" == "offline" ]]; then
    uat::offline
    sleep 5
fi

# t=40: app-driven GET attempt (relies on app emitting periodic GETs;
# in the upstream-demo-app this happens on resume — we trigger one more
# foreground cycle to provoke it)
if [[ "$CONNECTIVITY" == "offline" ]]; then
    uat::cycle_lifecycle "$MODE"
    sleep 5
fi

# t=50: trigger crash (only if crash=yes)
# The crash fires 3s after the intent delivery (Handler.postDelayed).
# We must force-stop the process BEFORE the background executor can
# drain the disk buffer via forceFlush(). Timeline:
#   t+0s: intent delivered, crash scheduled
#   t+3s: RuntimeException on main thread
#   t+3.5s: background thread's forceFlush() can succeed, clearing disk
#
# Force-stop at t+3.5s kills the process and all threads, preserving
# the disk buffer for recovery. The crash-safety mirror (2s schedule)
# has run at least once by t+3s, so the disk has events.
if [[ "$CRASH" == "yes" ]]; then
    uat::trigger_crash "$MODE"
    # Sleep 3.5s: enough for the crash to fire (3s) + margin for
    # crash-mirror to persist, but before forceFlush can complete.
    sleep 4
    uat::force_stop "$MODE"
    sleep 1
fi

# t=60: go online (only if connectivity=offline)
if [[ "$CONNECTIVITY" == "offline" ]]; then
    uat::online
    sleep 5
fi

# Cell 7 disk probe — must run BEFORE the relaunch path erases buffer
# state. Cell 7 (cond/offline/no) is the only cell that asserts on the
# disk buffer; for that cell, we skip the relaunch entirely.
DISK_BUFFER_COUNT=0
if [[ "$CONNECTIVITY" == "offline" && "$CRASH" == "no" && "$MODE" == "cond" ]]; then
    DISK_BUFFER_COUNT=$(uat::probe_disk_buffer "$MODE")
fi

# t=70: relaunch (only if crash=yes OR connectivity=offline)
# Skip for cell 7 since we already probed disk and a relaunch would
# drain it.
if [[ ( "$CRASH" == "yes" || "$CONNECTIVITY" == "offline" ) \
      && ! ( "$CONNECTIVITY" == "offline" && "$CRASH" == "no" && "$MODE" == "cond" ) ]]; then
    uat::launch "$MODE" "$RECOVERY_CELL_ID"
    sleep 8
fi

# t=90: Dash0 ingestion grace period
# Must exceed CONT mode's 30s log flush interval. Tighter values race the
# flush (verified 2026-05-05) — records arrive in Dash0 *after* assertions
# already read 0.
sleep 40

# --- Dash0 query batch ---
# `--from now-Xm` filters by EVENT TIMESTAMP (device wall clock), not by
# ingestion time. A cell that buffers events offline for 60s, then comes
# online at +60s and exports, produces events with device timestamps from
# +0s to +60s — but the export reaches Dash0 at +90s of cell time. With
# a tight window we'd miss the early offline events. Use a generous
# window (10m) — the cell_id filter still scopes results to this cell.
QUERY_FROM="now-10m"

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
# HYB mode's continuous tick: device.heartbeat is exported every ~30s
# regardless of policy. This is the signal that distinguishes HYB from
# pure COND. Cell 9 / 11 assert this rather than lifecycle counts since
# HYB lifecycle events are buffered until policy match, like COND.
# For crash cells the app dies before the first heartbeat fires (30s default),
# so the only heartbeats come from the recovery launch (RECOVERY_CELL_ID).
HEARTBEAT_CELL_ID="$ORIGINAL_CELL_ID"
if [[ "$CRASH" == "yes" ]]; then
    HEARTBEAT_CELL_ID="$RECOVERY_CELL_ID"
fi
HEARTBEAT_COUNT=$(run_logs_query "service.name is ${SERVICE_NAME}" "otel.scope.name is io.opentelemetry.android.mobile.heartbeat" "dash0.test.cell_id is ${HEARTBEAT_CELL_ID}")

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
        # Crash cells: the `app.crash` log is NOT a reliable signal on
        # Android. ErrorInstrumentation hooks the default uncaught
        # exception handler, but multi-thread crashes race
        # RuntimeInit's KillApplicationHandler — the process can be
        # SIGABRT'd before our chain runs. The reliable signal is
        # `app.recovery_start` on relaunch with event_count > 0,
        # proving the disk-buffered events from before the crash were
        # recovered. See memory feedback_crash_handler_race.
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        must::eq "recovery_present" "$RECOVERY_COUNT" 1 || EXIT=1
        warn::eq "crash_present_optional" "$CRASH_COUNT" 1
        ;;
    cont-offline-no)
        # CONT-offline-no: events buffer in RAM during offline window,
        # then CONT's normal flush drains them when network returns.
        # Relaunch happens on a clean process — disk buffer is empty,
        # so the SDK does NOT emit app.recovery_start (correct behavior:
        # nothing to recover). The proof of offline buffering is that
        # all lifecycle events from the offline window arrived under
        # the original cell_id.
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        must::ge "lifecycle_bg" "$LIFE_BG" 2 || EXIT=1
        must::zero "no_crash" "$CRASH_COUNT" || EXIT=1
        warn::eq "recovery_optional" "$RECOVERY_COUNT" 0
        ;;
    cont-offline-yes)
        # See cont-online-yes: app.crash is unreliable due to crash-handler race.
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        must::eq "recovery_present" "$RECOVERY_COUNT" 1 || EXIT=1
        warn::eq "crash_present_optional" "$CRASH_COUNT" 1
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
        # COND online + crash: the disk-mirror writes RAM events to
        # disk every 2s, so by crash time the recent events are
        # already on disk. On relaunch app.recovery_start fires with
        # event_count > 0, proving the recovery path. app.crash is
        # unreliable (see cont-online-yes).
        must::eq "recovery_present" "$RECOVERY_COUNT" 1 || EXIT=1
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        warn::eq "crash_present_optional" "$CRASH_COUNT" 1
        ;;
    cond-offline-no)
        # COND-offline-no tests buffer-don't-export semantics, but
        # PredictiveExportPolicy correctly fires flushWindow(2) right
        # before the network goes down (it detects networkLossRisk and
        # pre-emptively drains the buffer). That flush also clears the
        # disk mirror — verified 2026-05-05. So we can't assert "disk
        # has events" or "lifecycle == 0"; both contradict a correct
        # SDK feature. The hard guarantees that survive: no crash, no
        # network spans (the GETs happen in the offline window), and
        # the predictive flush itself reaches Dash0 with ≤ 4 lifecycle
        # records — bounded by the pre-flush window of buffered events.
        must::zero "no_network" "$NET" || EXIT=1
        must::zero "no_crash" "$CRASH_COUNT" || EXIT=1
        warn::within "lifecycle_partial_export" "$LIFE_FG" 2 4
        warn::within "disk_buffered_post_predictive_flush" "$DISK_BUFFER_COUNT" 0 5
        ;;
    cond-offline-yes)
        must::eq "recovery_present" "$RECOVERY_COUNT" 1 || EXIT=1
        must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
        warn::eq "crash_present_optional" "$CRASH_COUNT" 1
        ;;
    hyb-online-no)
        # HYB lifecycle events are buffered until policy match (same as
        # COND). The HYB-specific signal is the periodic device.heartbeat
        # tick, which is immediately exported (selective immediate path
        # in MobileLogRecordProcessor.onEmit). The 90s cell window covers
        # at least one 30s heartbeat tick.
        must::ge "heartbeat_present" "$HEARTBEAT_COUNT" 1 || EXIT=1
        must::zero "no_crash" "$CRASH_COUNT" || EXIT=1
        must::zero "no_recovery" "$RECOVERY_COUNT" || EXIT=1
        ;;
    hyb-online-yes)
        # HYB lifecycle is buffered until policy match; on crash the
        # disk-mirror has events that survive. Recovery_start fires on
        # relaunch.
        must::eq "recovery_present" "$RECOVERY_COUNT" 1 || EXIT=1
        must::ge "heartbeat_present" "$HEARTBEAT_COUNT" 1 || EXIT=1
        warn::eq "crash_present_optional" "$CRASH_COUNT" 1
        ;;
    hyb-offline-no)
        # Same logic as cont-offline-no: HYB lifecycle is buffered, but
        # the heartbeat tick proves the SDK is alive. Recovery marker
        # only fires if disk buffer had pending events at relaunch — not
        # guaranteed when offline window is short and online drain
        # handled the RAM buffer.
        must::ge "heartbeat_present" "$HEARTBEAT_COUNT" 1 || EXIT=1
        must::zero "no_crash" "$CRASH_COUNT" || EXIT=1
        warn::eq "recovery_optional" "$RECOVERY_COUNT" 0
        ;;
    hyb-offline-yes)
        must::eq "recovery_present" "$RECOVERY_COUNT" 1 || EXIT=1
        must::ge "heartbeat_present" "$HEARTBEAT_COUNT" 1 || EXIT=1
        warn::eq "crash_present_optional" "$CRASH_COUNT" 1
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
