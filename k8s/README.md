# OTEL Collector Deployment Configurations

This directory contains various deployment configurations for the OpenTelemetry Collector.

---

## Quick Start: Local Development with Docker

For local development and testing, use Docker Compose:

```bash
docker-compose up -d
```

This starts:
- **OTEL Collector** on ports 4317 (gRPC) and 4318 (HTTP)
- **Jaeger UI** on port 16686 for visualizing traces

**View logs:**
```bash
docker logs -f otel-collector
```

**Stop services:**
```bash
docker-compose down
```

**Files used:**
- `docker-compose.yml` - Service definitions
- `otel-collector-docker.yaml` - Collector configuration for Docker

---

## Kubernetes Deployment

For production Kubernetes deployments:

### Option 1: Native OTEL Configuration (Recommended)

Use the native OpenTelemetry Collector without custom processors:

```bash
./deploy-native.sh
```

**Files:**
- `otel-collector-native.yaml` - Native collector configuration
- `deploy-native.sh` - Deployment script

**Best for:**
- Production environments
- Standard OTEL use cases
- Simplicity and stability

### Option 2: Custom Processor (Advanced)

Use the custom Mobile Policy Processor for advanced mobile-specific features:

```bash
kubectl apply -f otel-collector.yaml
kubectl apply -f otel-gateway.yaml
```

**Files:**
- `otel-collector.yaml` - Collector with custom processor
- `otel-gateway.yaml` - Gateway configuration
- `../collector-processor/` - Custom processor source code

**Best for:**
- Advanced mobile policy evaluation
- Custom attribute enrichment
- Research and experimentation

**See:** [DEPLOYMENT.md](DEPLOYMENT.md) for detailed Kubernetes setup
**See:** [MIGRATION_TO_NATIVE.md](MIGRATION_TO_NATIVE.md) for migration guide

---

## Network Configuration

### Android Emulator
Use `http://10.0.2.2:4317` as your collector endpoint
- `10.0.2.2` maps to your host machine's `localhost`

### Real Android Device
Use `http://YOUR_MACHINE_IP:4317`
- Find your IP:
  - **macOS**: `ipconfig getifaddr en0`
  - **Linux**: `hostname -I | awk '{print $1}'`
  - **Windows**: `ipconfig` (look for IPv4 Address)
- Ensure device and computer are on the same network

### Production
Use your deployed collector's hostname/IP
- Example: `https://otel-collector.yourdomain.com:4317`
- Ensure proper TLS/SSL configuration
- Configure authentication headers if needed

---

## Configuration Files Reference

| File | Purpose | Deployment |
|------|---------|------------|
| `docker-compose.yml` | Local dev setup | Docker |
| `otel-collector-docker.yaml` | Collector config for Docker | Docker |
| `otel-collector-native.yaml` | Native OTEL config | Kubernetes |
| `otel-collector.yaml` | Custom processor config | Kubernetes |
| `otel-gateway.yaml` | Gateway config | Kubernetes |
| `deploy-native.sh` | Deployment automation | Kubernetes |

---

## Viewing Telemetry Data

### Console Logs (Docker)
```bash
docker logs -f otel-collector
```

### Jaeger UI (Docker)
Open browser to: http://localhost:16686
- Select your service name
- Click "Find Traces"
- View detailed trace information

### Kubernetes Logs
```bash
# View collector logs
kubectl logs -f deployment/otel-collector -n mobile-observability

# View collector metrics
kubectl port-forward svc/otel-collector 8888:8888 -n mobile-observability
# Then visit http://localhost:8888/metrics
```

---

## Troubleshooting

### Collector Not Receiving Data

**Check connectivity:**
```bash
# Test gRPC endpoint
grpcurl -plaintext localhost:4317 list

# Test HTTP endpoint
curl http://localhost:4318/v1/traces
```

**Check collector logs:**
```bash
docker logs otel-collector
```

**Common issues:**
- Firewall blocking port 4317/4318
- Wrong endpoint in mobile app config
- Android emulator: using `localhost` instead of `10.0.2.2`
- Real device: not on same network as collector

### Jaeger UI Shows No Data

- Wait 10-30 seconds after generating events
- Check collector logs for export errors
- Verify Jaeger container is running: `docker ps`
- Check mobile app is successfully exporting: look for success messages in app logs

### Port Already in Use

```bash
# Find what's using the port
lsof -i :4317

# Stop conflicting services or change ports in docker-compose.yml
```

---

## Next Steps

- **Development**: See [QUICKSTART.md](../QUICKSTART.md)
- **Production Deployment**: See [DEPLOYMENT.md](DEPLOYMENT.md)
- **Migration Guide**: See [MIGRATION_TO_NATIVE.md](MIGRATION_TO_NATIVE.md)
- **Custom Processor**: See [collector-processor/README.md](../collector-processor/mobilepolicyprocessor/README.md)
