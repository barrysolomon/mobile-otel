#!/bin/bash
# Test runner for OpenTelemetry Native Mobile Observability
#
# This script runs all tests across the project:
# - Android unit tests
# - Go unit tests
# - Integration tests (optional)

set -e

echo "=================================="
echo "OpenTelemetry Native - Test Suite"
echo "=================================="
echo ""

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Track failures
FAILURES=0

# Function to print section headers
print_section() {
    echo ""
    echo "=================================="
    echo "$1"
    echo "=================================="
    echo ""
}

# Function to handle errors
handle_error() {
    echo -e "${RED}✗ $1 failed${NC}"
    FAILURES=$((FAILURES + 1))
}

# Parse command line arguments
RUN_ANDROID=true
RUN_GO=true
RUN_INTEGRATION=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --android-only)
            RUN_GO=false
            shift
            ;;
        --go-only)
            RUN_ANDROID=false
            shift
            ;;
        --integration)
            RUN_INTEGRATION=true
            shift
            ;;
        --help)
            echo "Usage: ./run-tests.sh [options]"
            echo ""
            echo "Options:"
            echo "  --android-only    Run only Android tests"
            echo "  --go-only         Run only Go tests"
            echo "  --integration     Include integration tests (requires emulator)"
            echo "  --help            Show this help message"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

# Android Unit Tests
if [ "$RUN_ANDROID" = true ]; then
    print_section "Running Android Unit Tests"

    cd otel-android-mobile

    if ./gradlew test; then
        echo -e "${GREEN}✓ Android unit tests passed${NC}"
    else
        handle_error "Android unit tests"
    fi

    # Generate coverage report
    if ./gradlew testDebugUnitTestCoverage 2>/dev/null; then
        echo -e "${GREEN}✓ Coverage report generated${NC}"
        echo "   Report location: build/reports/coverage/test/debug/index.html"
    fi

    cd ..
fi

# Go Unit Tests
if [ "$RUN_GO" = true ]; then
    print_section "Running Go Unit Tests (Collector Processor)"

    cd collector-processor/mobilepolicyprocessor

    # Run tests with race detection and coverage
    if go test -v -race -coverprofile=coverage.txt -covermode=atomic ./...; then
        echo -e "${GREEN}✓ Go unit tests passed${NC}"

        # Show coverage summary
        echo ""
        echo "Coverage Summary:"
        go tool cover -func=coverage.txt | tail -n 1

        # Generate HTML coverage report
        go tool cover -html=coverage.txt -o coverage.html 2>/dev/null || true
        if [ -f coverage.html ]; then
            echo "   HTML report: collector-processor/mobilepolicyprocessor/coverage.html"
        fi
    else
        handle_error "Go unit tests"
    fi

    cd ../..
fi

# Android Integration Tests (optional)
if [ "$RUN_INTEGRATION" = true ] && [ "$RUN_ANDROID" = true ]; then
    print_section "Running Android Integration Tests"

    echo -e "${YELLOW}Note: This requires a running Android emulator${NC}"
    echo ""

    cd otel-android-mobile

    # Check if emulator is running
    if adb devices | grep -q "emulator"; then
        if ./gradlew connectedAndroidTest; then
            echo -e "${GREEN}✓ Android integration tests passed${NC}"
        else
            handle_error "Android integration tests"
        fi
    else
        echo -e "${YELLOW}⚠ No emulator detected, skipping integration tests${NC}"
        echo "  Start an emulator with: emulator -avd <avd_name>"
    fi

    cd ..
fi

# Summary
print_section "Test Summary"

if [ $FAILURES -eq 0 ]; then
    echo -e "${GREEN}✓ All tests passed!${NC}"
    echo ""
    echo "Test reports:"
    [ "$RUN_ANDROID" = true ] && echo "  - Android: otel-android-mobile/build/reports/tests/testDebugUnitTest/index.html"
    [ "$RUN_GO" = true ] && echo "  - Go:      collector-processor/mobilepolicyprocessor/coverage.html"
    echo ""
    exit 0
else
    echo -e "${RED}✗ $FAILURES test suite(s) failed${NC}"
    echo ""
    echo "Check the output above for details."
    exit 1
fi
