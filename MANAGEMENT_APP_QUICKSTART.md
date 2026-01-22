# Management Application - Quick Start Guide

**Purpose**: Step-by-step guide to build the management and visualization application
**Created**: January 21, 2026

---

## Project Initialization

### Option 1: Node.js + TypeScript Backend (Recommended)

```bash
# Create project directory
mkdir otel-management-app
cd otel-management-app

# Initialize backend
mkdir backend
cd backend
npm init -y
npm install express cors helmet morgan jsonwebtoken bcrypt pg redis
npm install -D typescript @types/node @types/express @types/jsonwebtoken @types/bcrypt ts-node nodemon
npx tsc --init

# Initialize frontend
cd ..
npm create vite@latest frontend -- --template react-ts
cd frontend
npm install @mui/material @emotion/react @emotion/styled
npm install @tanstack/react-query axios react-router-dom recharts
npm install @reduxjs/toolkit react-redux
```

### Project Structure

```
otel-management-app/
├── backend/
│   ├── src/
│   │   ├── controllers/
│   │   │   ├── deviceController.ts
│   │   │   ├── configController.ts
│   │   │   ├── policyController.ts
│   │   │   └── authController.ts
│   │   ├── models/
│   │   │   ├── Device.ts
│   │   │   ├── Configuration.ts
│   │   │   ├── Policy.ts
│   │   │   └── User.ts
│   │   ├── routes/
│   │   │   ├── deviceRoutes.ts
│   │   │   ├── configRoutes.ts
│   │   │   ├── policyRoutes.ts
│   │   │   └── authRoutes.ts
│   │   ├── middleware/
│   │   │   ├── auth.ts
│   │   │   └── errorHandler.ts
│   │   ├── database/
│   │   │   ├── connection.ts
│   │   │   └── migrations/
│   │   └── server.ts
│   ├── package.json
│   └── tsconfig.json
├── frontend/
│   ├── src/
│   │   ├── components/
│   │   │   ├── Dashboard/
│   │   │   ├── DeviceList/
│   │   │   ├── ConfigEditor/
│   │   │   ├── PolicyDesigner/
│   │   │   └── Monitoring/
│   │   ├── pages/
│   │   │   ├── DashboardPage.tsx
│   │   │   ├── DevicesPage.tsx
│   │   │   ├── ConfigsPage.tsx
│   │   │   └── PoliciesPage.tsx
│   │   ├── api/
│   │   │   └── client.ts
│   │   ├── store/
│   │   │   └── index.ts
│   │   ├── App.tsx
│   │   └── main.tsx
│   └── package.json
├── docker-compose.yml
└── README.md
```

---

## Phase 1: Database Setup

### Create Docker Compose

**File**: `docker-compose.yml`

```yaml
version: '3.8'

services:
  postgres:
    image: timescale/timescaledb:latest-pg15
    ports:
      - "5432:5432"
    environment:
      POSTGRES_USER: otel_user
      POSTGRES_PASSWORD: otel_pass
      POSTGRES_DB: otel_management
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ./backend/database/migrations:/docker-entrypoint-initdb.d

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data

  backend:
    build: ./backend
    ports:
      - "8000:8000"
    environment:
      DATABASE_URL: postgresql://otel_user:otel_pass@postgres:5432/otel_management
      REDIS_URL: redis://redis:6379
      JWT_SECRET: your-secret-key-change-in-production
      PORT: 8000
    depends_on:
      - postgres
      - redis
    volumes:
      - ./backend:/app
    command: npm run dev

  frontend:
    build: ./frontend
    ports:
      - "3000:3000"
    environment:
      VITE_API_URL: http://localhost:8000
    volumes:
      - ./frontend:/app
      - /app/node_modules
    depends_on:
      - backend

volumes:
  postgres_data:
  redis_data:
```

### Create Database Migrations

**File**: `backend/database/migrations/001_initial_schema.sql`

```sql
-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Users table
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(255),
    role VARCHAR(50) NOT NULL CHECK (role IN ('admin', 'operator', 'viewer')),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    last_login TIMESTAMP
);

-- Device groups table
CREATE TABLE device_groups (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name VARCHAR(255) UNIQUE NOT NULL,
    description TEXT,
    environment VARCHAR(50),
    rollout_strategy VARCHAR(50) DEFAULT 'immediate',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Devices table
CREATE TABLE devices (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    device_id VARCHAR(255) UNIQUE NOT NULL,
    device_token VARCHAR(512) NOT NULL,
    device_group_id UUID REFERENCES device_groups(id),
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

-- Configurations table
CREATE TABLE configurations (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
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

-- Export policies table
CREATE TABLE export_policies (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    policy_id VARCHAR(255) UNIQUE NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    enabled BOOLEAN DEFAULT TRUE,
    priority INTEGER NOT NULL,
    match_conditions JSONB NOT NULL,
    actions JSONB NOT NULL,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Policy deployments (many-to-many)
CREATE TABLE policy_deployments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    policy_id UUID REFERENCES export_policies(id) ON DELETE CASCADE,
    device_group_id UUID REFERENCES device_groups(id) ON DELETE CASCADE,
    deployed_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(policy_id, device_group_id)
);

-- Audit log
CREATE TABLE config_audit_log (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    action VARCHAR(50) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id UUID NOT NULL,
    performed_by UUID REFERENCES users(id),
    changes JSONB,
    timestamp TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Device metrics (TimescaleDB hypertable)
CREATE TABLE device_metrics (
    time TIMESTAMPTZ NOT NULL,
    device_id VARCHAR(255) NOT NULL,
    metric_name VARCHAR(255) NOT NULL,
    metric_value DOUBLE PRECISION,
    tags JSONB
);

-- Convert to hypertable
SELECT create_hypertable('device_metrics', 'time');

-- Indexes
CREATE INDEX idx_devices_group ON devices(device_group_id);
CREATE INDEX idx_devices_last_seen ON devices(last_seen DESC);
CREATE INDEX idx_configs_group ON configurations(device_group_id);
CREATE INDEX idx_configs_active ON configurations(is_active) WHERE is_active = TRUE;
CREATE INDEX idx_policies_priority ON export_policies(priority DESC);
CREATE INDEX idx_audit_timestamp ON config_audit_log(timestamp DESC);
CREATE INDEX idx_device_metrics_device ON device_metrics(device_id, time DESC);

-- Insert default admin user (password: admin123)
INSERT INTO users (email, password_hash, full_name, role)
VALUES (
    'admin@example.com',
    '$2b$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY5GyYKGq8b3aMC',
    'Admin User',
    'admin'
);

-- Insert default device group
INSERT INTO device_groups (name, description, environment)
VALUES ('default', 'Default device group', 'development');
```

### Start Database

```bash
docker-compose up -d postgres redis
```

---

## Phase 2: Backend Implementation

### Server Setup

**File**: `backend/src/server.ts`

```typescript
import express from 'express';
import cors from 'cors';
import helmet from 'helmet';
import morgan from 'morgan';
import { errorHandler } from './middleware/errorHandler';
import authRoutes from './routes/authRoutes';
import deviceRoutes from './routes/deviceRoutes';
import configRoutes from './routes/configRoutes';
import policyRoutes from './routes/policyRoutes';

const app = express();
const PORT = process.env.PORT || 8000;

// Middleware
app.use(helmet());
app.use(cors({
  origin: process.env.FRONTEND_URL || 'http://localhost:3000',
  credentials: true
}));
app.use(morgan('combined'));
app.use(express.json());

// Routes
app.use('/api/v1/auth', authRoutes);
app.use('/api/v1/devices', deviceRoutes);
app.use('/api/v1/configs', configRoutes);
app.use('/api/v1/policies', policyRoutes);

// Health check
app.get('/health', (req, res) => {
  res.json({ status: 'ok', timestamp: new Date().toISOString() });
});

// Error handling
app.use(errorHandler);

app.listen(PORT, () => {
  console.log(`✅ Backend server running on port ${PORT}`);
});
```

### Authentication Controller

**File**: `backend/src/controllers/authController.ts`

```typescript
import { Request, Response } from 'express';
import bcrypt from 'bcrypt';
import jwt from 'jsonwebtoken';
import { pool } from '../database/connection';

const JWT_SECRET = process.env.JWT_SECRET || 'your-secret-key';

export const login = async (req: Request, res: Response) => {
  try {
    const { email, password } = req.body;

    // Get user from database
    const result = await pool.query(
      'SELECT * FROM users WHERE email = $1',
      [email]
    );

    if (result.rows.length === 0) {
      return res.status(401).json({ error: 'Invalid credentials' });
    }

    const user = result.rows[0];

    // Verify password
    const validPassword = await bcrypt.compare(password, user.password_hash);
    if (!validPassword) {
      return res.status(401).json({ error: 'Invalid credentials' });
    }

    // Generate JWT
    const token = jwt.sign(
      {
        user_id: user.id,
        email: user.email,
        role: user.role
      },
      JWT_SECRET,
      { expiresIn: '1h' }
    );

    // Update last login
    await pool.query(
      'UPDATE users SET last_login = NOW() WHERE id = $1',
      [user.id]
    );

    res.json({
      token,
      user: {
        id: user.id,
        email: user.email,
        full_name: user.full_name,
        role: user.role
      }
    });
  } catch (error) {
    console.error('Login error:', error);
    res.status(500).json({ error: 'Internal server error' });
  }
};
```

### Device Controller

**File**: `backend/src/controllers/deviceController.ts`

```typescript
import { Request, Response } from 'express';
import { pool } from '../database/connection';
import crypto from 'crypto';

export const registerDevice = async (req: Request, res: Response) => {
  try {
    const { device_id, os_version, app_version, device_group } = req.body;

    // Get or create device group
    let groupResult = await pool.query(
      'SELECT id FROM device_groups WHERE name = $1',
      [device_group]
    );

    if (groupResult.rows.length === 0) {
      groupResult = await pool.query(
        'INSERT INTO device_groups (name, environment) VALUES ($1, $2) RETURNING id',
        [device_group, 'production']
      );
    }

    const device_group_id = groupResult.rows[0].id;

    // Generate device token
    const device_token = crypto.randomBytes(32).toString('hex');

    // Insert device
    await pool.query(
      `INSERT INTO devices (device_id, device_token, device_group_id, os_version, app_version)
       VALUES ($1, $2, $3, $4, $5)
       ON CONFLICT (device_id) DO UPDATE
       SET device_token = $2, os_version = $4, app_version = $5, updated_at = NOW()`,
      [device_id, device_token, device_group_id, os_version, app_version]
    );

    res.status(201).json({
      device_token,
      config_url: `${process.env.API_URL || 'http://localhost:8000'}/api/v1/config/${device_id}`,
      polling_interval: 300
    });
  } catch (error) {
    console.error('Register device error:', error);
    res.status(500).json({ error: 'Internal server error' });
  }
};

export const listDevices = async (req: Request, res: Response) => {
  try {
    const { group, limit = 50, offset = 0 } = req.query;

    let query = `
      SELECT d.*, dg.name as group_name
      FROM devices d
      JOIN device_groups dg ON d.device_group_id = dg.id
    `;
    const params: any[] = [];

    if (group) {
      query += ' WHERE dg.name = $1';
      params.push(group);
    }

    query += ' ORDER BY d.last_seen DESC NULLS LAST LIMIT $' + (params.length + 1) + ' OFFSET $' + (params.length + 2);
    params.push(limit, offset);

    const result = await pool.query(query, params);

    res.json({
      devices: result.rows,
      total: result.rowCount,
      limit: Number(limit),
      offset: Number(offset)
    });
  } catch (error) {
    console.error('List devices error:', error);
    res.status(500).json({ error: 'Internal server error' });
  }
};
```

### Configuration Controller

**File**: `backend/src/controllers/configController.ts`

```typescript
import { Request, Response } from 'express';
import { pool } from '../database/connection';

export const getConfigForDevice = async (req: Request, res: Response) => {
  try {
    const { device_id } = req.params;
    const ifNoneMatch = req.headers['if-none-match'];

    // Verify device token
    const deviceToken = req.headers.authorization?.replace('Bearer ', '');
    const deviceResult = await pool.query(
      'SELECT * FROM devices WHERE device_id = $1 AND device_token = $2',
      [device_id, deviceToken]
    );

    if (deviceResult.rows.length === 0) {
      return res.status(401).json({ error: 'Unauthorized' });
    }

    const device = deviceResult.rows[0];

    // Get active configuration for device group
    const configResult = await pool.query(
      `SELECT * FROM configurations
       WHERE device_group_id = $1 AND is_active = TRUE
       ORDER BY created_at DESC LIMIT 1`,
      [device.device_group_id]
    );

    if (configResult.rows.length === 0) {
      return res.status(404).json({ error: 'No configuration found' });
    }

    const config = configResult.rows[0];

    // Check if config version matches (304 Not Modified)
    if (ifNoneMatch === config.version) {
      await pool.query(
        'UPDATE devices SET last_config_fetch = NOW() WHERE id = $1',
        [device.id]
      );
      return res.status(304).send();
    }

    // Get policies for device group
    const policiesResult = await pool.query(
      `SELECT ep.* FROM export_policies ep
       JOIN policy_deployments pd ON ep.id = pd.policy_id
       WHERE pd.device_group_id = $1 AND ep.enabled = TRUE
       ORDER BY ep.priority DESC`,
      [device.device_group_id]
    );

    // Build response
    const response = {
      version: config.version,
      updated_at: config.created_at,
      otel_config: config.otel_config,
      environment_vars: config.environment_vars || {},
      export_policies: policiesResult.rows.map(p => ({
        id: p.policy_id,
        enabled: p.enabled,
        priority: p.priority,
        match: p.match_conditions,
        actions: p.actions
      })),
      polling_config: config.polling_config || { interval_seconds: 300 },
      feature_flags: config.feature_flags || {}
    };

    // Update device last_config_fetch
    await pool.query(
      'UPDATE devices SET last_config_fetch = NOW(), last_seen = NOW() WHERE id = $1',
      [device.id]
    );

    res.setHeader('ETag', config.version);
    res.json(response);
  } catch (error) {
    console.error('Get config error:', error);
    res.status(500).json({ error: 'Internal server error' });
  }
};

export const createConfig = async (req: Request, res: Response) => {
  try {
    const { device_group, otel_config, environment_vars, polling_config, feature_flags } = req.body;
    const user = (req as any).user;

    // Get device group
    const groupResult = await pool.query(
      'SELECT id FROM device_groups WHERE name = $1',
      [device_group]
    );

    if (groupResult.rows.length === 0) {
      return res.status(404).json({ error: 'Device group not found' });
    }

    const device_group_id = groupResult.rows[0].id;

    // Generate version
    const version = `${Date.now()}.0.0`;

    // Insert configuration
    const result = await pool.query(
      `INSERT INTO configurations (version, device_group_id, otel_config, environment_vars, polling_config, feature_flags, created_by, is_active)
       VALUES ($1, $2, $3, $4, $5, $6, $7, TRUE)
       RETURNING *`,
      [version, device_group_id, otel_config, environment_vars, polling_config, feature_flags, user.user_id]
    );

    // Deactivate previous configurations
    await pool.query(
      'UPDATE configurations SET is_active = FALSE WHERE device_group_id = $1 AND version != $2',
      [device_group_id, version]
    );

    // Get device count
    const deviceCountResult = await pool.query(
      'SELECT COUNT(*) FROM devices WHERE device_group_id = $1',
      [device_group_id]
    );

    res.status(201).json({
      version,
      affected_devices: Number(deviceCountResult.rows[0].count),
      config_url: `/api/v1/configs/${version}`
    });
  } catch (error) {
    console.error('Create config error:', error);
    res.status(500).json({ error: 'Internal server error' });
  }
};
```

### Auth Middleware

**File**: `backend/src/middleware/auth.ts`

```typescript
import { Request, Response, NextFunction } from 'express';
import jwt from 'jsonwebtoken';

const JWT_SECRET = process.env.JWT_SECRET || 'your-secret-key';

export const authenticate = (req: Request, res: Response, next: NextFunction) => {
  try {
    const token = req.headers.authorization?.replace('Bearer ', '');

    if (!token) {
      return res.status(401).json({ error: 'No token provided' });
    }

    const decoded = jwt.verify(token, JWT_SECRET);
    (req as any).user = decoded;
    next();
  } catch (error) {
    return res.status(401).json({ error: 'Invalid token' });
  }
};

export const authorize = (...roles: string[]) => {
  return (req: Request, res: Response, next: NextFunction) => {
    const user = (req as any).user;

    if (!roles.includes(user.role)) {
      return res.status(403).json({ error: 'Forbidden' });
    }

    next();
  };
};
```

---

## Phase 3: Frontend Implementation

### API Client

**File**: `frontend/src/api/client.ts`

```typescript
import axios from 'axios';

const API_URL = import.meta.env.VITE_API_URL || 'http://localhost:8000';

const client = axios.create({
  baseURL: `${API_URL}/api/v1`,
  headers: {
    'Content-Type': 'application/json'
  }
});

// Add auth token to requests
client.interceptors.request.use((config) => {
  const token = localStorage.getItem('auth_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

export const authApi = {
  login: (email: string, password: string) =>
    client.post('/auth/login', { email, password })
};

export const devicesApi = {
  list: (params?: any) => client.get('/devices', { params }),
  getById: (deviceId: string) => client.get(`/devices/${deviceId}`)
};

export const configsApi = {
  create: (data: any) => client.post('/configs', data),
  list: (params?: any) => client.get('/configs', { params })
};

export const policiesApi = {
  create: (data: any) => client.post('/policies', data),
  list: (params?: any) => client.get('/policies', { params }),
  update: (policyId: string, data: any) => client.put(`/policies/${policyId}`, data)
};

export default client;
```

### Dashboard Page

**File**: `frontend/src/pages/DashboardPage.tsx`

```typescript
import React from 'react';
import { Box, Grid, Paper, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import client from '../api/client';

export const DashboardPage: React.FC = () => {
  const { data: fleetStatus } = useQuery({
    queryKey: ['fleetStatus'],
    queryFn: () => client.get('/monitoring/fleet-status').then(res => res.data)
  });

  return (
    <Box p={3}>
      <Typography variant="h4" gutterBottom>
        Dashboard
      </Typography>

      <Grid container spacing={3}>
        <Grid item xs={12} md={3}>
          <Paper sx={{ p: 2 }}>
            <Typography variant="h6">Total Devices</Typography>
            <Typography variant="h3">{fleetStatus?.total_devices || 0}</Typography>
          </Paper>
        </Grid>

        <Grid item xs={12} md={3}>
          <Paper sx={{ p: 2 }}>
            <Typography variant="h6">Online</Typography>
            <Typography variant="h3" color="success.main">
              {fleetStatus?.online_devices || 0}
            </Typography>
          </Paper>
        </Grid>

        <Grid item xs={12} md={3}>
          <Paper sx={{ p: 2 }}>
            <Typography variant="h6">Offline</Typography>
            <Typography variant="h3" color="error.main">
              {fleetStatus?.offline_devices || 0}
            </Typography>
          </Paper>
        </Grid>

        <Grid item xs={12} md={3}>
          <Paper sx={{ p: 2 }}>
            <Typography variant="h6">Up to Date</Typography>
            <Typography variant="h3">
              {fleetStatus?.config_compliance?.up_to_date || 0}
            </Typography>
          </Paper>
        </Grid>
      </Grid>
    </Box>
  );
};
```

---

## Running the Application

### Start All Services

```bash
# Start database and Redis
docker-compose up -d postgres redis

# Start backend (in terminal 1)
cd backend
npm run dev

# Start frontend (in terminal 2)
cd frontend
npm run dev
```

### Access Application

- **Frontend**: http://localhost:3000
- **Backend API**: http://localhost:8000
- **Health Check**: http://localhost:8000/health

### Default Login

```
Email: admin@example.com
Password: admin123
```

---

## Next Steps

1. Complete all CRUD operations for devices, configs, policies
2. Implement policy visual designer
3. Add monitoring dashboards with charts
4. Implement rollout strategies
5. Add real-time updates with WebSockets
6. Deploy to production

---

**Status**: Ready for implementation
**Estimated Time**: 2-3 weeks for MVP

