#!/usr/bin/env bash

################################################################################
# OpenTelemetry Mobile Demo - Enhanced Monkey Test Script
#
# Features:
# - Transaction flows (login → api → navigation sequences)
# - Demographic cycling (simulates different user profiles)
# - Activity stretches (short/med/long normal activity bursts)
# - Periodic manual flushes
# - Background/foreground transitions
# - Crash and ANR detection with recovery
#
# Usage:
#   bash monkey-test.sh [OPTIONS]
#
# Options:
#   -a, --actions N         Run N random actions (default: unlimited)
#   -t, --time N            Run for N seconds (default: unlimited)
#   -s, --stretch-mode MODE Activity stretch mode: mixed|short|medium|long|none (default: long)
#   -f, --flush-interval N  Flush every N actions (default: 25, 0=disable)
#   -x, --transactions N    Transaction flow every N actions (default: 15, 0=disable)
#   -d, --demographics N    Change demographics every N actions (default: 50, 0=disable)
#   -p, --package PKG       Package name (default: io.opentelemetry.android.demo)
#       --device SERIAL     Target device/emulator (default: auto-select if one connected)
#   -v, --verbose           Verbose output
#   -h, --help              Show this help
#
# Stretch Modes:
#   mixed  - Random mix of short/medium/long stretches
#   short  - 5-10 normal actions between triggers
#   medium - 15-25 normal actions between triggers
#   long   - 30-50 normal actions between triggers (default for 3-hour test)
#   none   - Always use weighted random (old behavior)
#
# Transaction Flows:
#   login → api_call → navigate (simulates user session)
#
# Demographics:
#   Cycles through different user profiles with varying:
#   - Device types, regions, age groups, subscription tiers
#
# Examples:
#   bash monkey-test.sh --time 10800  # 3 hours with defaults
#   bash monkey-test.sh -t 3600 -s long -x 10 -d 30
#   bash monkey-test.sh -a 200 -s medium -x 15 -f 20
#
# Note: Compatible with Bash 3.2+ (macOS default)
#
################################################################################

set -e

# Default configuration
PACKAGE_NAME="io.opentelemetry.android.demo"
ACTIVITY_NAME=".SchedulingActivity"
DEVICE=""
MAX_ACTIONS=-1
MAX_DURATION=-1
VERBOSE=0
ACTION_COUNT=0
START_TIME=$(date +%s)
STRETCH_MODE="long"
FLUSH_INTERVAL=25
TRANSACTION_INTERVAL=15
DEMOGRAPHICS_INTERVAL=50
STRETCH_COUNTER=0
CURRENT_STRETCH_SIZE=0
FLUSH_COUNTER=0
TRANSACTION_COUNTER=0
DEMOGRAPHICS_COUNTER=0
CURRENT_DEMOGRAPHIC_INDEX=0

# Button data using parallel arrays
BUTTON_NAMES=(
    "login" "navigate" "api_call" "background" "interaction" "form_submit"
    "ui_freeze" "crash" "network_error" "low_memory" "anr" "force_flush"
)

BUTTON_X=(270 810 270 810 270 810  270 810 270 810 540  540)
BUTTON_Y=(1200 1200 1320 1320 1440 1440  900 900 1020 1020 1140  1600)
BUTTON_WEIGHTS=(15 15 15 15 15 15  5 2 5 1 1  10)

# Regular activity buttons
REGULAR_BUTTON_NAMES=("login" "navigate" "api_call" "background" "interaction" "form_submit")
REGULAR_BUTTON_INDICES=(0 1 2 3 4 5)

# Demographics profiles (will cycle through these)
DEMOGRAPHIC_PROFILES=(
    "device_type:smartphone,region:us,age_group:25-34,tier:premium"
    "device_type:tablet,region:eu,age_group:35-44,tier:free"
    "device_type:smartphone,region:asia,age_group:18-24,tier:basic"
    "device_type:phablet,region:us,age_group:45-54,tier:premium"
    "device_type:smartphone,region:latam,age_group:25-34,tier:basic"
    "device_type:tablet,region:eu,age_group:55-64,tier:premium"
    "device_type:smartphone,region:asia,age_group:18-24,tier:free"
    "device_type:smartphone,region:us,age_group:35-44,tier:basic"
)

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m'

################################################################################
# Helper Functions
################################################################################

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_debug() {
    if [ $VERBOSE -eq 1 ]; then
        echo -e "${BLUE}[DEBUG]${NC} $1"
    fi
}

log_stretch() {
    echo -e "${CYAN}[STRETCH]${NC} $1"
}

log_transaction() {
    echo -e "${MAGENTA}[TRANSACTION]${NC} $1"
}

log_demographic() {
    echo -e "${YELLOW}[DEMOGRAPHIC]${NC} $1"
}

show_help() {
    cat << EOF
OpenTelemetry Mobile Demo - Enhanced Monkey Test Script

Usage: bash $0 [OPTIONS]

Options:
  -a, --actions N         Run N random actions (default: unlimited)
  -t, --time N            Run for N seconds (default: unlimited)
  -s, --stretch-mode MODE Activity stretch mode (default: long)
  -f, --flush-interval N  Manual flush every N actions (default: 25, 0=disable)
  -x, --transactions N    Transaction flow every N actions (default: 15, 0=disable)
  -d, --demographics N    Change demographics every N actions (default: 50, 0=disable)
  -p, --package PKG       Package name (default: $PACKAGE_NAME)
      --device SERIAL     Target device/emulator (default: auto-select)
  -v, --verbose           Verbose output
  -h, --help              Show this help

Stretch Modes:
  mixed  - Random mix of short/medium/long stretches
  short  - 5-10 normal actions between triggers
  medium - 15-25 normal actions between triggers
  long   - 30-50 normal actions between triggers (default)
  none   - Always use weighted random (old behavior)

For 3-hour test:
  bash $0 --time 10800

Examples:
  bash $0 -t 3600 -s long -x 10 -d 30 -v
  bash $0 -a 200 -s medium -x 15 -f 20

EOF
}

check_device() {
    log_info "Checking device $DEVICE..."
    if ! $ADB get-state >/dev/null 2>&1; then
        log_error "Device $DEVICE not responding."
        exit 1
    fi
    log_info "Device $DEVICE ready"
}

is_app_running() {
    $ADB shell "pidof $PACKAGE_NAME" > /dev/null 2>&1
    return $?
}

is_app_responding() {
    if $ADB shell dumpsys activity 2>/dev/null | grep -q "ANR in $PACKAGE_NAME"; then
        return 1
    fi
    if ! is_app_running; then
        return 1
    fi
    return 0
}

start_app() {
    log_info "Starting app: $PACKAGE_NAME"
    $ADB shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME" > /dev/null 2>&1
    sleep 2
}

stop_app() {
    log_info "Stopping app: $PACKAGE_NAME"
    $ADB shell am force-stop "$PACKAGE_NAME"
    sleep 1
}

kill_app() {
    log_warn "Force killing app"
    $ADB shell am kill "$PACKAGE_NAME" 2>/dev/null || true
    sleep 1
}

send_to_background() {
    log_debug "Sending app to background"
    $ADB shell input keyevent KEYCODE_HOME
}

bring_to_foreground() {
    log_debug "Bringing app to foreground"
    $ADB shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME" > /dev/null 2>&1
}

get_button_index() {
    local name=$1
    for i in "${!BUTTON_NAMES[@]}"; do
        if [ "${BUTTON_NAMES[$i]}" = "$name" ]; then
            echo $i
            return 0
        fi
    done
    echo "-1"
}

click_button() {
    local button_name=$1
    local idx=$(get_button_index "$button_name")

    if [ "$idx" = "-1" ]; then
        log_error "Unknown button: $button_name"
        return 1
    fi

    local x=${BUTTON_X[$idx]}
    local y=${BUTTON_Y[$idx]}

    log_debug "Clicking button: $button_name at ($x, $y)"
    $ADB shell input tap $x $y
}

select_random_button() {
    local weighted_buttons=()
    for i in "${!BUTTON_NAMES[@]}"; do
        local weight=${BUTTON_WEIGHTS[$i]}
        for ((j=0; j<weight; j++)); do
            weighted_buttons+=($i)
        done
    done

    local total=${#weighted_buttons[@]}
    local random_idx=$((RANDOM % total))
    local button_idx=${weighted_buttons[$random_idx]}

    echo "${BUTTON_NAMES[$button_idx]}"
}

select_regular_button() {
    local random_idx=$((RANDOM % 6))
    local button_idx=${REGULAR_BUTTON_INDICES[$random_idx]}
    echo "${BUTTON_NAMES[$button_idx]}"
}

get_stretch_size() {
    case $STRETCH_MODE in
        short)
            echo $((RANDOM % 6 + 5))
            ;;
        medium)
            echo $((RANDOM % 11 + 15))
            ;;
        long)
            echo $((RANDOM % 21 + 30))
            ;;
        mixed)
            local mode=$((RANDOM % 3))
            case $mode in
                0) echo $((RANDOM % 6 + 5)) ;;
                1) echo $((RANDOM % 11 + 15)) ;;
                2) echo $((RANDOM % 21 + 30)) ;;
            esac
            ;;
        none)
            echo 0
            ;;
        *)
            echo 0
            ;;
    esac
}

random_delay() {
    local delay_ms=$((RANDOM % 2500 + 500))
    local delay_sec=$(echo "scale=3; $delay_ms / 1000" | bc 2>/dev/null || echo "1")
    sleep $delay_sec
}

random_background_delay() {
    local delay=$((RANDOM % 9 + 1))
    sleep $delay
}

set_demographics() {
    local profile="${DEMOGRAPHIC_PROFILES[$CURRENT_DEMOGRAPHIC_INDEX]}"
    log_demographic "Setting profile #$((CURRENT_DEMOGRAPHIC_INDEX + 1)): $profile"

    # Parse profile and build intent extras
    local intent_extras=""
    IFS=',' read -ra ATTRS <<< "$profile"
    for attr in "${ATTRS[@]}"; do
        IFS=':' read -ra KV <<< "$attr"
        local key="${KV[0]}"
        local value="${KV[1]}"
        log_debug "  $key = $value"
        intent_extras="$intent_extras --es $key \"$value\""
    done

    # Send demographics to app via intent extras (using FLAG_ACTIVITY_SINGLE_TOP to avoid restart)
    log_debug "Sending demographics via intent: $intent_extras"
    $ADB shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME" --activity-single-top $intent_extras >/dev/null 2>&1

    # Cycle to next profile
    CURRENT_DEMOGRAPHIC_INDEX=$(( (CURRENT_DEMOGRAPHIC_INDEX + 1) % ${#DEMOGRAPHIC_PROFILES[@]} ))
}

execute_transaction_flow() {
    log_transaction "Executing transaction flow: login → api_call → navigate"

    # Step 1: Login
    log_transaction "  Step 1/3: Login"
    click_button "login"
    sleep 1

    # Step 2: API Call
    log_transaction "  Step 2/3: API Call"
    click_button "api_call"
    sleep 1

    # Step 3: Navigate
    log_transaction "  Step 3/3: Navigate"
    click_button "navigate"
    sleep 1

    log_transaction "Transaction flow complete"
}

handle_crash() {
    log_warn "App crashed! Waiting before restart..."
    local restart_delay=$((RANDOM % 55 + 5))
    log_info "Waiting ${restart_delay}s before restart"
    sleep $restart_delay
    stop_app
    start_app
    log_info "App restarted after crash"
}

handle_anr() {
    log_warn "App in ANR state! Killing and restarting..."
    kill_app
    local restart_delay=$((RANDOM % 30 + 10))
    log_info "Waiting ${restart_delay}s before restart"
    sleep $restart_delay
    stop_app
    start_app
    log_info "App restarted after ANR"
}

trigger_manual_flush() {
    log_info "🚀 Triggering manual flush (interval: $FLUSH_INTERVAL)"
    click_button "force_flush"
    sleep 2
}

perform_random_action() {
    ACTION_COUNT=$((ACTION_COUNT + 1))
    FLUSH_COUNTER=$((FLUSH_COUNTER + 1))
    TRANSACTION_COUNTER=$((TRANSACTION_COUNTER + 1))
    DEMOGRAPHICS_COUNTER=$((DEMOGRAPHICS_COUNTER + 1))

    # Check if app is responding
    if ! is_app_responding; then
        if $ADB shell dumpsys activity 2>/dev/null | grep -q "ANR in $PACKAGE_NAME"; then
            handle_anr
            return
        elif ! is_app_running; then
            handle_crash
            return
        fi
    fi

    # Check for demographics change
    if [ $DEMOGRAPHICS_INTERVAL -gt 0 ] && [ $DEMOGRAPHICS_COUNTER -ge $DEMOGRAPHICS_INTERVAL ]; then
        set_demographics
        DEMOGRAPHICS_COUNTER=0
    fi

    # Check for transaction flow
    if [ $TRANSACTION_INTERVAL -gt 0 ] && [ $TRANSACTION_COUNTER -ge $TRANSACTION_INTERVAL ]; then
        execute_transaction_flow
        TRANSACTION_COUNTER=0
        return
    fi

    # Check for manual flush
    if [ $FLUSH_INTERVAL -gt 0 ] && [ $FLUSH_COUNTER -ge $FLUSH_INTERVAL ]; then
        trigger_manual_flush
        FLUSH_COUNTER=0
        return
    fi

    # 10% chance for background/foreground transition
    if [ "$STRETCH_MODE" = "none" ] || [ $STRETCH_COUNTER -ge $CURRENT_STRETCH_SIZE ]; then
        if [ $((RANDOM % 10)) -eq 0 ]; then
            log_info "Action #$ACTION_COUNT: Background/Foreground transition"
            send_to_background
            random_background_delay
            bring_to_foreground
            sleep 1
            return
        fi
    fi

    # Determine button based on stretch mode
    local button
    if [ "$STRETCH_MODE" = "none" ]; then
        button=$(select_random_button)
        log_info "Action #$ACTION_COUNT: Clicking $button"
    else
        if [ $STRETCH_COUNTER -lt $CURRENT_STRETCH_SIZE ]; then
            button=$(select_regular_button)
            STRETCH_COUNTER=$((STRETCH_COUNTER + 1))
            log_info "Action #$ACTION_COUNT: Clicking $button (stretch: $STRETCH_COUNTER/$CURRENT_STRETCH_SIZE)"
        else
            button=$(select_random_button)
            local is_trigger=0
            case $button in
                ui_freeze|crash|network_error|low_memory|anr)
                    is_trigger=1
                    ;;
            esac

            if [ $is_trigger -eq 1 ]; then
                log_stretch "End of stretch - triggering: $button"
            else
                log_info "Action #$ACTION_COUNT: Clicking $button (between stretches)"
            fi

            CURRENT_STRETCH_SIZE=$(get_stretch_size)
            STRETCH_COUNTER=0
            if [ $CURRENT_STRETCH_SIZE -gt 0 ]; then
                log_stretch "Starting new stretch: $CURRENT_STRETCH_SIZE normal actions"
            fi
        fi
    fi

    click_button "$button"

    # Special handling
    if [ "$button" = "crash" ]; then
        log_warn "Crash button clicked - expecting app to crash"
        sleep 2
        if ! is_app_running; then
            handle_crash
        fi
        return
    fi

    if [ "$button" = "anr" ]; then
        log_warn "ANR button clicked - app will freeze for 30s"
        sleep 35
        if ! is_app_responding; then
            handle_anr
        fi
        return
    fi

    random_delay
}

should_continue() {
    local current_time=$(date +%s)
    local elapsed=$((current_time - START_TIME))

    if [ $MAX_ACTIONS -gt 0 ] && [ $ACTION_COUNT -ge $MAX_ACTIONS ]; then
        return 1
    fi

    if [ $MAX_DURATION -gt 0 ] && [ $elapsed -ge $MAX_DURATION ]; then
        return 1
    fi

    return 0
}

print_statistics() {
    local duration=$(($(date +%s) - START_TIME))
    local hours=$((duration / 3600))
    local mins=$(( (duration % 3600) / 60 ))
    local secs=$((duration % 60))

    log_info "==== Test Statistics ===="
    log_info "Total actions: $ACTION_COUNT"
    log_info "Duration: ${hours}h ${mins}m ${secs}s"
    log_info "Actions/min: $(echo "scale=1; $ACTION_COUNT * 60 / $duration" | bc 2>/dev/null || echo "N/A")"
    log_info "========================="
}

################################################################################
# Main Script
################################################################################

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -a|--actions)
            MAX_ACTIONS="$2"
            shift 2
            ;;
        -t|--time)
            MAX_DURATION="$2"
            shift 2
            ;;
        -s|--stretch-mode)
            STRETCH_MODE="$2"
            shift 2
            ;;
        -f|--flush-interval)
            FLUSH_INTERVAL="$2"
            shift 2
            ;;
        -x|--transactions)
            TRANSACTION_INTERVAL="$2"
            shift 2
            ;;
        -d|--demographics)
            DEMOGRAPHICS_INTERVAL="$2"
            shift 2
            ;;
        -p|--package)
            PACKAGE_NAME="$2"
            shift 2
            ;;
        --device)
            DEVICE="$2"
            shift 2
            ;;
        -v|--verbose)
            VERBOSE=1
            shift
            ;;
        -h|--help)
            show_help
            exit 0
            ;;
        *)
            log_error "Unknown option: $1"
            show_help
            exit 1
            ;;
    esac
done

# Validate stretch mode
case $STRETCH_MODE in
    mixed|short|medium|long|none)
        ;;
    *)
        log_error "Invalid stretch mode: $STRETCH_MODE"
        exit 1
        ;;
esac

# Auto-select device if not specified
if [ -z "$DEVICE" ]; then
    DEVICES=()
    while IFS= read -r line; do
        serial=$(echo "$line" | awk '{print $1}')
        [[ -n "$serial" ]] && DEVICES+=("$serial")
    done < <(adb devices 2>/dev/null | tail -n +2 | grep -v "^$" | grep -v "offline")

    if [ ${#DEVICES[@]} -eq 0 ]; then
        log_error "No devices found. Connect a device or start an emulator."
        exit 1
    elif [ ${#DEVICES[@]} -eq 1 ]; then
        DEVICE="${DEVICES[0]}"
        log_info "Auto-selected device: $DEVICE"
    else
        log_error "Multiple devices found: ${DEVICES[*]}"
        log_error "Use --device <serial> to select one."
        exit 1
    fi
fi

ADB="adb -s $DEVICE"

# Print configuration
log_info "==== Enhanced Monkey Test Configuration ===="
log_info "Package: $PACKAGE_NAME"
log_info "Device: $DEVICE"
if [ $MAX_ACTIONS -gt 0 ]; then
    log_info "Max actions: $MAX_ACTIONS"
else
    log_info "Max actions: unlimited"
fi
if [ $MAX_DURATION -gt 0 ]; then
    hours=$((MAX_DURATION / 3600))
    mins=$(( (MAX_DURATION % 3600) / 60 ))
    log_info "Max duration: ${hours}h ${mins}m (${MAX_DURATION}s)"
else
    log_info "Max duration: unlimited"
fi
log_info "Stretch mode: $STRETCH_MODE"
[ $FLUSH_INTERVAL -gt 0 ] && log_info "Flush interval: every $FLUSH_INTERVAL actions" || log_info "Flush interval: disabled"
[ $TRANSACTION_INTERVAL -gt 0 ] && log_info "Transaction flow: every $TRANSACTION_INTERVAL actions" || log_info "Transaction flows: disabled"
[ $DEMOGRAPHICS_INTERVAL -gt 0 ] && log_info "Demographics change: every $DEMOGRAPHICS_INTERVAL actions" || log_info "Demographics: disabled"
log_info "Verbose: $VERBOSE"
log_info "============================================="
echo

check_device

log_info "Preparing app for enhanced monkey test..."
stop_app
start_app

# Initialize demographics
if [ $DEMOGRAPHICS_INTERVAL -gt 0 ]; then
    set_demographics
fi

# Initialize stretch
if [ "$STRETCH_MODE" != "none" ]; then
    CURRENT_STRETCH_SIZE=$(get_stretch_size)
    log_stretch "Starting with stretch: $CURRENT_STRETCH_SIZE normal actions"
fi

log_info "Starting enhanced monkey test..."
echo

# Main test loop
while should_continue; do
    perform_random_action
done

# Cleanup
log_info "Enhanced monkey test complete!"
print_statistics

log_info "Triggering final flush..."
click_button "force_flush"
sleep 3

log_info "Test finished successfully"
