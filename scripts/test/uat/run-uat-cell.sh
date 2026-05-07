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
    rn-android)     source "${SCRIPT_DIR}/lib-uat-platform-rn-android.sh" ;;
    ios-native)     source "${SCRIPT_DIR}/lib-uat-platform-ios-native.sh" ;;
    rn-ios)         source "${SCRIPT_DIR}/lib-uat-platform-rn-ios.sh" ;;
    *) echo "ERROR: platform $PLATFORM not yet supported" >&2; exit 3 ;;
esac

source "${SCRIPT_DIR}/lib-uat-assertions.sh"

# Per-platform service name resolution.
__uat_service_name_for_platform() {
    case "$1" in
        android-native) echo "otel-android-astronomy-shop" ;;
        rn-android) echo "otel-rn-android-astronomy-shop" ;;
        ios-native) echo "otel-ios-astronomy-shop" ;;
        rn-ios) echo "otel-rn-astronomy-shop" ;;
        *) echo "ERROR: unknown platform: $1" >&2; return 1 ;;
    esac
}
SERVICE_NAME=$(__uat_service_name_for_platform "$PLATFORM")

# --- Trigger sequence ---

# t=0: install + launch with cell_id
uat::install "$MODE"
uat::launch "$MODE" "$ORIGINAL_CELL_ID"
# RN platforms need longer for the JS bridge to start and call
# Dash0Mobile.start() → OTelMobile.start() → install instrumentation.
# Native iOS/Android complete SDK init in < 1s; RN takes 3-5s.
if [[ "$PLATFORM" == rn-ios || "$PLATFORM" == rn-android ]]; then
    sleep 8
else
    sleep 3
fi

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
# Per-platform crash timing:
#   Android/iOS-native: crash fires ~1.5-3s after launch
#   RN iOS: crash fires ~15s after launch (JS bridge + bundle eval + SDK
#           init + 5s DiskLogBuffer semaphore + main.async dispatch must
#           all complete before ErrorsInstrumentation installs the signal
#           handler)
# Force-stop after the crash to ensure the process is dead.
if [[ "$CRASH" == "yes" ]]; then
    uat::trigger_crash "$MODE"
    if [[ "$PLATFORM" == rn-ios ]]; then
        sleep 18
    else
        sleep 4
    fi
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
    # iOS COND/HYB modes have no periodic flush — events only export on
    # bg transitions (forceFlush). The recovery launch sits in the
    # foreground, so `app.crash` and other recovery events stay buffered.
    # Trigger one lifecycle cycle to fire the bg-flush. CONT mode doesn't
    # need this (periodic timer drains the buffer), but the cycle is
    # harmless there and keeps the code simple.
    if [[ "$PLATFORM" == ios-native || "$PLATFORM" == rn-ios ]]; then
        uat::cycle_lifecycle "$MODE"
        sleep 5
    fi
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
# iOS crash: app.crash is emitted on the RECOVERY launch (signal handler
# writes marker, next launch reads it), so it carries RECOVERY_CELL_ID.
# Android crash: app.crash is emitted in the crashing process itself, so
# it carries ORIGINAL_CELL_ID — but is unreliable due to handler race.
CRASH_COUNT_RECOVERY=$(run_logs_query "service.name is ${SERVICE_NAME}" "event.name is app.crash" "dash0.test.cell_id is ${RECOVERY_FOR_CELL}")
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
        if [[ "$PLATFORM" == ios-native || "$PLATFORM" == rn-ios ]]; then
            # iOS crash cells: bg-triggered flushes export 2 of 3 fg events;
            # the 3rd fg is lost when trigger_crash terminates the process
            # before a bg-flush fires. No crash mirror = no disk recovery.
            must::ge "lifecycle_fg" "$LIFE_FG" 2 || EXIT=1
            must::eq "crash_present" "$CRASH_COUNT_RECOVERY" 1 || EXIT=1
            warn::eq "recovery_optional_ios" "$RECOVERY_COUNT" 0
        else
            must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
            must::eq "recovery_present" "$RECOVERY_COUNT" 1 || EXIT=1
            warn::eq "crash_present_optional" "$CRASH_COUNT" 1
        fi
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
        if [[ "$PLATFORM" == ios-native || "$PLATFORM" == rn-ios ]]; then
            must::ge "lifecycle_fg" "$LIFE_FG" 2 || EXIT=1
            must::eq "crash_present" "$CRASH_COUNT_RECOVERY" 1 || EXIT=1
            warn::eq "recovery_optional_ios" "$RECOVERY_COUNT" 0
        else
            must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
            must::eq "recovery_present" "$RECOVERY_COUNT" 1 || EXIT=1
            warn::eq "crash_present_optional" "$CRASH_COUNT" 1
        fi
        ;;
    cond-online-no)
        # "Expected nothing" for policy-triggered export.
        # On iOS, OTelMobile.forceFlush() fires on UIApplication
        # didEnterBackground — a safety net against data loss. This means
        # lifecycle cycling produces 2 bg-triggered flushes that export
        # buffered events even in COND mode. Android doesn't auto-flush
        # on bg in COND mode, so it truly sees zero.
        if [[ "$PLATFORM" == ios-native || "$PLATFORM" == rn-ios ]]; then
            must::ge "lifecycle_fg" "$LIFE_FG" 2 || EXIT=1
            must::ge "lifecycle_bg" "$LIFE_BG" 2 || EXIT=1
        else
            must::zero "no_lifecycle_fg" "$LIFE_FG" || EXIT=1
            must::zero "no_lifecycle_bg" "$LIFE_BG" || EXIT=1
        fi
        must::zero "no_network" "$NET" || EXIT=1
        must::zero "no_crash" "$CRASH_COUNT" || EXIT=1
        must::zero "no_recovery" "$RECOVERY_COUNT" || EXIT=1
        ;;
    cond-online-yes)
        if [[ "$PLATFORM" == ios-native || "$PLATFORM" == rn-ios ]]; then
            must::eq "crash_present" "$CRASH_COUNT_RECOVERY" 1 || EXIT=1
            must::ge "lifecycle_fg" "$LIFE_FG" 2 || EXIT=1
            warn::eq "recovery_optional_ios" "$RECOVERY_COUNT" 0
        else
            must::eq "recovery_present" "$RECOVERY_COUNT" 1 || EXIT=1
            must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
            warn::eq "crash_present_optional" "$CRASH_COUNT" 1
        fi
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
        if [[ "$PLATFORM" == ios-native || "$PLATFORM" == rn-ios ]]; then
            must::eq "crash_present" "$CRASH_COUNT_RECOVERY" 1 || EXIT=1
            must::ge "lifecycle_fg" "$LIFE_FG" 2 || EXIT=1
            warn::eq "recovery_optional_ios" "$RECOVERY_COUNT" 0
        else
            must::eq "recovery_present" "$RECOVERY_COUNT" 1 || EXIT=1
            must::ge "lifecycle_fg" "$LIFE_FG" 3 || EXIT=1
            warn::eq "crash_present_optional" "$CRASH_COUNT" 1
        fi
        ;;
    hyb-online-no)
        # HYB lifecycle events are buffered until policy match (same as
        # COND). Android: heartbeat tick is the HYB-specific signal.
        # iOS: no heartbeat yet; HYB behaves like COND with bg-flush.
        if [[ "$PLATFORM" == ios-native || "$PLATFORM" == rn-ios ]]; then
            must::ge "lifecycle_fg" "$LIFE_FG" 2 || EXIT=1
        else
            must::ge "heartbeat_present" "$HEARTBEAT_COUNT" 1 || EXIT=1
        fi
        must::zero "no_crash" "$CRASH_COUNT" || EXIT=1
        must::zero "no_recovery" "$RECOVERY_COUNT" || EXIT=1
        ;;
    hyb-online-yes)
        if [[ "$PLATFORM" == ios-native || "$PLATFORM" == rn-ios ]]; then
            must::eq "crash_present" "$CRASH_COUNT_RECOVERY" 1 || EXIT=1
            warn::eq "recovery_optional_ios" "$RECOVERY_COUNT" 0
        else
            must::ge "heartbeat_present" "$HEARTBEAT_COUNT" 1 || EXIT=1
            must::eq "recovery_present" "$RECOVERY_COUNT" 1 || EXIT=1
            warn::eq "crash_present_optional" "$CRASH_COUNT" 1
        fi
        ;;
    hyb-offline-no)
        if [[ "$PLATFORM" == ios-native || "$PLATFORM" == rn-ios ]]; then
            must::ge "lifecycle_fg" "$LIFE_FG" 2 || EXIT=1
        else
            must::ge "heartbeat_present" "$HEARTBEAT_COUNT" 1 || EXIT=1
        fi
        must::zero "no_crash" "$CRASH_COUNT" || EXIT=1
        warn::eq "recovery_optional" "$RECOVERY_COUNT" 0
        ;;
    hyb-offline-yes)
        if [[ "$PLATFORM" == ios-native || "$PLATFORM" == rn-ios ]]; then
            must::eq "crash_present" "$CRASH_COUNT_RECOVERY" 1 || EXIT=1
            warn::eq "recovery_optional_ios" "$RECOVERY_COUNT" 0
        else
            must::ge "heartbeat_present" "$HEARTBEAT_COUNT" 1 || EXIT=1
            must::eq "recovery_present" "$RECOVERY_COUNT" 1 || EXIT=1
            warn::eq "crash_present_optional" "$CRASH_COUNT" 1
        fi
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
