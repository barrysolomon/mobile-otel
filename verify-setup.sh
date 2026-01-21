#!/bin/bash

# Mobile Observability Demo - Setup Verification Script
# Verifies all components are properly configured and ready to deploy

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

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

print_header "Mobile Observability Demo - Setup Verification"
echo ""

# ============================================================================
# Prerequisites Check
# ============================================================================

print_header "1. Prerequisites"

# kubectl
if command_exists kubectl; then
    KUBECTL_VERSION=$(kubectl version --client -o json 2>/dev/null | grep -o '"gitVersion":"[^"]*"' | head -1 | cut -d'"' -f4)
    print_success "kubectl installed: $KUBECTL_VERSION"
else
    print_error "kubectl not found - required for Kubernetes deployment"
fi

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
        print_warning "Go version < 1.21 - gateway requires Go 1.21+"
    fi
else
    print_warning "Go not found - required to build gateway (optional if using Docker)"
fi

# Node.js
if command_exists node; then
    NODE_VERSION=$(node --version)
    print_success "Node.js installed: $NODE_VERSION"

    # Check Node version >= 18
    NODE_MAJOR=$(node --version | sed 's/v\([0-9]*\).*/\1/')
    if [ "$NODE_MAJOR" -ge 18 ]; then
        print_success "Node.js version >= 18 ✓"
    else
        print_warning "Node.js version < 18 - Control Plane UI requires Node 18+"
    fi
else
    print_warning "Node.js not found - required for Control Plane UI"
fi

# npm
if command_exists npm; then
    NPM_VERSION=$(npm --version)
    print_success "npm installed: $NPM_VERSION"
else
    print_warning "npm not found - required for Control Plane UI"
fi

# Android Studio / SDK (optional)
if command_exists adb; then
    print_success "adb found - Android SDK installed"
else
    print_warning "adb not found - Android SDK required to build and deploy app"
fi

echo ""

# ============================================================================
# Project Structure Check
# ============================================================================

print_header "2. Project Structure"

# k8s directory
if dir_exists "k8s"; then
    print_success "k8s/ directory exists"

    if file_exists "k8s/otel-collector.yaml"; then
        print_success "k8s/otel-collector.yaml found"
    else
        print_error "k8s/otel-collector.yaml missing"
    fi

    if file_exists "k8s/otel-gateway.yaml"; then
        print_success "k8s/otel-gateway.yaml found"
    else
        print_error "k8s/otel-gateway.yaml missing"
    fi
else
    print_error "k8s/ directory missing"
fi

# gateway directory
if dir_exists "gateway"; then
    print_success "gateway/ directory exists"

    if file_exists "gateway/go.mod"; then
        print_success "gateway/go.mod found"
    else
        print_error "gateway/go.mod missing"
    fi

    if file_exists "gateway/main.go"; then
        print_success "gateway/main.go found"
    else
        print_error "gateway/main.go missing"
    fi
else
    print_error "gateway/ directory missing"
fi

# android-app directory
if dir_exists "android-app"; then
    print_success "android-app/ directory exists"

    if file_exists "android-app/build.gradle.kts"; then
        print_success "android-app/build.gradle.kts found"
    else
        print_error "android-app/build.gradle.kts missing"
    fi
else
    print_error "android-app/ directory missing"
fi

# control-plane-ui directory
if dir_exists "control-plane-ui"; then
    print_success "control-plane-ui/ directory exists"

    if file_exists "control-plane-ui/package.json"; then
        print_success "control-plane-ui/package.json found"
    else
        print_error "control-plane-ui/package.json missing"
    fi

    if file_exists "control-plane-ui/vite.config.ts"; then
        print_success "control-plane-ui/vite.config.ts found"
    else
        print_error "control-plane-ui/vite.config.ts missing"
    fi
else
    print_error "control-plane-ui/ directory missing"
fi

echo ""

# ============================================================================
# Documentation Check
# ============================================================================

print_header "3. Documentation"

DOCS=(
    "README.md"
    "DEPLOYMENT_GUIDE.md"
    "QUICK_REFERENCE.md"
    "ARCHITECTURE.md"
    "E2E_VERIFICATION_CHECKLIST.md"
    "VERIFICATION_PACK.md"
    "FINAL_STATUS.md"
)

for doc in "${DOCS[@]}"; do
    if file_exists "$doc"; then
        print_success "$doc exists"
    else
        print_warning "$doc missing (not critical)"
    fi
done

echo ""

# ============================================================================
# Gateway Build Check
# ============================================================================

print_header "4. Gateway Build Verification"

if command_exists go && dir_exists "gateway"; then
    cd gateway

    print_info "Running: go mod verify"
    if go mod verify >/dev/null 2>&1; then
        print_success "go.mod verification passed"
    else
        print_error "go.mod verification failed"
    fi

    print_info "Running: go build ./..."
    if go build ./... >/dev/null 2>&1; then
        print_success "Gateway builds successfully"

        # Clean up build artifacts
        rm -f gateway
    else
        print_error "Gateway build failed"
    fi

    cd "$SCRIPT_DIR"
else
    print_warning "Skipping gateway build verification (Go not installed or gateway/ missing)"
fi

echo ""

# ============================================================================
# Control Plane UI Check
# ============================================================================

print_header "5. Control Plane UI Dependencies"

if command_exists npm && dir_exists "control-plane-ui"; then
    cd control-plane-ui

    if file_exists "package-lock.json"; then
        print_info "package-lock.json exists"
    else
        print_info "package-lock.json not found (will be created on npm install)"
    fi

    if dir_exists "node_modules"; then
        print_success "node_modules/ exists (dependencies already installed)"
    else
        print_info "node_modules/ not found - run 'npm install' to install dependencies"
    fi

    cd "$SCRIPT_DIR"
else
    print_warning "Skipping UI verification (npm not installed or control-plane-ui/ missing)"
fi

echo ""

# ============================================================================
# Kubernetes Cluster Check
# ============================================================================

print_header "6. Kubernetes Cluster Connectivity"

if command_exists kubectl; then
    print_info "Testing kubectl connectivity..."

    if kubectl cluster-info >/dev/null 2>&1; then
        print_success "kubectl can connect to cluster"

        # Check if otel-demo namespace exists
        if kubectl get namespace otel-demo >/dev/null 2>&1; then
            print_info "otel-demo namespace already exists"

            # Check if pods are running
            COLLECTOR_PODS=$(kubectl get pods -n otel-demo -l app=otel-collector --no-headers 2>/dev/null | wc -l)
            GATEWAY_PODS=$(kubectl get pods -n otel-demo -l app=otel-gateway --no-headers 2>/dev/null | wc -l)

            if [ "$COLLECTOR_PODS" -gt 0 ]; then
                print_info "OTEL Collector pod(s) found: $COLLECTOR_PODS"
            fi

            if [ "$GATEWAY_PODS" -gt 0 ]; then
                print_info "Gateway pod(s) found: $GATEWAY_PODS"
            fi
        else
            print_info "otel-demo namespace not found (will be created on deployment)"
        fi
    else
        print_error "kubectl cannot connect to cluster - verify cluster is running"
    fi
else
    print_warning "kubectl not found - skipping cluster connectivity check"
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
    echo -e "${GREEN}✓ System ready for deployment!${NC}"
    echo ""
    echo "Next steps:"
    echo "  1. Deploy to Kubernetes: kubectl apply -f k8s/"
    echo "  2. Port forward gateway: kubectl port-forward -n otel-demo svc/otel-gateway 8080:8080"
    echo "  3. Start UI: cd control-plane-ui && npm install && npm run dev"
    echo "  4. Build Android app: cd android-app && ./gradlew installDebug"
    echo ""
    echo "For detailed instructions, see: DEPLOYMENT_GUIDE.md"
    exit 0
else
    echo -e "${RED}✗ Some critical checks failed${NC}"
    echo ""
    echo "Please fix the errors above before deploying."
    echo "Refer to DEPLOYMENT_GUIDE.md for detailed setup instructions."
    exit 1
fi
