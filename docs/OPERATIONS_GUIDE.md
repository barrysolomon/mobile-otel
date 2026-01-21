# Operations Guide

Complete guide for deploying and operating the Mobile Observability system in production.

## Table of Contents

1. [Production Architecture](#production-architecture)
2. [Pre-Deployment Checklist](#pre-deployment-checklist)
3. [Infrastructure Setup](#infrastructure-setup)
4. [Security Configuration](#security-configuration)
5. [Deployment](#deployment)
6. [Monitoring & Alerting](#monitoring--alerting)
7. [Scaling](#scaling)
8. [Backup & Recovery](#backup--recovery)
9. [Operational Runbooks](#operational-runbooks)
10. [Incident Response](#incident-response)

## Production Architecture

### High-Level Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                      Production System                       │
└──────────────────────────────────────────────────────────────┘

Internet                Load Balancer            Kubernetes
   │                         │                        │
   │                    ┌────▼────┐             ┌────▼────┐
   │                    │  Ingress │             │   Pod   │
   │                    │  (TLS)   │             │ Gateway │
   │                    └────┬─────┘             │  x3     │
   │                         │                   └────┬────┘
   │                         │                        │
Mobile Devices ─────────────┘                         │
   │                                                   │
   │                                          ┌────────▼────────┐
   │                                          │  PostgreSQL     │
   │                                          │  (Config)       │
   │                                          └─────────────────┘
   │                                                   │
   │                                          ┌────────▼────────┐
   │                                          │  OTEL Collector │
   │                                          │  x2             │
   │                                          └────┬────────────┘
   │                                               │
   │                                               ├────► Loki
   │                                               ├────► Prometheus
   │                                               └────► Jaeger
   │
   └──────────────────────────────────────────────────────────►
                          Grafana (Visualization)
```

### Component Distribution

| Component | Replicas | CPU | Memory | Storage |
|-----------|----------|-----|--------|---------|
| Gateway | 3 | 500m | 512Mi | - |
| OTEL Collector | 2 | 1000m | 1Gi | - |
| PostgreSQL | 1 | 500m | 1Gi | 50Gi |
| Control Plane UI | 2 | 250m | 256Mi | - |

### Network Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Kubernetes Cluster                      │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌───────────────────────────────────────────────────────┐ │
│  │              Namespace: mobile-observability-prod                │ │
│  │                                                       │ │
│  │  ┌──────────────┐     ┌──────────────┐             │ │
│  │  │  Gateway     │────►│  PostgreSQL  │             │ │
│  │  │  Service     │     │  Service     │             │ │
│  │  │  (ClusterIP) │     │  (ClusterIP) │             │ │
│  │  └──────┬───────┘     └──────────────┘             │ │
│  │         │                                           │ │
│  │         │              ┌──────────────┐             │ │
│  │         └─────────────►│  Collector   │             │ │
│  │                        │  Service     │             │ │
│  │                        │  (ClusterIP) │             │ │
│  │                        └──────┬───────┘             │ │
│  │                               │                     │ │
│  │                               ▼                     │ │
│  │                        Backend Services             │ │
│  │                        (Loki, Prom, etc)            │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐ │
│  │                  Ingress Controller                   │ │
│  │         gateway.yourcompany.com (TLS)                 │ │
│  │         ui.yourcompany.com (TLS)                      │ │
│  └───────────────────────────────────────────────────────┘ │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

## Pre-Deployment Checklist

### Infrastructure Requirements

- [ ] Kubernetes cluster (1.24+)
- [ ] kubectl access configured
- [ ] Helm 3 installed
- [ ] Container registry access
- [ ] DNS management access
- [ ] TLS certificates (or cert-manager)
- [ ] PostgreSQL (managed service recommended)
- [ ] Monitoring stack (Prometheus, Grafana)
- [ ] Log aggregation (Loki or ELK)

### Security Requirements

- [ ] API keys generated
- [ ] TLS certificates obtained
- [ ] Secrets management configured
- [ ] Network policies defined
- [ ] RBAC roles configured
- [ ] Service account created
- [ ] Image scanning enabled
- [ ] Vulnerability scanning active

### Operational Requirements

- [ ] Backup strategy defined
- [ ] Disaster recovery plan
- [ ] Monitoring dashboards created
- [ ] Alert rules configured
- [ ] On-call rotation established
- [ ] Incident response playbook
- [ ] Runbook documentation
- [ ] SLO/SLA defined

## Infrastructure Setup

### 1. Kubernetes Cluster

**Recommended Specifications:**

```yaml
Node Pool:
  - Count: 3 nodes minimum
  - Instance Type: n1-standard-4 (GCP) / t3.xlarge (AWS)
  - CPU: 4 vCPU per node
  - Memory: 16 GB per node
  - Disk: 100 GB per node
  - Auto-scaling: Enabled (3-10 nodes)
```

**Create cluster (GKE example):**

```bash
gcloud container clusters create mobile-observability-prod \
  --zone=us-central1-a \
  --num-nodes=3 \
  --machine-type=n1-standard-4 \
  --enable-autoscaling \
  --min-nodes=3 \
  --max-nodes=10 \
  --enable-autorepair \
  --enable-autoupgrade
```

### 2. PostgreSQL Setup

**Option A: Managed Service (Recommended)**

```bash
# GCP Cloud SQL
gcloud sql instances create otel-gateway-db \
  --database-version=POSTGRES_15 \
  --tier=db-f1-micro \
  --region=us-central1

# AWS RDS
aws rds create-db-instance \
  --db-instance-identifier otel-gateway-db \
  --db-instance-class db.t3.micro \
  --engine postgres \
  --allocated-storage 20
```

**Option B: In-Cluster PostgreSQL**

```yaml
# postgres.yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: postgres
  namespace: mobile-observability-prod
spec:
  serviceName: postgres
  replicas: 1
  selector:
    matchLabels:
      app: postgres
  template:
    metadata:
      labels:
        app: postgres
    spec:
      containers:
      - name: postgres
        image: postgres:15-alpine
        env:
        - name: POSTGRES_PASSWORD
          valueFrom:
            secretKeyRef:
              name: postgres-secret
              key: password
        - name: POSTGRES_DB
          value: gateway
        ports:
        - containerPort: 5432
        volumeMounts:
        - name: postgres-storage
          mountPath: /var/lib/postgresql/data
  volumeClaimTemplates:
  - metadata:
      name: postgres-storage
    spec:
      accessModes: ["ReadWriteOnce"]
      resources:
        requests:
          storage: 50Gi
```

### 3. Container Registry

**Push images:**

```bash
# Build and tag
docker build -t gcr.io/your-project/gateway:1.0.0 gateway/
docker build -t gcr.io/your-project/ui:1.0.0 control-plane-ui/

# Push
docker push gcr.io/your-project/gateway:1.0.0
docker push gcr.io/your-project/ui:1.0.0
```

### 4. DNS Configuration

**Create DNS records:**

```
gateway.yourcompany.com  → Load Balancer IP
ui.yourcompany.com       → Load Balancer IP
```

## Security Configuration

### 1. TLS Certificates

**Using cert-manager:**

```yaml
# certificate.yaml
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: gateway-tls
  namespace: mobile-observability-prod
spec:
  secretName: gateway-tls-secret
  issuerRef:
    name: letsencrypt-prod
    kind: ClusterIssuer
  dnsNames:
  - gateway.yourcompany.com
  - ui.yourcompany.com
```

### 2. Secrets Management

**Create secrets:**

```bash
# PostgreSQL password
kubectl create secret generic postgres-secret \
  --from-literal=password='your-secure-password' \
  -n mobile-observability-prod

# API keys
kubectl create secret generic gateway-api-keys \
  --from-literal=admin-key='admin-api-key-here' \
  --from-literal=device-key='device-api-key-here' \
  -n mobile-observability-prod

# JWT signing key
kubectl create secret generic jwt-secret \
  --from-literal=signing-key='your-jwt-signing-key' \
  -n mobile-observability-prod
```

### 3. Network Policies

**Restrict traffic:**

```yaml
# network-policy.yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: gateway-policy
  namespace: mobile-observability-prod
spec:
  podSelector:
    matchLabels:
      app: otel-gateway
  policyTypes:
  - Ingress
  - Egress
  ingress:
  - from:
    - podSelector:
        matchLabels:
          app: ingress-controller
    ports:
    - protocol: TCP
      port: 8080
  egress:
  - to:
    - podSelector:
        matchLabels:
          app: postgres
    ports:
    - protocol: TCP
      port: 5432
  - to:
    - podSelector:
        matchLabels:
          app: otel-collector
    ports:
    - protocol: TCP
      port: 4317
```

### 4. RBAC Configuration

**Create service account:**

```yaml
# rbac.yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: gateway-sa
  namespace: mobile-observability-prod
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: gateway-role
  namespace: mobile-observability-prod
rules:
- apiGroups: [""]
  resources: ["secrets"]
  verbs: ["get", "list"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: gateway-rolebinding
  namespace: mobile-observability-prod
subjects:
- kind: ServiceAccount
  name: gateway-sa
roleRef:
  kind: Role
  name: gateway-role
  apiGroup: rbac.authorization.k8s.io
```

## Deployment

### 1. Create Namespace

```bash
kubectl create namespace mobile-observability-prod
kubectl label namespace mobile-observability-prod environment=production
```

### 2. Deploy Gateway

**Update deployment with production config:**

```yaml
# gateway-prod.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: otel-gateway
  namespace: mobile-observability-prod
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  selector:
    matchLabels:
      app: otel-gateway
  template:
    metadata:
      labels:
        app: otel-gateway
        version: "1.0.0"
    spec:
      serviceAccountName: gateway-sa
      containers:
      - name: gateway
        image: gcr.io/your-project/gateway:1.0.0
        ports:
        - containerPort: 8080
        env:
        - name: PORT
          value: "8080"
        - name: DB_CONNECTION_STRING
          value: "host=postgres.mobile-observability-prod.svc.cluster.local port=5432 user=gateway dbname=gateway sslmode=require"
        - name: DB_PASSWORD
          valueFrom:
            secretKeyRef:
              name: postgres-secret
              key: password
        - name: OTEL_COLLECTOR_ENDPOINT
          value: "otel-collector.mobile-observability-prod.svc.cluster.local:4317"
        - name: API_KEY_REQUIRED
          value: "true"
        resources:
          requests:
            cpu: 250m
            memory: 256Mi
          limits:
            cpu: 500m
            memory: 512Mi
        livenessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 30
        readinessProbe:
          httpGet:
            path: /health
            port: 8080
          initialDelaySeconds: 5
          periodSeconds: 10
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
          - weight: 100
            podAffinityTerm:
              labelSelector:
                matchLabels:
                  app: otel-gateway
              topologyKey: kubernetes.io/hostname
```

**Deploy:**

```bash
kubectl apply -f gateway-prod.yaml
```

### 3. Deploy OTEL Collector

```yaml
# collector-prod.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: otel-collector
  namespace: mobile-observability-prod
spec:
  replicas: 2
  selector:
    matchLabels:
      app: otel-collector
  template:
    metadata:
      labels:
        app: otel-collector
    spec:
      containers:
      - name: collector
        image: otel/opentelemetry-collector-contrib:0.91.0
        args:
        - "--config=/etc/otel-collector-config.yaml"
        resources:
          requests:
            cpu: 500m
            memory: 512Mi
          limits:
            cpu: 1000m
            memory: 1Gi
        ports:
        - containerPort: 4317  # OTLP gRPC
        - containerPort: 4318  # OTLP HTTP
        - containerPort: 8888  # Metrics
        volumeMounts:
        - name: config
          mountPath: /etc/otel-collector-config.yaml
          subPath: otel-collector-config.yaml
      volumes:
      - name: config
        configMap:
          name: otel-collector-config
```

### 4. Deploy Control Plane UI

```yaml
# ui-prod.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: control-plane-ui
  namespace: mobile-observability-prod
spec:
  replicas: 2
  selector:
    matchLabels:
      app: control-plane-ui
  template:
    metadata:
      labels:
        app: control-plane-ui
    spec:
      containers:
      - name: ui
        image: gcr.io/your-project/ui:1.0.0
        ports:
        - containerPort: 80
        resources:
          requests:
            cpu: 100m
            memory: 128Mi
          limits:
            cpu: 250m
            memory: 256Mi
        livenessProbe:
          httpGet:
            path: /
            port: 80
          initialDelaySeconds: 10
          periodSeconds: 30
```

### 5. Create Ingress

```yaml
# ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: otel-ingress
  namespace: mobile-observability-prod
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
spec:
  tls:
  - hosts:
    - gateway.yourcompany.com
    - ui.yourcompany.com
    secretName: gateway-tls-secret
  rules:
  - host: gateway.yourcompany.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: otel-gateway
            port:
              number: 8080
  - host: ui.yourcompany.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: control-plane-ui
            port:
              number: 80
```

### 6. Apply All Resources

```bash
kubectl apply -f gateway-prod.yaml
kubectl apply -f collector-prod.yaml
kubectl apply -f ui-prod.yaml
kubectl apply -f ingress.yaml
```

### 7. Verify Deployment

```bash
# Check all pods
kubectl get pods -n mobile-observability-prod

# Check services
kubectl get svc -n mobile-observability-prod

# Check ingress
kubectl get ingress -n mobile-observability-prod

# Test gateway health
curl https://gateway.yourcompany.com/health

# Test UI
curl https://ui.yourcompany.com
```

## Monitoring & Alerting

### 1. Prometheus ServiceMonitor

```yaml
# servicemonitor.yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: gateway-metrics
  namespace: mobile-observability-prod
spec:
  selector:
    matchLabels:
      app: otel-gateway
  endpoints:
  - port: metrics
    interval: 30s
```

### 2. Grafana Dashboards

**Gateway Dashboard:**

```json
{
  "dashboard": {
    "title": "Mobile Observability Gateway",
    "panels": [
      {
        "title": "Request Rate",
        "targets": [
          {
            "expr": "rate(http_requests_total{job=\"otel-gateway\"}[5m])"
          }
        ]
      },
      {
        "title": "Error Rate",
        "targets": [
          {
            "expr": "rate(http_requests_total{job=\"otel-gateway\",status=~\"5..\"}[5m])"
          }
        ]
      },
      {
        "title": "Latency (p95)",
        "targets": [
          {
            "expr": "histogram_quantile(0.95, rate(http_request_duration_seconds_bucket{job=\"otel-gateway\"}[5m]))"
          }
        ]
      }
    ]
  }
}
```

### 3. Alert Rules

```yaml
# alerts.yaml
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: gateway-alerts
  namespace: mobile-observability-prod
spec:
  groups:
  - name: gateway
    interval: 30s
    rules:
    - alert: HighErrorRate
      expr: |
        rate(http_requests_total{job="otel-gateway",status=~"5.."}[5m]) > 0.05
      for: 5m
      labels:
        severity: warning
      annotations:
        summary: "High error rate detected"
        description: "Error rate is {{ $value | humanizePercentage }} over last 5 minutes"

    - alert: HighLatency
      expr: |
        histogram_quantile(0.95, rate(http_request_duration_seconds_bucket{job="otel-gateway"}[5m])) > 1
      for: 5m
      labels:
        severity: warning
      annotations:
        summary: "High latency detected"
        description: "P95 latency is {{ $value }}s over last 5 minutes"

    - alert: PodDown
      expr: |
        kube_deployment_status_replicas_available{deployment="otel-gateway"} < 2
      for: 5m
      labels:
        severity: critical
      annotations:
        summary: "Gateway pods unavailable"
        description: "Only {{ $value }} pods available"
```

## Scaling

### 1. Horizontal Pod Autoscaling

```yaml
# hpa.yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: gateway-hpa
  namespace: mobile-observability-prod
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: otel-gateway
  minReplicas: 3
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
```

### 2. Vertical Pod Autoscaling

```yaml
# vpa.yaml
apiVersion: autoscaling.k8s.io/v1
kind: VerticalPodAutoscaler
metadata:
  name: gateway-vpa
  namespace: mobile-observability-prod
spec:
  targetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: otel-gateway
  updatePolicy:
    updateMode: "Auto"
```

## Backup & Recovery

### 1. PostgreSQL Backup

```bash
# Daily backup script
#!/bin/bash
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_FILE="gateway-db-$TIMESTAMP.sql"

kubectl exec -n mobile-observability-prod postgres-0 -- \
  pg_dump -U gateway gateway > $BACKUP_FILE

# Upload to cloud storage
gsutil cp $BACKUP_FILE gs://your-backup-bucket/postgres/

# Retain last 30 days
find . -name "gateway-db-*.sql" -mtime +30 -delete
```

### 2. Configuration Backup

```bash
# Backup all configs
kubectl get configmap,secret -n mobile-observability-prod -o yaml > config-backup.yaml

# Backup workflows
curl https://gateway.yourcompany.com/admin/versions > workflows-backup.json
```

### 3. Disaster Recovery

**Recovery steps:**

```bash
# 1. Restore PostgreSQL
kubectl exec -n mobile-observability-prod postgres-0 -- \
  psql -U gateway gateway < gateway-db-backup.sql

# 2. Restore configurations
kubectl apply -f config-backup.yaml

# 3. Redeploy services
kubectl rollout restart deployment/otel-gateway -n mobile-observability-prod
```

## Operational Runbooks

### Runbook: High Error Rate

**Symptoms:**
- Alert: HighErrorRate firing
- 5xx errors > 5% for 5 minutes

**Investigation:**
```bash
# 1. Check gateway logs
kubectl logs -n mobile-observability-prod -l app=otel-gateway --tail=100 | grep ERROR

# 2. Check collector connectivity
kubectl exec -n mobile-observability-prod -it <gateway-pod> -- nc -zv otel-collector 4317

# 3. Check database connectivity
kubectl exec -n mobile-observability-prod -it <gateway-pod> -- nc -zv postgres 5432
```

**Mitigation:**
- If collector down: Restart collector
- If database down: Check database health
- If gateway issue: Rollback deployment

### Runbook: High Latency

**Symptoms:**
- Alert: HighLatency firing
- P95 latency > 1s for 5 minutes

**Investigation:**
```bash
# 1. Check resource usage
kubectl top pods -n mobile-observability-prod

# 2. Check database queries
kubectl logs -n mobile-observability-prod -l app=otel-gateway | grep "slow query"

# 3. Check collector backpressure
kubectl logs -n mobile-observability-prod -l app=otel-collector | grep "queue full"
```

**Mitigation:**
- Scale up gateway replicas
- Optimize database queries
- Scale up collector

### Runbook: Pod Crash Loop

**Symptoms:**
- Pod status: CrashLoopBackOff
- Frequent restarts

**Investigation:**
```bash
# 1. Check pod logs
kubectl logs -n mobile-observability-prod <pod-name> --previous

# 2. Describe pod
kubectl describe pod -n mobile-observability-prod <pod-name>

# 3. Check events
kubectl get events -n mobile-observability-prod --sort-by='.lastTimestamp'
```

**Mitigation:**
- Fix configuration issue
- Increase resource limits
- Rollback to previous version

## Incident Response

### Severity Levels

| Level | Response Time | Description |
|-------|---------------|-------------|
| P0 - Critical | 15 min | Complete service outage |
| P1 - High | 1 hour | Partial outage, high error rate |
| P2 - Medium | 4 hours | Degraded performance |
| P3 - Low | Next business day | Minor issues |

### Incident Response Process

1. **Detection**: Alert fires or manual report
2. **Acknowledgment**: On-call engineer acknowledges (< 15 min for P0)
3. **Assessment**: Determine severity and impact
4. **Mitigation**: Apply temporary fixes
5. **Resolution**: Permanent fix deployed
6. **Post-Mortem**: Document lessons learned

### Communication Template

```
**INCIDENT #123**
Status: [Investigating | Identified | Monitoring | Resolved]
Severity: P0
Start Time: 2024-01-20 14:00 UTC
Impact: Mobile event ingestion failing for 10% of devices

Timeline:
- 14:00 - Alert fired: HighErrorRate
- 14:05 - On-call acknowledged
- 14:10 - Root cause identified: Collector disk full
- 14:15 - Mitigation: Increased disk size
- 14:20 - Services restored
- 14:30 - Monitoring for recurrence

Next Steps:
- Implement disk usage monitoring
- Auto-scaling for collector storage
- Post-mortem scheduled for tomorrow
```

## Related Documentation

- [Developer Guide](DEVELOPER_GUIDE.md) - Extending the system
- [API Reference](API_REFERENCE.md) - API documentation
- [Troubleshooting Guide](TROUBLESHOOTING_GUIDE.md) - Common issues

---

**Last Updated:** 2024-01-20
**Document Owner:** DevOps Team
