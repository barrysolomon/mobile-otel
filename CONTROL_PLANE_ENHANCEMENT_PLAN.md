# Control Plane UI Enhancement Plan

**Purpose**: Enhance the existing control-plane-ui with device fleet management and configuration capabilities
**Created**: January 21, 2026
**Status**: Planning

---

## Current State Analysis

### What control-plane-ui Already Has ✅

1. **Visual Workflow Builder**
   - React Flow-based drag-and-drop editor
   - Node types: Event Match, HTTP Error, Crash Marker, Logic (ANY/ALL), Actions (Flush, Annotate, Set Sampling)
   - Graph to DSL compiler
   - Workflow validation

2. **Workflow Publishing**
   - Publish workflows to gateway
   - Version management
   - Rollback capability

3. **Device Monitoring (Basic)**
   - Device list view (currently mock data)
   - Status indicators
   - Heartbeat visualization

4. **Tech Stack**
   - React 18 + TypeScript
   - Vite build tool
   - React Flow 11
   - Axios for API calls
   - Gateway integration at `/api/*`

### What's Missing 🔧

Based on the mobile app's new capabilities and remote management architecture, the control-plane-ui needs:

1. **Device Fleet Management**
   - Real device registration and tracking
   - Device grouping (prod, staging, dev)
   - Device filtering and search
   - Device configuration status

2. **Configuration Management**
   - OTEL collector endpoint management
   - Auth token and dataset management
   - Protocol selection (gRPC/HTTP)
   - Buffer configuration
   - Environment variables
   - Feature flags

3. **Real-Time Device Polling**
   - Replace mock device data with actual heartbeats
   - Live device status updates
   - Configuration compliance tracking

4. **Authentication & Authorization**
   - User login system
   - Role-based access (admin, operator, viewer)
   - JWT token management

5. **Configuration Deployment**
   - Push config updates to device groups
   - Gradual rollout support
   - Configuration versioning
   - Rollback configurations

6. **Analytics Dashboard**
   - Device health metrics
   - Policy execution statistics
   - Export success rates

---

## Enhancement Phases

### Phase 1: Device Fleet Management (Priority: HIGH)

**Goal**: Connect to real devices and manage device registration/grouping

#### 1.1 Backend Gateway Enhancements

Add to gateway (Go service):

**New Endpoints**:
```go
// Device registration
POST /api/v1/devices/register
{
  "device_id": "device-123",
  "os_version": "Android 14",
  "app_version": "2.1.0",
  "device_group": "production-mobile"
}

// List devices
GET /api/v1/devices?group=production&limit=50

// Get device details
GET /api/v1/devices/{device_id}

// Update device group
PATCH /api/v1/devices/{device_id}/group
{
  "device_group": "staging-mobile"
}
```

**Database Schema** (add to gateway's storage):
```sql
CREATE TABLE devices (
    device_id VARCHAR(255) PRIMARY KEY,
    device_token VARCHAR(512) NOT NULL,
    device_group VARCHAR(255) NOT NULL,
    os_version VARCHAR(50),
    app_version VARCHAR(50),
    registered_at TIMESTAMP NOT NULL,
    last_seen TIMESTAMP,
    last_config_fetch TIMESTAMP,
    current_config_version VARCHAR(50),
    metadata JSONB
);

CREATE TABLE device_groups (
    name VARCHAR(255) PRIMARY KEY,
    description TEXT,
    environment VARCHAR(50),
    created_at TIMESTAMP NOT NULL
);
```

#### 1.2 Frontend Enhancements

**New Component**: `src/components/DeviceFleet.tsx`

```typescript
import React, { useEffect, useState } from 'react';
import { gatewayAPI } from '../api/gateway';

interface Device {
  device_id: string;
  device_group: string;
  os_version: string;
  app_version: string;
  last_seen: string;
  current_config_version: string;
}

export const DeviceFleet: React.FC = () => {
  const [devices, setDevices] = useState<Device[]>([]);
  const [selectedGroup, setSelectedGroup] = useState<string>('all');
  const [searchTerm, setSearchTerm] = useState('');

  useEffect(() => {
    const fetchDevices = async () => {
      const result = await gatewayAPI.listDevices({ group: selectedGroup });
      setDevices(result.devices);
    };
    fetchDevices();
    const interval = setInterval(fetchDevices, 30000); // Poll every 30s
    return () => clearInterval(interval);
  }, [selectedGroup]);

  const filteredDevices = devices.filter(d =>
    d.device_id.toLowerCase().includes(searchTerm.toLowerCase())
  );

  return (
    <div className="device-fleet">
      <div className="controls">
        <input
          type="text"
          placeholder="Search devices..."
          value={searchTerm}
          onChange={(e) => setSearchTerm(e.target.value)}
        />
        <select value={selectedGroup} onChange={(e) => setSelectedGroup(e.target.value)}>
          <option value="all">All Groups</option>
          <option value="production-mobile">Production</option>
          <option value="staging-mobile">Staging</option>
          <option value="dev-mobile">Development</option>
        </select>
      </div>

      <table className="device-table">
        <thead>
          <tr>
            <th>Device ID</th>
            <th>Group</th>
            <th>OS Version</th>
            <th>App Version</th>
            <th>Config Version</th>
            <th>Last Seen</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {filteredDevices.map(device => (
            <tr key={device.device_id}>
              <td>{device.device_id}</td>
              <td><span className={`badge ${device.device_group}`}>{device.device_group}</span></td>
              <td>{device.os_version}</td>
              <td>{device.app_version}</td>
              <td>{device.current_config_version}</td>
              <td>{formatLastSeen(device.last_seen)}</td>
              <td>{renderStatusIndicator(device.last_seen)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

function formatLastSeen(timestamp: string): string {
  const date = new Date(timestamp);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffMins = Math.floor(diffMs / 60000);

  if (diffMins < 1) return 'Just now';
  if (diffMins < 60) return `${diffMins}m ago`;
  const diffHours = Math.floor(diffMins / 60);
  if (diffHours < 24) return `${diffHours}h ago`;
  return date.toLocaleDateString();
}

function renderStatusIndicator(lastSeen: string): JSX.Element {
  const diffMins = Math.floor((Date.now() - new Date(lastSeen).getTime()) / 60000);

  if (diffMins < 1) return <span className="status-dot green">🟢 Online</span>;
  if (diffMins < 5) return <span className="status-dot yellow">🟡 Active</span>;
  return <span className="status-dot red">🔴 Offline</span>;
}
```

**Update**: `src/api/gateway.ts`

```typescript
export const gatewayAPI = {
  // Existing methods...

  // Device management
  listDevices: async (params?: { group?: string; limit?: number }) => {
    const response = await axios.get('/api/v1/devices', { params });
    return response.data;
  },

  getDevice: async (deviceId: string) => {
    const response = await axios.get(`/api/v1/devices/${deviceId}`);
    return response.data;
  },

  updateDeviceGroup: async (deviceId: string, group: string) => {
    const response = await axios.patch(`/api/v1/devices/${deviceId}/group`, { device_group: group });
    return response.data;
  }
};
```

**Add Tab**: Update `src/App.tsx`

```typescript
import { DeviceFleet } from './components/DeviceFleet';

function App() {
  const [activeTab, setActiveTab] = useState('workflows');

  return (
    <div className="app">
      <nav className="tabs">
        <button onClick={() => setActiveTab('workflows')}>Workflows</button>
        <button onClick={() => setActiveTab('devices')}>Devices</button>
        <button onClick={() => setActiveTab('config')}>Configuration</button>
        <button onClick={() => setActiveTab('monitor')}>Monitor</button>
      </nav>

      {activeTab === 'workflows' && <WorkflowBuilder />}
      {activeTab === 'devices' && <DeviceFleet />}
      {activeTab === 'config' && <ConfigManager />}
      {activeTab === 'monitor' && <DeviceMonitor />}
    </div>
  );
}
```

---

### Phase 2: Configuration Management (Priority: HIGH)

**Goal**: Allow admins to manage OTEL collector configuration and push updates to devices

#### 2.1 Backend Gateway Enhancements

**New Endpoints**:
```go
// Create/update configuration
POST /api/v1/configs
{
  "device_group": "production-mobile",
  "otel_config": {
    "protocol": "grpc",
    "collector_endpoint": "https://ingress.dash0.com:4317",
    "auth_token": "auth_ABC123",
    "dataset": "production-mobile",
    "ram_buffer_size": 5000,
    "disk_buffer_mb": 50
  },
  "environment_vars": {
    "FEATURE_FLAG_NEW_UI": "true"
  },
  "feature_flags": {
    "enable_crash_detection": true
  }
}

// Get configuration for device (already exists, enhance it)
GET /config?app_id=X&device_id=Y

// List configuration versions
GET /api/v1/configs?device_group=production

// Deploy configuration
POST /api/v1/configs/{version}/deploy
{
  "device_groups": ["production-mobile"],
  "rollout_strategy": "gradual"
}
```

**Database Schema**:
```sql
CREATE TABLE configurations (
    version VARCHAR(50) PRIMARY KEY,
    device_group VARCHAR(255) NOT NULL,
    otel_config JSONB NOT NULL,
    environment_vars JSONB,
    feature_flags JSONB,
    created_at TIMESTAMP NOT NULL,
    is_active BOOLEAN DEFAULT FALSE
);

CREATE TABLE config_deployments (
    id SERIAL PRIMARY KEY,
    config_version VARCHAR(50) REFERENCES configurations(version),
    device_id VARCHAR(255) REFERENCES devices(device_id),
    deployed_at TIMESTAMP,
    applied_at TIMESTAMP,
    success BOOLEAN
);
```

#### 2.2 Frontend Component

**New Component**: `src/components/ConfigManager.tsx`

```typescript
import React, { useState } from 'react';
import { gatewayAPI } from '../api/gateway';

interface OTELConfig {
  protocol: 'grpc' | 'http';
  collector_endpoint: string;
  auth_token: string;
  dataset: string;
  ram_buffer_size: number;
  disk_buffer_mb: number;
  disk_buffer_ttl_hours: number;
  export_timeout_seconds: number;
  max_export_retries: number;
}

export const ConfigManager: React.FC = () => {
  const [deviceGroup, setDeviceGroup] = useState('production-mobile');
  const [otelConfig, setOtelConfig] = useState<OTELConfig>({
    protocol: 'grpc',
    collector_endpoint: 'https://ingress.us-west-2.aws.dash0.com:4317',
    auth_token: '',
    dataset: 'production-mobile',
    ram_buffer_size: 5000,
    disk_buffer_mb: 50,
    disk_buffer_ttl_hours: 24,
    export_timeout_seconds: 30,
    max_export_retries: 3
  });

  const [envVars, setEnvVars] = useState<Record<string, string>>({
    FEATURE_FLAG_NEW_UI: 'true'
  });

  const handleDeploy = async () => {
    try {
      const response = await gatewayAPI.createConfig({
        device_group: deviceGroup,
        otel_config: otelConfig,
        environment_vars: envVars
      });
      alert(`Configuration ${response.version} deployed successfully!`);
    } catch (error) {
      console.error('Deploy failed:', error);
      alert('Failed to deploy configuration');
    }
  };

  return (
    <div className="config-manager">
      <h2>Configuration Management</h2>

      <div className="form-section">
        <label>Device Group</label>
        <select value={deviceGroup} onChange={(e) => setDeviceGroup(e.target.value)}>
          <option value="production-mobile">Production</option>
          <option value="staging-mobile">Staging</option>
          <option value="dev-mobile">Development</option>
        </select>
      </div>

      <div className="form-section">
        <h3>OTEL Configuration</h3>

        <label>Protocol</label>
        <div>
          <label>
            <input
              type="radio"
              value="grpc"
              checked={otelConfig.protocol === 'grpc'}
              onChange={(e) => setOtelConfig({ ...otelConfig, protocol: 'grpc' })}
            />
            gRPC (port 4317)
          </label>
          <label>
            <input
              type="radio"
              value="http"
              checked={otelConfig.protocol === 'http'}
              onChange={(e) => setOtelConfig({ ...otelConfig, protocol: 'http' })}
            />
            HTTP (path /v1/signal)
          </label>
        </div>

        <label>Collector Endpoint</label>
        <input
          type="text"
          value={otelConfig.collector_endpoint}
          onChange={(e) => setOtelConfig({ ...otelConfig, collector_endpoint: e.target.value })}
          placeholder={
            otelConfig.protocol === 'grpc'
              ? 'https://ingress.dash0.com:4317'
              : 'https://ingress.dash0.com/v1/logs'
          }
        />

        <label>Auth Token</label>
        <input
          type="password"
          value={otelConfig.auth_token}
          onChange={(e) => setOtelConfig({ ...otelConfig, auth_token: e.target.value })}
          placeholder="auth_..."
        />

        <label>Dataset</label>
        <input
          type="text"
          value={otelConfig.dataset}
          onChange={(e) => setOtelConfig({ ...otelConfig, dataset: e.target.value })}
          placeholder="production-mobile"
        />

        <label>RAM Buffer Size (events)</label>
        <input
          type="number"
          value={otelConfig.ram_buffer_size}
          onChange={(e) => setOtelConfig({ ...otelConfig, ram_buffer_size: Number(e.target.value) })}
        />

        <label>Disk Buffer (MB)</label>
        <input
          type="number"
          value={otelConfig.disk_buffer_mb}
          onChange={(e) => setOtelConfig({ ...otelConfig, disk_buffer_mb: Number(e.target.value) })}
        />
      </div>

      <div className="form-section">
        <h3>Environment Variables</h3>
        {Object.entries(envVars).map(([key, value]) => (
          <div key={key} className="env-var-row">
            <input type="text" value={key} readOnly />
            <input
              type="text"
              value={value}
              onChange={(e) => setEnvVars({ ...envVars, [key]: e.target.value })}
            />
            <button onClick={() => {
              const newVars = { ...envVars };
              delete newVars[key];
              setEnvVars(newVars);
            }}>Remove</button>
          </div>
        ))}
        <button onClick={() => {
          const key = prompt('Variable name:');
          if (key) setEnvVars({ ...envVars, [key]: '' });
        }}>
          Add Variable
        </button>
      </div>

      <div className="actions">
        <button className="btn-primary" onClick={handleDeploy}>
          Deploy Configuration
        </button>
        <button className="btn-secondary">Preview JSON</button>
      </div>
    </div>
  );
};
```

**Update**: `src/api/gateway.ts`

```typescript
export const gatewayAPI = {
  // ... existing methods

  // Configuration management
  createConfig: async (data: any) => {
    const response = await axios.post('/api/v1/configs', data);
    return response.data;
  },

  listConfigs: async (deviceGroup?: string) => {
    const response = await axios.get('/api/v1/configs', {
      params: { device_group: deviceGroup }
    });
    return response.data;
  },

  deployConfig: async (version: string, data: any) => {
    const response = await axios.post(`/api/v1/configs/${version}/deploy`, data);
    return response.data;
  }
};
```

---

### Phase 3: Real-Time Device Polling (Priority: MEDIUM)

**Goal**: Replace mock device data with real heartbeat polling

#### 3.1 Update DeviceMonitor Component

**File**: `src/components/DeviceMonitor.tsx`

Replace mock data logic with:

```typescript
import React, { useEffect, useState } from 'react';
import { gatewayAPI } from '../api/gateway';

interface DeviceStatus {
  device_id: string;
  session_id: string;
  last_seen: number;
  buffer_usage_mb: number;
  recent_triggers: string[];
  config_version: string;
}

export const DeviceMonitor: React.FC = () => {
  const [devices, setDevices] = useState<DeviceStatus[]>([]);
  const [autoRefresh, setAutoRefresh] = useState(true);

  useEffect(() => {
    if (!autoRefresh) return;

    const fetchHeartbeats = async () => {
      try {
        const result = await gatewayAPI.getHeartbeats();
        setDevices(result.devices);
      } catch (error) {
        console.error('Failed to fetch heartbeats:', error);
      }
    };

    fetchHeartbeats();
    const interval = setInterval(fetchHeartbeats, 30000); // Every 30s
    return () => clearInterval(interval);
  }, [autoRefresh]);

  return (
    <div className="device-monitor">
      <div className="controls">
        <h2>Device Monitor</h2>
        <label>
          <input
            type="checkbox"
            checked={autoRefresh}
            onChange={(e) => setAutoRefresh(e.target.checked)}
          />
          Auto-refresh (30s)
        </label>
      </div>

      <table className="heartbeat-table">
        <thead>
          <tr>
            <th>Device ID</th>
            <th>Session</th>
            <th>Buffer Usage</th>
            <th>Recent Triggers</th>
            <th>Config</th>
            <th>Last Seen</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {devices.map(device => (
            <tr key={device.device_id}>
              <td>{device.device_id}</td>
              <td>{device.session_id.substring(0, 8)}</td>
              <td>{device.buffer_usage_mb.toFixed(1)} MB</td>
              <td>
                {device.recent_triggers.map(t => (
                  <span key={t} className="trigger-badge">{t}</span>
                ))}
              </td>
              <td>{device.config_version}</td>
              <td>{formatTimestamp(device.last_seen)}</td>
              <td>{renderStatus(device.last_seen)}</td>
            </tr>
          ))}
        </tbody>
      </table>

      {devices.length === 0 && (
        <div className="no-devices">
          No devices connected. Start Android app to see heartbeats.
        </div>
      )}
    </div>
  );
};
```

#### 3.2 Backend Gateway Enhancement

**Add Heartbeat Endpoint**:
```go
// POST /api/v1/heartbeat
// Called by mobile devices every 30s
func handleHeartbeat(w http.ResponseWriter, r *http.Request) {
  var hb Heartbeat
  json.NewDecoder(r.Body).Decode(&hb)

  // Store in Redis with TTL
  heartbeatKey := fmt.Sprintf("heartbeat:%s", hb.DeviceID)
  data, _ := json.Marshal(hb)
  rdb.Set(ctx, heartbeatKey, data, 2*time.Minute)

  // Update device last_seen in database
  db.Exec("UPDATE devices SET last_seen = NOW() WHERE device_id = $1", hb.DeviceID)

  w.WriteHeader(http.StatusOK)
}

// GET /api/v1/heartbeats
// Returns all active heartbeats (last 2 minutes)
func listHeartbeats(w http.ResponseWriter, r *http.Request) {
  keys, _ := rdb.Keys(ctx, "heartbeat:*").Result()

  devices := []Heartbeat{}
  for _, key := range keys {
    data, _ := rdb.Get(ctx, key).Result()
    var hb Heartbeat
    json.Unmarshal([]byte(data), &hb)
    devices = append(devices, hb)
  }

  json.NewEncoder(w).Encode(map[string]interface{}{
    "devices": devices,
    "count": len(devices)
  })
}
```

---

### Phase 4: Authentication (Priority: LOW)

**Goal**: Add user login and role-based access control

#### 4.1 Simple JWT Auth

**Backend**: Add auth middleware to gateway

```go
func authMiddleware(next http.Handler) http.Handler {
  return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
    token := r.Header.Get("Authorization")
    if token == "" {
      http.Error(w, "Unauthorized", 401)
      return
    }

    claims, err := validateJWT(token)
    if err != nil {
      http.Error(w, "Invalid token", 401)
      return
    }

    ctx := context.WithValue(r.Context(), "user", claims)
    next.ServeHTTP(w, r.WithContext(ctx))
  })
}
```

**Frontend**: Add login page

```typescript
// src/components/Login.tsx
export const Login: React.FC = () => {
  const [credentials, setCredentials] = useState({ email: '', password: '' });

  const handleLogin = async () => {
    const response = await axios.post('/api/v1/auth/login', credentials);
    localStorage.setItem('auth_token', response.data.token);
    window.location.href = '/';
  };

  return (
    <div className="login-page">
      <h2>Control Plane Login</h2>
      <input
        type="email"
        placeholder="Email"
        value={credentials.email}
        onChange={(e) => setCredentials({ ...credentials, email: e.target.value })}
      />
      <input
        type="password"
        placeholder="Password"
        value={credentials.password}
        onChange={(e) => setCredentials({ ...credentials, password: e.target.value })}
      />
      <button onClick={handleLogin}>Login</button>
    </div>
  );
};
```

---

## Implementation Priority

### Phase 1 (Week 1-2): Device Fleet Management
- [ ] Add device registration endpoint to gateway
- [ ] Create devices table in database
- [ ] Build DeviceFleet component
- [ ] Add Devices tab to UI
- [ ] Test with real Android devices

### Phase 2 (Week 2-3): Configuration Management
- [ ] Add configuration endpoints to gateway
- [ ] Create configurations table
- [ ] Build ConfigManager component
- [ ] Add Configuration tab to UI
- [ ] Test config push to Android devices

### Phase 3 (Week 3-4): Real-Time Polling
- [ ] Add heartbeat endpoint to gateway
- [ ] Store heartbeats in Redis
- [ ] Update DeviceMonitor to poll heartbeats
- [ ] Test real-time updates

### Phase 4 (Week 4-5): Authentication (Optional)
- [ ] Add auth endpoints to gateway
- [ ] Create Login component
- [ ] Add JWT middleware
- [ ] Test role-based access

---

## Testing Checklist

### Device Fleet
- [ ] Register device from Android app
- [ ] See device appear in Devices tab
- [ ] Filter devices by group
- [ ] Search devices by ID
- [ ] Update device group

### Configuration
- [ ] Create configuration for device group
- [ ] Deploy configuration
- [ ] Android app fetches new config
- [ ] Android app applies config successfully
- [ ] Verify config version in device list

### Real-Time Monitoring
- [ ] Start Android app
- [ ] See heartbeat in Device Monitor
- [ ] Status indicator updates (green/yellow/red)
- [ ] Buffer usage shows correctly
- [ ] Recent triggers display

---

## Files to Modify

### Gateway (Go)
- `cmd/gateway/main.go` - Add new routes
- `internal/handlers/device.go` - Device management handlers
- `internal/handlers/config.go` - Configuration handlers
- `internal/handlers/heartbeat.go` - Heartbeat handlers
- `internal/models/device.go` - Device model
- `internal/models/config.go` - Configuration model
- `migrations/` - Add database migrations

### Frontend (TypeScript/React)
- `src/App.tsx` - Add new tabs
- `src/components/DeviceFleet.tsx` - New component
- `src/components/ConfigManager.tsx` - New component
- `src/components/DeviceMonitor.tsx` - Enhance existing
- `src/api/gateway.ts` - Add new API methods
- `src/types/` - Add type definitions
- `src/App.css` - Add styles for new components

---

## Summary

The control-plane-ui already has excellent workflow visualization capabilities. The main enhancements needed are:

1. **Device Fleet Management** - Connect to real devices, track them
2. **Configuration Management** - Push OTEL configs to devices
3. **Real-Time Polling** - Replace mock data with live heartbeats
4. **Authentication** - Secure access (optional for MVP)

These enhancements will transform the control-plane-ui from a workflow designer into a complete fleet management platform!

---

**Status**: Planning Complete
**Next**: Start Phase 1 implementation
**Estimated Time**: 4-5 weeks for all phases

