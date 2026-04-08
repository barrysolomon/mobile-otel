#!/bin/bash

# Mobile OTel SDK - Setup Verification Script
# Verifies Android SDK and collector processor are properly configured

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$REPO_ROOT"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Counters
CHECKS_PASSED=0
CHECKS_FAILED=0
CHECKS_WARNING=0

print_header() {
    echo -e "${BLUE}================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}================================${NC}"
}

print_success() {
    echo -e "${GREEN}✓${NC} $1"
    ((CHECKS_PASSED++))
}

print_error() {
    echo -e "${RED}✗${NC} $1"
    ((CHECKS_FAILED++))
}

print_warning() {
    echo -e "${YELLOW}⚠${NC} $1"
    ((CHECKS_WARNING++))
}

print_info() {
    echo -e "${BLUE}ℹ${NC} $1"
}

# Check if a command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Check if a file exists
file_exists() {
    [ -f "$1" ]
}

# Check if a directory exists
dir_exists() {
    [ -d "$1" ]
}

print_header "Mobile OTel SDK - Setup Verification"
echo ""

# ============================================================================
# Prerequisites Check
# ============================================================================

print_header "1. Prerequisites"

# Go
if command_exists go; then
    GO_VERSION=$(go version | awk '{print $3}')
    print_success "Go installed: $GO_VERSION"

    # Check Go version >= 1.21
    GO_MAJOR=$(go version | sed -n 's/.*go\([0-9]*\)\.\([0-9]*\).*/\1/p')
    GO_MINOR=$(go version | sed -n 's/.*go\([0-9]*\)\.\([0-9]*\).*/\2/p')
    if [ "$GO_MAJOR" -ge 1 ] && [ "$GO_MINOR" -ge 21 ]; then
        print_success "Go version >= 1.21 ✓"
    else
        print_warning "Go version < 1.21 - collector processor requires Go 1.21+"
    fi
else
    print_warning "Go not found - required to build collector processor"
fi

# Android Studio / SDK
if command_exists adb; then
    print_success "adb found - Android SDK installed"
else
    print_warning "adb not found - Android SDK required to build and deploy app"
fi

# JDK
if command_exists java; then
    JAVA_VERSION=$(java -version 2>&1 | head -1)
    print_success "Java installed: $JAVA_VERSION"
else
    print_warning "Java not found - JDK 17 required for Android SDK library"
fi

echo ""

# ============================================================================
# Project Structure Check
# ============================================================================

print_header "2. Project Structure"

# otel-android-mobile directory
if dir_exists "otel-android-mobile"; then
    print_success "otel-android-mobile/ directory exists"
else
    print_error "otel-android-mobile/ directory missing"
fi

# collector-processor directory
if dir_exists "collector-processor"; then
    print_success "collector-processor/ directory exists"

    if file_exists "collector-processor/mobilepolicyprocessor/go.mod"; then
        print_success "collector-processor/mobilepolicyprocessor/go.mod found"
    else
        print_error "collector-processor/mobilepolicyprocessor/go.mod missing"
    fi
else
    print_error "collector-processor/ directory missing"
fi

# examples/demo-app directory
if dir_exists "examples/demo-app"; then
    print_success "examples/demo-app/ directory exists"

    if file_exists "examples/demo-app/gradlew"; then
        print_success "examples/demo-app/gradlew found"
    else
        print_error "examples/demo-app/gradlew missing"
    fi
else
    print_error "examples/demo-app/ directory missing"
fi

echo ""

# ============================================================================
# Android SDK Build Check
# ============================================================================

print_header "3. Android SDK Build Verification"

if dir_exists "examples/demo-app" && file_exists "examples/demo-app/gradlew"; then
    cd examples/demo-app

    print_info "Running: ./gradlew :otel-android-mobile:build (dry run check)"
    if ./gradlew :otel-android-mobile:tasks >/dev/null 2>&1; then
        print_success "Android SDK Gradle project is valid"
    else
        print_warning "Android SDK Gradle project check failed (may need Android SDK configured)"
    fi

    cd "$REPO_ROOT"
else
    print_warning "Skipping Android SDK build verification (demo-app missing)"
fi

echo ""

# ============================================================================
# Collector Processor Build Check
# ============================================================================

print_header "4. Collector Processor Build Verification"

if command_exists go && dir_exists "collector-processor/mobilepolicyprocessor"; then
    cd collector-processor/mobilepolicyprocessor

    print_info "Running: go mod verify"
    if go mod verify >/dev/null 2>&1; then
        print_success "go.mod verification passed"
    else
        print_warning "go.mod verification failed (run 'go mod tidy' first)"
    fi

    print_info "Running: go build ./..."
    if go build ./... >/dev/null 2>&1; then
        print_success "Collector processor builds successfully"
    else
        print_error "Collector processor build failed"
    fi

    cd "$REPO_ROOT"
else
    print_warning "Skipping collector processor build verification (Go not installed or directory missing)"
fi

echo ""

# ============================================================================
# Summary
# ============================================================================

print_header "Verification Summary"

echo ""
echo -e "${GREEN}Checks Passed:${NC} $CHECKS_PASSED"
echo -e "${YELLOW}Warnings:${NC} $CHECKS_WARNING"
echo -e "${RED}Checks Failed:${NC} $CHECKS_FAILED"
echo ""

if [ $CHECKS_FAILED -eq 0 ]; then
    echo -e "${GREEN}✓ Setup looks good!${NC}"
    echo ""
    echo "Next steps:"
    echo "  1. Build Android SDK: cd examples/demo-app && ./gradlew :otel-android-mobile:build"
    echo "  2. Run tests: ./run-tests.sh"
    echo "  3. Build demo app: cd examples/demo-app && ./gradlew installDebug"
    echo ""
    echo "Gateway, Control Plane UI, and k8s manifests are in the sister repo:"
    echo "  https://github.com/barrysolomon/mobile-otel-control-plane"
    exit 0
else
    echo -e "${RED}✗ Some critical checks failed${NC}"
    echo ""
    echo "Please fix the errors above."
    exit 1
fi
