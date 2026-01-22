# Management & Visualization Application Architecture

**Purpose**: Web-based platform for managing OpenTelemetry collector configurations and visualizing/managing mobile device workflows

**Created**: January 21, 2026
**Status**: Architecture Design

---

## Overview

The Management Application is a web-based dashboard that enables administrators to:

1. **Device Fleet Management**
   - Register and organize mobile devices
   - Group devices by environment, region, app version
   - Monitor device health and configuration status

2. **Configuration Management**
   - Push OTEL collector endpoint updates
   - Manage authentication tokens and datasets
   - Configure buffer sizes, retry policies
   - Update environment variables and feature flags

3. **Workflow/Policy Management**
   - Create and edit export policies visually
   - Design conditional workflows (if-then rules)
   - Test policies before deployment
   - Version and rollback policies

4. **Visualization & Monitoring**
   - View active workflows across device fleet
   - Monitor policy execution metrics
   - Visualize data flow from devices to collectors
   - Track configuration rollout status

---

## System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Web Browser                               │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  React Frontend (SPA)                                     │  │
│  │  - Device Dashboard                                       │  │
│  │  - Configuration Editor                                   │  │
│  │  - Workflow Visual Designer                               │  │
│  │  - Monitoring & Analytics                                 │  │
│  └────────────────┬─────────────────────────────────────────┘  │
└───────────────────┼─────────────────────────────────────────────┘
                    │ REST API (HTTPS)
                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Backend API Server                            │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │  Node.js/Express (or Go/Python)                          │  │
│  │  - Authentication & Authorization (JWT)                  │  │
│  │  - Device Management API                                 │  │
│  │  - Configuration API                                     │  │
│  │  - Policy/Workflow API                                   │  │
│  │  - Telemetry Ingestion API                               │  │
│  └────────────────┬─────────────────────────────────────────┘  │
└───────────────────┼─────────────────────────────────────────────┘
                    │
        ┌───────────┼───────────┐
        │           │           │
        ▼           ▼           ▼
┌──────────────┐ ┌──────────┐ ┌──────────────┐
│  PostgreSQL  │ │  Redis   │ │  TimescaleDB │
│  (Config DB) │ │  (Cache) │ │  (Metrics)   │
└──────────────┘ └──────────┘ └──────────────┘

                    ▲
                    │ HTTPS (Config Fetch)
                    │
┌─────────────────────────────────────────────────────────────────┐
│                    Mobile Device Fleet                           │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐         │
│  │  Device A    │  │  Device B    │  │  Device N    │         │
│  │  (Polling)   │  │  (Polling)   │  │  (Polling)   │         │
│  └──────────────┘  └──────────────┘  └──────────────┘         │
└─────────────────────────────────────────────────────────────────┘
```

---

## Technology Stack

### Frontend

**Framework**: React 18+ with TypeScript
- **UI Library**: Material-UI (MUI) or Ant Design
- **State Management**: Redux Toolkit or Zustand
- **Data Fetching**: React Query (TanStack Query)
- **Routing**: React Router v6
- **Visualization**:
  - D3.js for workflow visualization
  - Recharts for analytics charts
  - React Flow for policy graph builder

**Build Tools**:
- Vite for fast development
- TypeScript for type safety
- ESLint + Prettier for code quality

### Backend

**Option 1: Node.js** (Recommended for JavaScript ecosystem consistency)
- **Framework**: Express.js or Fastify
- **Language**: TypeScript
- **ORM**: Prisma or TypeORM
- **Authentication**: Passport.js + JWT
- **Validation**: Zod or Joi

**Option 2: Go** (Better performance, suitable for high-scale)
- **Framework**: Gin or Echo
- **ORM**: GORM
- **Authentication**: golang-jwt

**Option 3: Python** (Fast prototyping, good for data processing)
- **Framework**: FastAPI
- **ORM**: SQLAlchemy
- **Authentication**: PyJWT

### Database

**Primary Database**: PostgreSQL 15+
- Device registry
- Configuration versions
- Policy definitions
- User accounts and permissions
- Audit logs

**Cache Layer**: Redis 7+
- Configuration caching
- Session storage
- Rate limiting
- Real-time device status

**Time-Series Database**: TimescaleDB (PostgreSQL extension)
- Configuration fetch metrics
- Policy execution statistics
- Device health metrics
- Rollout progress tracking

### Deployment

**Containerization**: Docker + Docker Compose
**Orchestration**: Kubernetes (optional for production scale)
**Reverse Proxy**: Nginx or Traefik
**SSL/TLS**: Let's Encrypt certificates
**Monitoring**: Prometheus + Grafana

---

## Database Schema

### Devices Table

```sql
CREATE TABLE devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id VARCHAR(255) UNIQUE NOT NULL,
    device_token VARCHAR(512) NOT NULL,
    device_group VARCHAR(255) NOT NULL,
    os_version VARCHAR(50),
    app_version VARCHAR(50),
    registered_at TIMESTAMP NOT NULL DEFAULT NOW(),
    last_seen TIMESTAMP,
    last_config_fetch TIMESTAMP,
    current_config_version VARCHAR(50),
    config_applied_successfully BOOLEAN DEFAULT TRUE,
    metadata JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_devices_group ON devices(device_group);
CREATE INDEX idx_devices_last_seen ON devices(last_seen);
```

### Device Groups Table

```sql
CREATE TABLE device_groups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) UNIQUE NOT NULL,
    description TEXT,
    environment VARCHAR(50), -- dev, staging, production
    rollout_strategy VARCHAR(50), -- immediate, gradual, canary
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
```

### Configurations Table

```sql
CREATE TABLE configurations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    version VARCHAR(50) UNIQUE NOT NULL,
    device_group_id UUID REFERENCES device_groups(id),
    otel_config JSONB NOT NULL,
    environment_vars JSONB,
    polling_config JSONB,
    feature_flags JSONB,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deployed_at TIMESTAMP,
    is_active BOOLEAN DEFAULT FALSE
);

CREATE INDEX idx_configs_group ON configurations(device_group_id);
CREATE INDEX idx_configs_version ON configurations(version);
```

### Export Policies Table

```sql
CREATE TABLE export_policies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id VARCHAR(255) UNIQUE NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    enabled BOOLEAN DEFAULT TRUE,
    priority INTEGER NOT NULL,
    match_conditions JSONB NOT NULL,
    actions JSONB NOT NULL,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    deployed_at TIMESTAMP
);

CREATE INDEX idx_policies_priority ON export_policies(priority DESC);
```

### Policy Deployments Table (Many-to-Many)

```sql
CREATE TABLE policy_deployments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id UUID REFERENCES export_policies(id),
    device_group_id UUID REFERENCES device_groups(id),
    deployed_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(policy_id, device_group_id)
);
```

### Configuration Audit Log

```sql
CREATE TABLE config_audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    action VARCHAR(50) NOT NULL, -- create, update, deploy, rollback
    entity_type VARCHAR(50) NOT NULL, -- config, policy, device_group
    entity_id UUID NOT NULL,
    performed_by UUID REFERENCES users(id),
    changes JSONB,
    timestamp TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_timestamp ON config_audit_log(timestamp DESC);
CREATE INDEX idx_audit_entity ON config_audit_log(entity_type, entity_id);
```

### Device Metrics Table (TimescaleDB Hypertable)

```sql
CREATE TABLE device_metrics (
    time TIMESTAMPTZ NOT NULL,
    device_id VARCHAR(255) NOT NULL,
    metric_name VARCHAR(255) NOT NULL,
    metric_value DOUBLE PRECISION,
    tags JSONB
);

-- Convert to hypertable for time-series optimization
SELECT create_hypertable('device_metrics', 'time');

CREATE INDEX idx_device_metrics_device ON device_metrics(device_id, time DESC);
CREATE INDEX idx_device_metrics_name ON device_metrics(metric_name, time DESC);
```

### Users Table

```sql
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(255),
    role VARCHAR(50) NOT NULL, -- admin, operator, viewer
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    last_login TIMESTAMP
);
```

---

## REST API Specification

### Device Management API

#### Register Device
```http
POST /api/v1/devices/register
Content-Type: application/json

{
  "device_id": "device-12345",
  "os_version": "Android 14",
  "app_version": "2.1.0",
  "device_group": "production-mobile"
}

Response 201:
{
  "device_token": "token_ABC123",
  "config_url": "https://mgmt.example.com/api/v1/config/device-12345",
  "polling_interval": 300
}
```

#### List Devices
```http
GET /api/v1/devices?group=production-mobile&limit=50&offset=0
Authorization: Bearer {admin_token}

Response 200:
{
  "devices": [
    {
      "id": "uuid",
      "device_id": "device-12345",
      "device_group": "production-mobile",
      "os_version": "Android 14",
      "app_version": "2.1.0",
      "last_seen": "2026-01-21T10:30:00Z",
      "current_config_version": "1.2.3",
      "config_applied_successfully": true
    }
  ],
  "total": 150,
  "limit": 50,
  "offset": 0
}
```

#### Get Device Details
```http
GET /api/v1/devices/{device_id}
Authorization: Bearer {admin_token}

Response 200:
{
  "id": "uuid",
  "device_id": "device-12345",
  "device_group": "production-mobile",
  "current_config_version": "1.2.3",
  "active_policies": ["policy-1", "policy-2"],
  "metrics": {
    "config_fetch_success_rate": 0.99,
    "last_export_at": "2026-01-21T10:35:00Z"
  }
}
```

### Configuration Management API

#### Create Configuration
```http
POST /api/v1/configs
Authorization: Bearer {admin_token}
Content-Type: application/json

{
  "device_group": "production-mobile",
  "otel_config": {
    "protocol": "grpc",
    "collector_endpoint": "https://ingress.dash0.com:4317",
    "auth_token": "auth_ABC123",
    "dataset": "production-mobile",
    "ram_buffer_size": 5000
  },
  "environment_vars": {
    "FEATURE_FLAG_NEW_UI": "true"
  },
  "polling_config": {
    "interval_seconds": 300
  }
}

Response 201:
{
  "version": "1.2.4",
  "affected_devices": 150,
  "config_url": "/api/v1/configs/1.2.4"
}
```

#### Get Configuration for Device
```http
GET /api/v1/config/{device_id}
Authorization: Bearer {device_token}
If-None-Match: "1.2.3"

Response 200 (if new version available):
{
  "version": "1.2.4",
  "updated_at": "2026-01-21T11:00:00Z",
  "otel_config": { ... },
  "environment_vars": { ... },
  "export_policies": [ ... ],
  "polling_config": { ... }
}

Response 304 (if unchanged):
(Empty body)
```

#### Deploy Configuration
```http
POST /api/v1/configs/{version}/deploy
Authorization: Bearer {admin_token}

{
  "device_groups": ["production-mobile"],
  "rollout_strategy": "gradual",
  "rollout_percentage": 10
}

Response 200:
{
  "deployment_id": "uuid",
  "status": "in_progress",
  "deployed_to": 15,
  "total_devices": 150
}
```

#### Rollback Configuration
```http
POST /api/v1/configs/rollback
Authorization: Bearer {admin_token}

{
  "device_group": "production-mobile",
  "target_version": "1.2.3"
}

Response 200:
{
  "rolled_back_to": "1.2.3",
  "affected_devices": 150
}
```

### Policy/Workflow Management API

#### Create Policy
```http
POST /api/v1/policies
Authorization: Bearer {admin_token}
Content-Type: application/json

{
  "policy_id": "high-memory-policy",
  "name": "High Memory Usage Alert",
  "description": "Flush immediately when memory exceeds 500MB",
  "enabled": true,
  "priority": 120,
  "match": {
    "attributes": {
      "event.name": {"equals": "memory.high"},
      "memory_mb": {"gt": 500}
    }
  },
  "actions": [
    {
      "type": "flush_immediate"
    },
    {
      "type": "increase_sampling",
      "parameters": {
        "rate": 1.0,
        "duration_minutes": 10
      }
    }
  ],
  "device_groups": ["production-mobile"]
}

Response 201:
{
  "policy_id": "high-memory-policy",
  "version": "1.0.0",
  "deployed_to_groups": ["production-mobile"],
  "affected_devices": 150
}
```

#### List Policies
```http
GET /api/v1/policies?device_group=production-mobile
Authorization: Bearer {admin_token}

Response 200:
{
  "policies": [
    {
      "policy_id": "high-memory-policy",
      "name": "High Memory Usage Alert",
      "enabled": true,
      "priority": 120,
      "device_groups": ["production-mobile"],
      "execution_count": 450,
      "last_triggered": "2026-01-21T10:30:00Z"
    }
  ]
}
```

#### Update Policy
```http
PUT /api/v1/policies/{policy_id}
Authorization: Bearer {admin_token}

{
  "enabled": false,
  "priority": 100
}

Response 200:
{
  "policy_id": "high-memory-policy",
  "version": "1.0.1",
  "changes_applied": true
}
```

#### Deploy Policy
```http
POST /api/v1/policies/{policy_id}/deploy
Authorization: Bearer {admin_token}

{
  "device_groups": ["production-mobile", "staging-mobile"]
}

Response 200:
{
  "deployed_to": ["production-mobile", "staging-mobile"],
  "affected_devices": 200
}
```

### Monitoring & Analytics API

#### Get Fleet Status
```http
GET /api/v1/monitoring/fleet-status
Authorization: Bearer {admin_token}

Response 200:
{
  "total_devices": 500,
  "online_devices": 485,
  "offline_devices": 15,
  "devices_by_group": {
    "production-mobile": 300,
    "staging-mobile": 150,
    "dev-mobile": 50
  },
  "config_compliance": {
    "up_to_date": 480,
    "outdated": 5,
    "failed": 0
  }
}
```

#### Get Policy Execution Metrics
```http
GET /api/v1/monitoring/policies/metrics?policy_id=high-memory-policy&from=2026-01-20T00:00:00Z&to=2026-01-21T23:59:59Z
Authorization: Bearer {admin_token}

Response 200:
{
  "policy_id": "high-memory-policy",
  "execution_count": 450,
  "success_rate": 0.98,
  "avg_execution_time_ms": 12,
  "triggered_by_devices": 120,
  "time_series": [
    {
      "timestamp": "2026-01-21T00:00:00Z",
      "executions": 18
    }
  ]
}
```

#### Get Configuration Rollout Status
```http
GET /api/v1/monitoring/rollouts/{deployment_id}
Authorization: Bearer {admin_token}

Response 200:
{
  "deployment_id": "uuid",
  "config_version": "1.2.4",
  "device_group": "production-mobile",
  "status": "in_progress",
  "progress": {
    "total_devices": 150,
    "deployed": 45,
    "pending": 105,
    "failed": 0
  },
  "started_at": "2026-01-21T10:00:00Z",
  "estimated_completion": "2026-01-21T12:00:00Z"
}
```

---

## Frontend UI Components

### 1. Dashboard (Home Page)

**Widgets**:
- Fleet health overview (total devices, online/offline)
- Recent configuration changes
- Active policy summary
- Recent alerts/errors
- Quick actions (deploy config, create policy)

### 2. Device Management

**Features**:
- Device list with filters (group, status, version)
- Device detail view with configuration history
- Bulk actions (assign group, force refresh config)
- Device search
- Export device list (CSV)

### 3. Configuration Editor

**Features**:
- Visual form for OTEL config parameters
- Protocol selector (gRPC/HTTP)
- Authentication settings
- Buffer configuration
- Environment variables (key-value pairs)
- JSON preview
- Validate configuration
- Deploy with rollout strategy selection

### 4. Policy/Workflow Designer

**Visual Editor Components**:
- Drag-and-drop policy builder
- Match condition builder (attribute filters)
- Action builder (flush, sampling, etc.)
- Priority ordering
- Device group assignment
- Policy testing/simulation
- Visual workflow graph

**Example Visual Flow**:
```
[Trigger: memory.high > 500MB]
    │
    ├─► [Action: Flush Immediate]
    │
    └─► [Action: Increase Sampling to 100% for 10min]
```

### 5. Monitoring & Analytics

**Dashboards**:
- Configuration compliance dashboard
- Policy execution metrics
- Device health trends
- Export success rates
- Rollout progress visualization

**Charts**:
- Time-series graphs (policy triggers over time)
- Pie charts (device distribution by group)
- Bar charts (config version adoption)
- Heatmaps (device activity by time of day)

---

## Authentication & Authorization

### User Roles

**Admin**:
- Full access to all features
- Create/edit/delete configurations and policies
- Manage users and device groups
- Deploy configurations

**Operator**:
- View all data
- Create and edit policies (requires approval)
- Deploy to non-production groups

**Viewer**:
- Read-only access
- View dashboards and reports
- Export data

### Authentication Flow

```
1. User Login (POST /api/v1/auth/login)
   └─► Email + Password
   └─► Returns JWT token (expires in 1 hour)

2. Token Refresh (POST /api/v1/auth/refresh)
   └─► Refresh token (expires in 7 days)
   └─► Returns new JWT token

3. Logout (POST /api/v1/auth/logout)
   └─► Invalidates refresh token
```

### JWT Payload
```json
{
  "user_id": "uuid",
  "email": "admin@example.com",
  "role": "admin",
  "iat": 1234567890,
  "exp": 1234571490
}
```

---

## Deployment Architecture

### Docker Compose (Development)

```yaml
version: '3.8'

services:
  frontend:
    build: ./frontend
    ports:
      - "3000:3000"
    environment:
      - REACT_APP_API_URL=http://localhost:8000
    depends_on:
      - backend

  backend:
    build: ./backend
    ports:
      - "8000:8000"
    environment:
      - DATABASE_URL=postgresql://user:pass@postgres:5432/otel_mgmt
      - REDIS_URL=redis://redis:6379
      - JWT_SECRET=${JWT_SECRET}
    depends_on:
      - postgres
      - redis

  postgres:
    image: timescale/timescaledb:latest-pg15
    ports:
      - "5432:5432"
    environment:
      - POSTGRES_USER=user
      - POSTGRES_PASSWORD=pass
      - POSTGRES_DB=otel_mgmt
    volumes:
      - postgres_data:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data

  nginx:
    image: nginx:alpine
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf
      - ./ssl:/etc/nginx/ssl
    depends_on:
      - frontend
      - backend

volumes:
  postgres_data:
  redis_data:
```

### Kubernetes (Production)

**Components**:
- Frontend: 3 replicas (React SPA served by nginx)
- Backend: 5 replicas (API server with auto-scaling)
- PostgreSQL: StatefulSet with persistent volume
- Redis: StatefulSet with persistent volume
- Ingress: nginx-ingress with TLS termination

---

## Security Considerations

### API Security
- All endpoints use HTTPS (TLS 1.3)
- JWT authentication with short expiry
- Rate limiting (100 requests/minute per user)
- CORS configuration (whitelist frontend origin)
- Input validation and sanitization
- SQL injection prevention (parameterized queries)

### Device Security
- Device tokens separate from admin tokens
- Device tokens scoped to read config only
- Token rotation support
- Configuration signing (optional)

### Data Security
- Passwords hashed with bcrypt (cost factor 12)
- Sensitive config values encrypted at rest
- Audit logging of all changes
- RBAC for all operations

---

## Next Steps

1. **Choose Tech Stack**
   - Decide on backend language (Node.js/Go/Python)
   - Select frontend UI library (MUI/Ant Design)

2. **Initialize Project Structure**
   - Create monorepo or separate repos
   - Set up Docker Compose for local development

3. **Implement Backend**
   - Set up database with migrations
   - Create REST API endpoints
   - Implement authentication

4. **Implement Frontend**
   - Create React app with routing
   - Build dashboard and device list
   - Implement configuration editor

5. **Integrate with Mobile App**
   - Test config fetch from mobile devices
   - Verify policy deployment end-to-end

---

**Status**: ✅ Architecture Complete - Ready for Implementation
**Next**: Choose tech stack and initialize project

