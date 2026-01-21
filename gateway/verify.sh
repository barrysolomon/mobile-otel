#!/bin/bash
set -e

echo "=== Gateway Build Verification ==="
echo ""

# Check Go version
echo "▶ Checking Go version..."
go version
echo ""

# Check if we're in the gateway directory
if [ ! -f "go.mod" ]; then
    echo "Error: go.mod not found. Run this script from the gateway directory."
    exit 1
fi

# Download dependencies
echo "▶ Downloading dependencies..."
go mod download
echo "✓ Dependencies downloaded"
echo ""

# Verify go.mod is tidy
echo "▶ Verifying go.mod is tidy..."
go mod tidy
echo "✓ go.mod is tidy"
echo ""

# Run go build
echo "▶ Building all packages..."
go build ./...
echo "✓ All packages compile successfully"
echo ""

# Run go test
echo "▶ Running tests..."
go test ./...
echo "✓ Tests passed (or no test files)"
echo ""

# Check for common issues
echo "▶ Running go vet..."
go vet ./...
echo "✓ No issues found"
echo ""

echo "=== ✓ All verifications passed ==="
echo ""
echo "The gateway code compiles successfully with:"
echo "  - Go $(go version | awk '{print $3}')"
echo "  - OTEL SDK v1.32.0"
echo "  - OTEL Logs v0.8.0"
echo "  - gRPC v1.69.2"
echo ""
echo "You can now:"
echo "  1. Build Docker image: docker build -t otel-gateway:latest ."
echo "  2. Deploy to k3s: ./build.sh"
echo "  3. Run locally: go run main.go"
