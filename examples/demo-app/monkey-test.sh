#!/usr/bin/env bash

################################################################################
# OpenTelemetry Mobile Demo - Monkey Test Script
#
# This script performs randomized testing of the demo app with:
# - Random button clicks (regular activities and trigger events)
# - Configurable activity stretches (short/med/long normal activity bursts)
# - Periodic manual flushes to validate telemetry capture
# - Background/foreground transitions
# - Crash and ANR detection
# - Automatic app restart after crashes
#
# Usage:
#   bash monkey-test.sh [OPTIONS]
#
# Options:
#   -a, --actions N         Run N random actions (default: unlimited)
#   -t, --time N            Run for N seconds (default: unlimited)
#   -s, --stretch-mode MODE Activity stretch mode: mixed|short|medium|long|none (default: mixed)
#   -f, --flush-interval N  Flush every N actions (default: 20, 0=disable)
#   -p, --package PKG       Package name (default: io.opentelemetry.android.demo)
#   -v, --verbose           Verbose output
#   -h, --help              Show this help
#
# Stretch Modes:
#   mixed  - Random mix of short/medium/long stretches (default)
#   short  - 5-10 normal actions between triggers
#   medium - 15-25 normal actions between triggers
#   long   - 30-50 normal actions between triggers
#   none   - Always use weighted random (old behavior)
#
# Examples:
#   bash monkey-test.sh --actions 100 --stretch-mode medium
#   bash monkey-test.sh --time 300 --flush-interval 15
#   bash monkey-test.sh -a 50 -s long -f 10 -v
#
# Note: Compatible with Bash 3.2+ (macOS default)
#
################################################################################

set -e

# Default configuration
PACKAGE_NAME="io.opentelemetry.android.demo"
ACTIVITY_NAME=".MainActivity"
MAX_ACTIONS=-1
MAX_DURATION=-1
VERBOSE=0
ACTION_COUNT=0
START_TIME=$(date +%s)
STRETCH_MODE="mixed"
FLUSH_INTERVAL=20
STRETCH_COUNTER=0
CURRENT_STRETCH_SIZE=0
FLUSH_COUNTER=0

# Button data using parallel arrays for Bash 3.2 compatibility
BUTTON_NAMES=(
    "login" "navigate" "api_call" "background" "interaction" "form_submit"
    "ui_freeze" "crash" "network_error" "low_memory" "anr" "force_flush"
)

BUTTON_X=(270 810 270 810 270 810  270 810 270 810 540  540)
BUTTON_Y=(1200 1200 1320 1320 1440 1440  900 900 1020 1020 1140  1600)
BUTTON_WEIGHTS=(15 15 15 15 15 15  5 2 5 1 1  10)

# Regular activity buttons (first 6)
REGULAR_BUTTON_NAMES=("login" "navigate" "api_call" "background" "interaction" "form_submit")
REGULAR_BUTTON_INDICES=(0 1 2 3 4 5)

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

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

show_help() {
    cat << EOF
OpenTelemetry Mobile Demo - Monkey Test Script

Usage: bash $0 [OPTIONS]

Options:
  -a, --actions N         Run N random actions (default: unlimited)
  -t, --time N            Run for N seconds (default: unlimited)
  -s, --stretch-mode MODE Activity stretch mode (default: mixed)
  -f, --flush-interval N  Manual flush every N actions (default: 20, 0=disable)
  -p, --package PKG       Package name (default: $PACKAGE_NAME)
  -v, --verbose           Verbose output
  -h, --help              Show this help

Stretch Modes:
  mixed  - Random mix of short/medium/long stretches (default)
  short  - 5-10 normal actions between triggers
  medium - 15-25 normal actions between triggers
  long   - 30-50 normal actions between triggers
  none   - Always use weighted random (old behavior)

Examples:
  bash $0 --actions 100 --stretch-mode medium
  bash $0 --time 300 --flush-interval 15
  bash $0 -a 50 -s long -f 10 -v

Activity Stretches:
  The test will perform bursts of normal user activity (login, navigate, etc.)
  without triggering errors. After each stretch, it may trigger an error event.
  Manual flushes occur periodically to validate telemetry capture.

EOF
}

check_device() {
    log_info "Checking for connected device..."
    if ! adb devices 2>/dev/null | grep -q "device$"; then
        log_error "No device connected. Please connect a device and try again."
        log_error "Or install adb: brew install android-platform-tools"
        exit 1
    fi
    log_info "Device found"
}

is_app_running() {
    adb shell "pidof $PACKAGE_NAME" > /dev/null 2>&1
    return $?
}

is_app_responding() {
    if adb shell dumpsys activity 2>/dev/null | grep -q "ANR in $PACKAGE_NAME"; then
        return 1
    fi
    if ! is_app_running; then
        return 1
    fi
    return 0
}

start_app() {
    log_info "Starting app: $PACKAGE_NAME"
    adb shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME" > /dev/null 2>&1
    sleep 2
}

stop_app() {
    log_info "Stopping app: $PACKAGE_NAME"
    adb shell am force-stop "$PACKAGE_NAME"
    sleep 1
}

kill_app() {
    log_warn "Force killing app"
    adb shell am kill "$PACKAGE_NAME" 2>/dev/null || true
    sleep 1
}

send_to_background() {
    log_debug "Sending app to background"
    adb shell input keyevent KEYCODE_HOME
}

bring_to_foreground() {
    log_debug "Bringing app to foreground"
    adb shell am start -n "$PACKAGE_NAME/$ACTIVITY_NAME" > /dev/null 2>&1
}

get_button_index() {
    local name=$1
    local i
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
    adb shell input tap $x $y
}

select_random_button() {
    # Create weighted array of button indices
    local weighted_buttons=()
    local i
    for i in "${!BUTTON_NAMES[@]}"; do
        local weight=${BUTTON_WEIGHTS[$i]}
        local j
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
    # Select only from regular activity buttons (first 6)
    local random_idx=$((RANDOM % 6))
    local button_idx=${REGULAR_BUTTON_INDICES[$random_idx]}
    echo "${BUTTON_NAMES[$button_idx]}"
}

get_stretch_size() {
    case $STRETCH_MODE in
        short)
            echo $((RANDOM % 6 + 5))  # 5-10
            ;;
        medium)
            echo $((RANDOM % 11 + 15))  # 15-25
            ;;
        long)
            echo $((RANDOM % 21 + 30))  # 30-50
            ;;
        mixed)
            # Randomly choose short/medium/long
            local mode=$((RANDOM % 3))
            case $mode in
                0) echo $((RANDOM % 6 + 5)) ;;    # short
                1) echo $((RANDOM % 11 + 15)) ;;  # medium
                2) echo $((RANDOM % 21 + 30)) ;;  # long
            esac
            ;;
        none)
            echo 0  # No stretches
            ;;
        *)
            echo 0
            ;;
    esac
}

random_delay() {
    # Random delay between 500ms and 3000ms
    local delay_ms=$((RANDOM % 2500 + 500))
    local delay_sec=$(echo "scale=3; $delay_ms / 1000" | bc 2>/dev/null || echo "1")
    sleep $delay_sec
}

random_background_delay() {
    # Random delay for background duration (1-10 seconds)
    local delay=$((RANDOM % 9 + 1))
    sleep $delay
}

handle_crash() {
    log_warn "App crashed! Waiting before restart..."

    # Random delay before restart (5-60 seconds)
    local restart_delay=$((RANDOM % 55 + 5))
    log_info "Waiting ${restart_delay}s before restart"
    sleep $restart_delay

    # Clean restart
    stop_app
    start_app

    log_info "App restarted after crash"
}

handle_anr() {
    log_warn "App in ANR state! Killing and restarting..."

    kill_app

    # Random delay before restart
    local restart_delay=$((RANDOM % 30 + 10))
    log_info "Waiting ${restart_delay}s before restart"
    sleep $restart_delay

    stop_app
    start_app

    log_info "App restarted after ANR"
}

trigger_manual_flush() {
    log_info "🚀 Triggering manual flush (every $FLUSH_INTERVAL actions)"
    click_button "force_flush"
    sleep 2
}

perform_random_action() {
    ACTION_COUNT=$((ACTION_COUNT + 1))
    FLUSH_COUNTER=$((FLUSH_COUNTER + 1))

    # Check if app is still running and responding
    if ! is_app_responding; then
        if adb shell dumpsys activity 2>/dev/null | grep -q "ANR in $PACKAGE_NAME"; then
            handle_anr
            return
        elif ! is_app_running; then
            handle_crash
            return
        fi
    fi

    # Check for manual flush interval
    if [ $FLUSH_INTERVAL -gt 0 ] && [ $FLUSH_COUNTER -ge $FLUSH_INTERVAL ]; then
        trigger_manual_flush
        FLUSH_COUNTER=0
        return
    fi

    # 10% chance to do a background/foreground transition (only if not in stretch)
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

    # Determine if we're in a stretch or mixing
    local button
    if [ "$STRETCH_MODE" = "none" ]; then
        # Old behavior: weighted random
        button=$(select_random_button)
        log_info "Action #$ACTION_COUNT: Clicking $button"
    else
        # Stretch mode active
        if [ $STRETCH_COUNTER -lt $CURRENT_STRETCH_SIZE ]; then
            # Inside stretch: only regular buttons
            button=$(select_regular_button)
            STRETCH_COUNTER=$((STRETCH_COUNTER + 1))
            log_info "Action #$ACTION_COUNT: Clicking $button (stretch: $STRETCH_COUNTER/$CURRENT_STRETCH_SIZE)"
        else
            # End of stretch: trigger event and start new stretch
            button=$(select_random_button)

            # Prefer trigger buttons at end of stretch
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

            # Start new stretch
            CURRENT_STRETCH_SIZE=$(get_stretch_size)
            STRETCH_COUNTER=0
            if [ $CURRENT_STRETCH_SIZE -gt 0 ]; then
                log_stretch "Starting new stretch: $CURRENT_STRETCH_SIZE normal actions"
            fi
        fi
    fi

    click_button "$button"

    # Special handling for crash button
    if [ "$button" = "crash" ]; then
        log_warn "Crash button clicked - expecting app to crash"
        sleep 2
        if ! is_app_running; then
            handle_crash
        fi
        return
    fi

    # Special handling for ANR button
    if [ "$button" = "anr" ]; then
        log_warn "ANR button clicked - app will freeze for 30s"
        sleep 35
        if ! is_app_responding; then
            handle_anr
        fi
        return
    fi

    # Normal delay between actions
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

################################################################################
# Main Script
################################################################################

# Parse command line arguments
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
        -p|--package)
            PACKAGE_NAME="$2"
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
        log_error "Valid modes: mixed, short, medium, long, none"
        exit 1
        ;;
esac

# Print configuration
log_info "==== Monkey Test Configuration ===="
log_info "Package: $PACKAGE_NAME"
if [ $MAX_ACTIONS -gt 0 ]; then
    log_info "Max actions: $MAX_ACTIONS"
else
    log_info "Max actions: unlimited"
fi
if [ $MAX_DURATION -gt 0 ]; then
    log_info "Max duration: ${MAX_DURATION}s"
else
    log_info "Max duration: unlimited"
fi
log_info "Stretch mode: $STRETCH_MODE"
if [ $FLUSH_INTERVAL -gt 0 ]; then
    log_info "Flush interval: every $FLUSH_INTERVAL actions"
else
    log_info "Flush interval: disabled"
fi
log_info "Verbose: $VERBOSE"
log_info "===================================="
echo

# Check for connected device
check_device

# Stop and restart app for clean start
log_info "Preparing app for monkey test..."
stop_app
start_app

# Initialize stretch if needed
if [ "$STRETCH_MODE" != "none" ]; then
    CURRENT_STRETCH_SIZE=$(get_stretch_size)
    log_stretch "Starting with stretch: $CURRENT_STRETCH_SIZE normal actions"
fi

log_info "Starting monkey test..."
echo

# Main test loop
while should_continue; do
    perform_random_action
done

# Cleanup
log_info "Monkey test complete!"
log_info "Total actions performed: $ACTION_COUNT"
log_info "Total duration: $(($(date +%s) - START_TIME))s"

# Trigger final flush
log_info "Triggering final flush..."
click_button "force_flush"
sleep 3

log_info "Test finished successfully"
