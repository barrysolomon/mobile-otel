# Mobile Observability Control Plane

React + TypeScript web UI for visually creating and managing mobile observability workflows.

## Features

* **Visual Workflow Builder** - React Flow-based drag-and-drop editor
* **Node Types**:
  * Triggers: Event Match, HTTP Error Match, Crash Marker
  * Logic: ANY (OR), ALL (AND)
  * Actions: Flush Window, Annotate Trigger, Set Sampling
* **Graph → DSL Compiler** - Converts visual graphs to executable JSON
* **Workflow Publishing** - Deploy workflows to gateway with versioning
* **Version Management** - Rollback to previous configurations
* **Device Monitoring** - Real-time view of connected devices and their status

## Tech Stack

* **React 18** - UI framework
* **TypeScript** - Type safety
* **Vite** - Build tool
* **React Flow 11** - Flowchart editor
* **Axios** - HTTP client
* **Zustand** - State management (optional, for future use)

## Project Structure

```
control-plane-ui/
├── src/
│   ├── components/
│   │   ├── WorkflowBuilder.tsx        # Main React Flow canvas
│   │   ├── DeviceMonitor.tsx          # Device dashboard
│   │   └── nodes/
│   │       ├── EventMatchNode.tsx     # Trigger node
│   │       ├── FlushWindowNode.tsx    # Action node
│   │       └── LogicNode.tsx          # Logic node (ANY/ALL)
│   ├── types/
│   │   └── workflow.ts                # TypeScript types
│   ├── utils/
│   │   └── graphToDSL.ts              # Graph compiler
│   ├── api/
│   │   └── gateway.ts                 # Gateway API client
│   ├── App.tsx                        # Main app component
│   ├── App.css                        # Styles
│   └── main.tsx                       # Entry point
├── index.html                         # HTML template
├── package.json                       # Dependencies
├── tsconfig.json                      # TypeScript config
├── vite.config.ts                     # Vite config
└── README.md                          # This file
```

## Setup & Development

### Prerequisites

* Node.js 18+ and npm
* Gateway running on `http://localhost:8080`

### Install Dependencies

```bash
cd control-plane-ui
npm install
```

### Development Server

```bash
npm run dev
```

Opens on `http://localhost:3000` with hot reload.

**Gateway Proxy:** Vite proxies `/api/*` to `http://localhost:8080` automatically.

### Build for Production

```bash
npm run build
```

Output in `dist/` directory.

### Preview Production Build

```bash
npm run preview
```

## Usage

### Creating a Workflow

1. **Open Workflow Builder Tab**
2. **Select Workflow** from left panel (or create new)
3. **Add Nodes:**
   * Drag from node palette (future feature)
   * Or manually add via JSON (current MVP)
4. **Connect Nodes:** Click and drag between handles
5. **Configure Nodes:** Click node to edit properties
6. **Validate:** Click "Validate" button
7. **Publish:** Click "Publish" to deploy

### Node Types

#### Trigger Nodes (Purple Border)

**Event Match** 🎯
* Matches specific event name
* Optional predicates for filtering
* Example: `ui.freeze` or `ui.jank` where `duration_ms > 2000`

**HTTP Error Match** 🚫
* Matches HTTP error responses
* Status code threshold
* Optional route filter
* Example: `status >= 500` AND `route contains /appointments`

**Crash Marker** 💥
* Triggers on app crash detection
* No configuration needed

#### Logic Nodes (Blue Border)

**ANY (OR)** ∨
* Triggers if any input condition matches
* Used to combine multiple triggers

**ALL (AND)** ∧
* Triggers if all input conditions match
* Used for composite conditions

#### Action Nodes (Green Border)

**Flush Window** 📤
* Flushes last N minutes of buffered events
* Scope: session or device
* Example: Flush last 2 minutes for this session

**Annotate Trigger** 🏷️
* Adds metadata to flushed events
* Trigger ID and reason
* Example: Tag events with "ui-freeze" trigger

**Set Sampling** 🎲
* Adjusts sampling rate temporarily
* Rate (0.0 - 1.0) and duration
* Example: Set 100% sampling for 10 minutes

### Graph → DSL Compilation

The compiler converts React Flow graphs to device-executable JSON:

**Graph Format (Editing):**
```typescript
{
  id: "ui-freeze",
  name: "UI Freeze Handler",
  nodes: [...],
  edges: [...]
}
```

**DSL Format (Execution):**
```json
{
  "version": 1,
  "workflows": [
    {
      "id": "ui-freeze",
      "trigger": { "any": [...] },
      "actions": [...]
    }
  ]
}
```

**Compilation Rules:**
* Entry node must be a trigger or logic node
* Trigger → Logic → Action flow enforced
* No cycles allowed (validated)
* All edges must connect valid nodes

### Publishing Workflows

**Publish Process:**
1. Validate graph structure
2. Compile to DSL JSON
3. POST to `/admin/publish` with both formats
4. Gateway stores as new version
5. Devices fetch updated config on next poll

**What Gets Published:**
* `graph_json` - React Flow format (for future editing)
* `dsl_json` - Compiled DSL (for device execution)
* `published_by` - User identifier
* `version` - Auto-incremented by gateway

**Version Increments:**
* Each publish creates new version
* Old versions retained for rollback
* Only one version active at a time

### Rolling Back

1. Go to **Version Panel** (right sidebar)
2. Find desired version in list
3. Click **Rollback** button
4. Confirms and activates that version
5. Devices pick up on next config poll

### Device Monitoring

**Devices Tab** shows:
* Connected devices (via heartbeat)
* Device ID, session ID, app ID
* Buffer usage (MB)
* Last seen timestamp
* Recent triggers fired
* Config version in use

**Status Indicators:**
* 🟢 Green - Seen < 1 minute ago
* 🟡 Yellow - Seen 1-5 minutes ago
* 🔴 Red - Seen > 5 minutes ago

**Heartbeat Frequency:** Devices send status every 30 seconds

## API Integration

### Gateway Endpoints Used

```typescript
// Get config (for preview)
GET /config?app_id=X&device_id=Y

// Publish workflow
POST /admin/publish
{
  "graph_json": "...",
  "dsl_json": "...",
  "published_by": "admin"
}

// Rollback to version
POST /admin/rollback
{
  "version": 2
}

// List versions
GET /admin/versions?limit=50

// Health check
GET /health
```

### Example: Publishing via API

```typescript
import { gatewayAPI } from './api/gateway';
import { compileGraphToDSL } from './utils/graphToDSL';

const workflows = [/* workflow graphs */];
const dslConfig = compileGraphToDSL(workflows, {
  diskMb: 50,
  ramEvents: 5000,
  retentionHours: 24
});

const response = await gatewayAPI.publish(
  workflows,
  dslConfig,
  'admin'
);

console.log(`Published version ${response.version}`);
```

## Configuration

### Gateway URL

Update in `vite.config.ts`:

```typescript
server: {
  proxy: {
    '/api': {
      target: 'http://localhost:8080',  // Change this
      changeOrigin: true
    }
  }
}
```

### Port

Default: `3000`

Change in `vite.config.ts`:

```typescript
server: {
  port: 3000  // Change this
}
```

## Development Notes

### Adding New Node Types

1. **Create Node Component** in `src/components/nodes/`
2. **Add Type** to `src/types/workflow.ts`
3. **Register** in `WorkflowBuilder.tsx` nodeTypes
4. **Update Compiler** in `src/utils/graphToDSL.ts`

### Extending Graph Validation

Edit `validateGraph()` in `src/utils/graphToDSL.ts`:

```typescript
export function validateGraph(graph: WorkflowGraph): string[] {
  const errors: string[] = [];
  // Add validation rules
  return errors;
}
```

### State Management

Currently uses React `useState`. For complex apps, integrate Zustand:

```typescript
import { create } from 'zustand';

const useWorkflowStore = create((set) => ({
  workflows: [],
  addWorkflow: (workflow) => set((state) => ({
    workflows: [...state.workflows, workflow]
  }))
}));
```

## Deployment

### Docker

```dockerfile
FROM node:18-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
```

### Kubernetes

```yaml
apiVersion: v1
kind: Service
metadata:
  name: control-plane-ui
spec:
  ports:
  - port: 80
    targetPort: 80
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: control-plane-ui
spec:
  replicas: 1
  template:
    spec:
      containers:
      - name: ui
        image: control-plane-ui:latest
        ports:
        - containerPort: 80
```

**Note:** Update nginx config to proxy `/api` to gateway service.

## Troubleshooting

### Gateway Connection Fails

**Error:** `Network Error` or `Failed to fetch`

**Solutions:**
* Check gateway is running: `curl http://localhost:8080/health`
* Verify proxy config in `vite.config.ts`
* Check browser console for CORS errors

### Workflow Won't Publish

**Error:** "Validation errors: ..."

**Solutions:**
* Click "Validate" to see specific errors
* Check all nodes have required fields
* Ensure no cycles in graph
* Verify entry node is set correctly

### Devices Not Showing

**Possible Causes:**
* Android app not running
* Gateway not receiving heartbeats
* Device Monitor using mock data (check `DeviceMonitor.tsx`)

**Solution:** Implement real heartbeat polling in `DeviceMonitor.tsx`:

```typescript
useEffect(() => {
  const fetchDevices = async () => {
    const devices = await gatewayAPI.getHeartbeats();
    setDevices(devices);
  };
  fetchDevices();
  const interval = setInterval(fetchDevices, 30000);
  return () => clearInterval(interval);
}, []);
```

### Build Errors

**TypeScript errors:**
```bash
npm run build
# Fix any type errors shown
```

**Missing dependencies:**
```bash
rm -rf node_modules package-lock.json
npm install
```

## Testing

### Manual Testing Checklist

- [ ] Open UI in browser
- [ ] Workflow Builder loads
- [ ] Can add/edit nodes
- [ ] Can connect nodes with edges
- [ ] Validate button works
- [ ] Publish button works
- [ ] Version list updates after publish
- [ ] Rollback button works
- [ ] Device Monitor shows devices
- [ ] Tabs switch correctly

### Integration Testing

1. **Start Gateway:**
   ```bash
   kubectl port-forward -n mobile-observability svc/otel-gateway 8080:8080
   ```

2. **Start UI:**
   ```bash
   npm run dev
   ```

3. **Publish Workflow:**
   * Create simple workflow
   * Click Publish
   * Check gateway logs for publish request

4. **Verify on Android:**
   * Restart Android app
   * Check Logcat for new config version

## Future Enhancements

**Phase 1 (MVP - Current):**
* ✅ Visual workflow builder
* ✅ Basic node types
* ✅ Graph validation
* ✅ Publish/rollback
* ✅ Device monitoring (mock)

**Phase 2:**
* [ ] Drag-and-drop node palette
* [ ] Real-time device heartbeat polling
* [ ] Workflow simulation/testing
* [ ] Multi-user authentication
* [ ] Workflow templates library

**Phase 3:**
* [ ] Analytics dashboard (event counts, trigger frequency)
* [ ] Device filtering and search
* [ ] Workflow cloning
* [ ] Export/import workflows
* [ ] Audit logs

**Phase 4:**
* [ ] Real-time collaboration (multiple editors)
* [ ] Advanced node types (custom actions)
* [ ] Workflow versioning/diffing
* [ ] A/B testing workflows
* [ ] Performance metrics

## License

Apache 2.0 (for demo purposes)

## Support

For issues or questions:
* Check [E2E_VERIFICATION_CHECKLIST.md](../E2E_VERIFICATION_CHECKLIST.md)
* Review gateway logs: `kubectl logs -n mobile-observability -l app=otel-gateway`
* Check browser DevTools console for errors
