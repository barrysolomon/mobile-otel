#!/usr/bin/env bash
# UAT matrix outer loop. Drives N cells and aggregates results.

set -uo pipefail

usage() {
    cat <<EOF
Usage: $0 \\
  [--platform=android-native[,ios-native,...]] \\
  [--cells=1-12|1,3,5] \\
  [--fail-fast] \\
  [--summary-md=<path>]

Defaults: all platforms, all 12 cells, no fail-fast, no summary file.
EOF
}

PLATFORMS_ARG=""
CELLS_ARG="1-12"
FAIL_FAST=0
SUMMARY_MD=""

for arg in "$@"; do
    case "$arg" in
        --platform=*)   PLATFORMS_ARG="${arg#*=}" ;;
        --cells=*)      CELLS_ARG="${arg#*=}" ;;
        --fail-fast)    FAIL_FAST=1 ;;
        --summary-md=*) SUMMARY_MD="${arg#*=}" ;;
        -h|--help)      usage; exit 0 ;;
        *) echo "Unknown arg: $arg" >&2; usage; exit 2 ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Cell number → (mode, connectivity, crash) per spec §5 row order.
__cell_tuple() {
    case "$1" in
        1)  echo "cont online no" ;;
        2)  echo "cont online yes" ;;
        3)  echo "cont offline no" ;;
        4)  echo "cont offline yes" ;;
        5)  echo "cond online no" ;;
        6)  echo "cond online yes" ;;
        7)  echo "cond offline no" ;;
        8)  echo "cond offline yes" ;;
        9)  echo "hyb online no" ;;
        10) echo "hyb online yes" ;;
        11) echo "hyb offline no" ;;
        12) echo "hyb offline yes" ;;
        *)  echo "ERROR: bad cell number: $1" >&2; return 1 ;;
    esac
}

# Expand cell range.
__expand_cells() {
    local spec="$1"
    if [[ "$spec" =~ ^([0-9]+)-([0-9]+)$ ]]; then
        seq "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"
    else
        echo "$spec" | tr ',' '\n'
    fi
}

PLATFORMS_DEFAULT="android-native ios-native rn-android rn-ios"
PLATFORMS_LIST="${PLATFORMS_ARG:-$PLATFORMS_DEFAULT}"
PLATFORMS_LIST="${PLATFORMS_LIST//,/ }"
CELLS_LIST=$(__expand_cells "$CELLS_ARG")

RUN_ID="$(uuidgen | tr 'A-Z' 'a-z')"
EVIDENCE_DIR="${SCRIPT_DIR}/evidence/${RUN_ID}"
mkdir -p "$EVIDENCE_DIR"

RESULTS=()  # entries like "android-native:1:pass"

WORST_EXIT=0
for plat in $PLATFORMS_LIST; do
    for cell_no in $CELLS_LIST; do
        read -r mode conn crash <<<"$(__cell_tuple "$cell_no")"
        echo
        echo "===== ${plat} cell ${cell_no} (${mode}/${conn}/${crash}) ====="
        if "${SCRIPT_DIR}/run-uat-cell.sh" \
            --platform="$plat" \
            --mode="$mode" \
            --connectivity="$conn" \
            --crash="$crash" \
            --run-id="$RUN_ID" \
            --evidence-dir="$EVIDENCE_DIR"; then
            RESULTS+=("${plat}:${cell_no}:pass")
        else
            ec=$?
            case "$ec" in
                1) RESULTS+=("${plat}:${cell_no}:fail") ;;
                2) RESULTS+=("${plat}:${cell_no}:infra") ;;
                3) RESULTS+=("${plat}:${cell_no}:skip") ;;
                *) RESULTS+=("${plat}:${cell_no}:err${ec}") ;;
            esac
            [[ "$ec" -gt "$WORST_EXIT" ]] && WORST_EXIT="$ec"
            [[ "$FAIL_FAST" -eq 1 && "$ec" -eq 1 ]] && break 2
        fi
    done
done

# --- Summary ---
echo
echo "===== SUMMARY ====="
for r in "${RESULTS[@]}"; do echo "  $r"; done

if [[ -n "$SUMMARY_MD" ]]; then
    {
        echo "# UAT Matrix Run — ${RUN_ID}"
        echo
        echo "| Platform | Cell | Result |"
        echo "|---|---|---|"
        for r in "${RESULTS[@]}"; do
            IFS=':' read -r p c res <<<"$r"
            local_emoji="❓"
            case "$res" in
                pass)  local_emoji="🟢" ;;
                fail)  local_emoji="🔴" ;;
                infra) local_emoji="⚠️" ;;
                skip)  local_emoji="➖" ;;
            esac
            echo "| $p | $c | $local_emoji $res |"
        done
        echo
        echo "Evidence: \`${EVIDENCE_DIR}\`"
    } > "$SUMMARY_MD"
    echo "[UAT] Summary written to $SUMMARY_MD"
fi

exit "$WORST_EXIT"
