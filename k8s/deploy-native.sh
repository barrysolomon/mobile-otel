#!/bin/bash
# Quick deployment script for OTEL-native architecture

set -e

echo "=================================="
echo "OTEL-Native Deployment"
echo "=================================="
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Check if kubectl is available
if ! command -v kubectl &> /dev/null; then
    echo -e "${RED}Error: kubectl not found${NC}"
    echo "Please install kubectl first"
    exit 1
fi

# Remove old gateway if it exists
echo "Step 1: Removing old gateway (if exists)..."
kubectl delete -f otel-gateway.yaml 2>/dev/null || echo "  No old gateway found (this is fine)"
echo ""

# Deploy OTEL-native collector
echo "Step 2: Deploying OTEL-native collector..."
kubectl apply -f otel-collector-native.yaml

echo ""
echo "Step 3: Waiting for collector to be ready..."
kubectl wait --for=condition=ready pod -l app=otel-collector -n mobile-observability --timeout=60s

echo ""
echo -e "${GREEN}✓ Deployment complete!${NC}"
echo ""

# Get collector info
echo "=================================="
echo "Collector Information"
echo "=================================="
echo ""

# Get pod status
echo "Pod Status:"
kubectl get pods -n mobile-observability -l app=otel-collector

echo ""
echo "Service Endpoints:"
kubectl get svc -n mobile-observability otel-collector -o wide
kubectl get svc -n mobile-observability otel-collector-external -o wide

echo ""
echo "=================================="
echo "Android App Configuration"
echo "=================================="
echo ""

# Get node IP for external access
NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')

echo "Use this endpoint in your Android app:"
echo ""
echo "val config = MobileConfig("
echo "    serviceName = \"my-mobile-app\","
echo "    serviceVersion = \"1.0.0\","
echo "    collectorEndpoint = \"http://${NODE_IP}:30317\""
echo ")"
echo ""

echo "Or if testing from emulator:"
echo "    collectorEndpoint = \"http://10.0.2.2:30317\""
echo ""

echo "=================================="
echo "Next Steps"
echo "=================================="
echo ""
echo "1. Update Android app with endpoint above"
echo "2. Run the app and trigger scenarios"
echo "3. Check collector logs:"
echo "   kubectl logs -n mobile-observability -l app=otel-collector -f"
echo ""
echo "4. To build custom collector with mobile processor:"
echo "   See: REMAINING_WORK.md Phase 4"
echo ""

echo -e "${YELLOW}Note: Mobile policy processor is configured but not active yet${NC}"
echo -e "${YELLOW}until custom collector is built (Phase 4 in REMAINING_WORK.md)${NC}"
echo ""
