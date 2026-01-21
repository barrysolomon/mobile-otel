# OTEL Collector Deployment Guide (k3s)

## Prerequisites

* k3s cluster running
* kubectl configured to access the cluster
* Sufficient resources (512MB RAM minimum for collector pod)

## Deployment Commands

### 1. Deploy the OTEL Collector

```bash
# Apply the YAML manifest
kubectl apply -f k8s/otel-collector.yaml

# Expected output:
# namespace/otel-demo created
# configmap/otel-collector-config created
# deployment.apps/otel-collector created
# service/otel-collector created
```

### 2. Wait for Pod to be Ready

```bash
# Watch pod startup
kubectl get pods -n otel-demo -w

# Or check status once
kubectl get pods -n otel-demo

# Expected output:
# NAME                              READY   STATUS    RESTARTS   AGE
# otel-collector-xxxxxxxxxx-xxxxx   1/1     Running   0          30s
```

### 3. Verify Service Endpoints

```bash
# Check service is created
kubectl get svc -n otel-demo

# Expected output:
# NAME             TYPE        CLUSTER-IP      EXTERNAL-IP   PORT(S)
# otel-collector   ClusterIP   10.43.xxx.xxx   <none>        4317/TCP,4318/TCP,8888/TCP
```

## Verification Commands

### 1. Check Collector Logs

```bash
# View collector logs
kubectl logs -n otel-demo -l app=otel-collector --tail=50 -f

# Expected startup logs should show:
# - "Everything is ready. Begin running and processing data."
# - Receivers, processors, and exporters initialized
```

### 2. Test OTLP gRPC Endpoint (from within cluster)

```bash
# Port-forward to local machine
kubectl port-forward -n otel-demo svc/otel-collector 4317:4317 4318:4318
```

In another terminal:

```bash
# Test with grpcurl (if installed)
grpcurl -plaintext localhost:4317 list

# Or test connectivity with telnet
telnet localhost 4317
```

### 3. Verify Configuration

```bash
# Check ConfigMap
kubectl get configmap -n otel-demo otel-collector-config -o yaml

# Check if config is mounted correctly
kubectl exec -n otel-demo deploy/otel-collector -- cat /conf/collector.yaml
```

### 4. Check Resource Usage

```bash
# View pod resource consumption
kubectl top pod -n otel-demo

# View detailed pod description
kubectl describe pod -n otel-demo -l app=otel-collector
```

### 5. Test with Sample Data

Once the Gateway is deployed, you can test the full pipeline:

```bash
# From the Go gateway (once deployed), send a test log
# The collector logs should show received data

# Watch collector logs for incoming data
kubectl logs -n otel-demo -l app=otel-collector --tail=100 -f | grep -i "LogsExporter"
```

## Service Endpoints

### Internal (within k3s cluster)

* OTLP gRPC: `otel-collector.otel-demo.svc.cluster.local:4317`
* OTLP HTTP: `otel-collector.otel-demo.svc.cluster.local:4318`
* Metrics: `otel-collector.otel-demo.svc.cluster.local:8888`

### External Access (Development)

Use port-forwarding to access from outside the cluster:

```bash
# Forward OTLP gRPC
kubectl port-forward -n otel-demo svc/otel-collector 4317:4317

# Or forward both gRPC and HTTP
kubectl port-forward -n otel-demo svc/otel-collector 4317:4317 4318:4318
```

Then access via `localhost:4317` or `localhost:4318`.

## Troubleshooting

### Pod not starting

```bash
# Check pod events
kubectl describe pod -n otel-demo -l app=otel-collector

# Check pod logs for errors
kubectl logs -n otel-demo -l app=otel-collector
```

### Configuration errors

```bash
# Validate ConfigMap syntax
kubectl get configmap -n otel-demo otel-collector-config -o jsonpath='{.data.collector\.yaml}'

# Update config if needed
kubectl edit configmap -n otel-demo otel-collector-config

# Restart collector to pick up changes
kubectl rollout restart deployment -n otel-demo otel-collector
```

### Resource constraints

```bash
# Check if memory limited
kubectl describe pod -n otel-demo -l app=otel-collector | grep -A 5 "State:"

# Increase limits in otel-collector.yaml if needed
```

## Clean Up

```bash
# Remove all resources
kubectl delete -f k8s/otel-collector.yaml

# Or delete just the namespace
kubectl delete namespace otel-demo
```

## Naming Convention

* Namespace: `otel-demo` (all components use this namespace)
* Service name: `otel-collector`
* Gateway will use: `otel-gateway` (Step 2)

## Next Steps

Proceed to Step 2: Go Gateway API
