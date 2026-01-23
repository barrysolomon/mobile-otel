#!/usr/bin/env bash

################################################################################
# OpenTelemetry Mobile Demo - Monkey Test Script
#
# This script performs randomized testing of the demo app with:
# - Random button clicks (regular activities and trigger events)
# - Background/foreground transitions
# - Crash and ANR detection
# - Automatic app restart after crashes
# - Configurable test duration or action count
#
# Usage:
#   bash monkey-test.sh [OPTIONS]
#
# Options:
#   -a, --actions N     Run N random actions (default: unlimited)
#   -t, --time N        Run for N seconds (default: unlimited)
#   -p, --package PKG   Package name (default: io.opentelemetry.android.demo)
#   -v, --verbose       Verbose output
#   -h, --help          Show this help
#
# Examples:
#   bash monkey-test.sh --actions 100    # Run 100 random actions
#   bash monkey-test.sh --time 300       # Run for 5 minutes
#   bash monkey-test.sh -a 50 -v         # 50 actions with verbose output
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

# Button data using parallel arrays for Bash 3.2 compatibility
# Format: name, x_coord, y_coord, weight
BUTTON_NAMES=(
    "login" "navigate" "api_call" "background" "interaction" "form_submit"
    "ui_freeze" "crash" "network_error" "low_memory" "anr" "force_flush"
)

BUTTON_X=(270 810 270 810 270 810  270 810 270 810 540  540)
BUTTON_Y=(1200 1200 1320 1320 1440 1440  900 900 1020 1020 1140  1600)
BUTTON_WEIGHTS=(15 15 15 15 15 15  5 2 5 1 1  10)

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
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

show_help() {
    cat << EOF
OpenTelemetry Mobile Demo - Monkey Test Script

Usage: bash $0 [OPTIONS]

Options:
  -a, --actions N     Run N random actions (default: unlimited)
  -t, --time N        Run for N seconds (default: unlimited)
  -p, --package PKG   Package name (default: $PACKAGE_NAME)
  -v, --verbose       Verbose output
  -h, --help          Show this help

Examples:
  bash $0 --actions 100    # Run 100 random actions
  bash $0 --time 300       # Run for 5 minutes
  bash $0 -a 50 -v         # 50 actions with verbose output

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
    # Check if app is in ANR state
    if adb shell dumpsys activity 2>/dev/null | grep -q "ANR in $PACKAGE_NAME"; then
        return 1
    fi

    # Check if app process exists
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

    # Select random button from weighted array
    local total=${#weighted_buttons[@]}
    local random_idx=$((RANDOM % total))
    local button_idx=${weighted_buttons[$random_idx]}

    echo "${BUTTON_NAMES[$button_idx]}"
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

    # Kill the app forcefully
    kill_app

    # Random delay before restart
    local restart_delay=$((RANDOM % 30 + 10))
    log_info "Waiting ${restart_delay}s before restart"
    sleep $restart_delay

    # Clean restart
    stop_app
    start_app

    log_info "App restarted after ANR"
}

perform_random_action() {
    ACTION_COUNT=$((ACTION_COUNT + 1))

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

    # 10% chance to do a background/foreground transition
    if [ $((RANDOM % 10)) -eq 0 ]; then
        log_info "Action #$ACTION_COUNT: Background/Foreground transition"
        send_to_background
        random_background_delay
        bring_to_foreground
        sleep 1
        return
    fi

    # Select and click a random button
    local button=$(select_random_button)
    log_info "Action #$ACTION_COUNT: Clicking $button"
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

    # Check action count limit
    if [ $MAX_ACTIONS -gt 0 ] && [ $ACTION_COUNT -ge $MAX_ACTIONS ]; then
        return 1
    fi

    # Check time limit
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
log_info "Verbose: $VERBOSE"
log_info "===================================="
echo

# Check for connected device
check_device

# Stop and restart app for clean start
log_info "Preparing app for monkey test..."
stop_app
start_app

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
