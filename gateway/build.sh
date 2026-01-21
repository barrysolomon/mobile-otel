#!/bin/bash
set -e

echo "=== Building Mobile Observability Gateway ==="

# Configuration
IMAGE_NAME="otel-gateway"
IMAGE_TAG="latest"
NAMESPACE="mobile-observability"

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

step() {
    echo -e "${GREEN}▶ $1${NC}"
}

warn() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

# Check prerequisites
step "Checking prerequisites..."
command -v go >/dev/null 2>&1 || { echo "Go is required but not installed. Aborting." >&2; exit 1; }
command -v docker >/dev/null 2>&1 || { echo "Docker is required but not installed. Aborting." >&2; exit 1; }
command -v kubectl >/dev/null 2>&1 || { echo "kubectl is required but not installed. Aborting." >&2; exit 1; }

# Download dependencies
step "Downloading Go dependencies..."
go mod tidy

# Run tests (if any exist)
if ls *_test.go 1> /dev/null 2>&1; then
    step "Running tests..."
    go test ./...
fi

# Build Docker image
step "Building Docker image: ${IMAGE_NAME}:${IMAGE_TAG}..."
docker build -t ${IMAGE_NAME}:${IMAGE_TAG} .

# Import into k3s
if command -v k3s >/dev/null 2>&1; then
    step "Importing image into k3s..."
    docker save ${IMAGE_NAME}:${IMAGE_TAG} | k3s ctr images import -
else
    warn "k3s not found, skipping image import"
    warn "If using k3s, manually import with: docker save ${IMAGE_NAME}:${IMAGE_TAG} | k3s ctr images import -"
fi

# Deploy to k3s
step "Deploying to k3s..."
kubectl apply -f ../k8s/otel-gateway.yaml

# Wait for deployment
step "Waiting for deployment to be ready..."
kubectl wait --for=condition=available --timeout=60s deployment/otel-gateway -n ${NAMESPACE}

# Show status
step "Deployment status:"
kubectl get pods -n ${NAMESPACE} -l app=otel-gateway

step "Service status:"
kubectl get svc -n ${NAMESPACE} -l app=otel-gateway

echo ""
echo -e "${GREEN}✓ Gateway deployed successfully!${NC}"
echo ""
echo "To view logs:"
echo "  kubectl logs -n ${NAMESPACE} -l app=otel-gateway -f"
echo ""
echo "To test locally:"
echo "  kubectl port-forward -n ${NAMESPACE} svc/otel-gateway 8080:8080"
echo "  curl http://localhost:8080/health"
