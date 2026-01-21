# User Guide

Complete guide to using the Mobile Observability Control Plane UI.

## Table of Contents

1. [Overview](#overview)
2. [Getting Started](#getting-started)
3. [Creating Workflows](#creating-workflows)
4. [Node Types](#node-types)
5. [Publishing Workflows](#publishing-workflows)
6. [Managing Versions](#managing-versions)
7. [Monitoring Devices](#monitoring-devices)
8. [Best Practices](#best-practices)
9. [Example Workflows](#example-workflows)

## Overview

The Control Plane UI allows you to visually create and manage workflows that control when mobile devices send observability data. Instead of sending all events all the time, workflows define specific conditions (triggers) that cause data to be uploaded.

**Key Concepts:**

- **Workflow**: A set of rules that define when to capture and send data
- **Trigger**: A condition that activates the workflow (e.g., crash, error, performance issue)
- **Action**: What happens when a trigger fires (e.g., flush last 2 minutes of events)
- **Node**: A building block in the visual workflow editor
- **Version**: Each published workflow gets a version number for rollback

## Getting Started

### Accessing the UI

1. Ensure the gateway is running and port-forwarded:
   ```bash
   kubectl port-forward -n mobile-observability svc/otel-gateway 8080:8080
   ```

2. Start the UI:
   ```bash
   cd control-plane-ui
   npm run dev
   ```

3. Open browser to http://localhost:3000

### UI Layout

```
┌─────────────────────────────────────────────────────────────┐
│  Mobile Observability Control Plane            [Validate]  │
│                                                  [Publish]  │
├──────────────┬──────────────────────────────────────────────┤
│              │                                              │
│  Workflows   │           Canvas (React Flow)               │
│              │                                              │
│  □ Workflow1 │     ┌──────────┐         ┌──────────┐      │
│  □ Workflow2 │     │ Trigger  │────────►│  Action  │      │
│  ■ Workflow3 │     └──────────┘         └──────────┘      │
│              │                                              │
│  [+ New]     │                                              │
│              │                                              │
├──────────────┼──────────────────────────────────────────────┤
│  Versions    │           Properties Panel                  │
│              │                                              │
│  v3 (active) │     Selected Node:                          │
│  v2          │     Type: Event Match                       │
│  v1          │     Event Name: ui.freeze                   │
│              │     Predicates: duration_ms > 2000          │
└──────────────┴──────────────────────────────────────────────┘
```

**Main Areas:**
- **Top Toolbar**: Validate, Publish, Tab navigation
- **Left Sidebar**: Workflow list and version history
- **Center Canvas**: Visual workflow editor
- **Right Sidebar**: Properties panel for selected node
- **Tabs**: Workflow Builder, Device Monitor

## Creating Workflows

### Step 1: Create a New Workflow

1. Click **"+ New Workflow"** button in the left sidebar
2. Enter workflow details:
   - **Name**: Descriptive name (e.g., "UI Freeze Handler")
   - **ID**: Unique identifier (auto-generated or custom)
   - **Enabled**: Toggle to activate/deactivate

3. Click **Create**

The new workflow appears in the left sidebar and opens on the canvas.

### Step 2: Add Nodes

**Method 1: Right-click Menu** (Current)
1. Right-click on canvas
2. Select node type from menu
3. Node appears at click position

**Method 2: Drag from Palette** (Future)
- Drag node type from palette
- Drop on canvas

### Step 3: Configure Nodes

1. **Click on a node** to select it
2. **Properties panel** appears on the right
3. **Edit properties**:
   - Event name
   - Predicates
   - Time windows
   - Scope settings
4. Changes save automatically

### Step 4: Connect Nodes

1. **Hover over a node** - connection handles appear
2. **Click and drag** from output handle (right side)
3. **Drop on input handle** of target node (left side)
4. Edge (connection line) is created

### Step 5: Set Entry Point

1. **Right-click workflow** in left sidebar
2. Select **"Set Entry Node"**
3. Choose which node starts the workflow

Or:
1. Click node to select
2. In properties panel, check **"Entry Node"**

### Step 6: Validate Workflow

1. Click **"Validate"** button in toolbar
2. Review any errors:
   - Missing entry node
   - Disconnected nodes
   - Cycles in graph
   - Invalid configuration

3. Fix errors and validate again
4. When valid, **"Graph valid ✓"** appears

## Node Types

### Trigger Nodes (Purple)

Trigger nodes detect specific conditions in the event stream.

#### 1. Event Match 🎯

Matches events by name and optional predicates.

**Properties:**
- **Event Name**: Event to match (e.g., `ui.freeze`, `network.error`)
- **Predicates**: Optional filters on event attributes

**Example:**
```
Event Name: ui.freeze
Predicates:
  - field: duration_ms
    operator: >
    value: 2000
```

**Use Cases:**
- UI performance issues
- Custom business events
- User interactions

#### 2. HTTP Error Match 🚫

Matches HTTP errors from network requests.

**Properties:**
- **Status Threshold**: Minimum status code (e.g., 400, 500)
- **Route Filter**: Optional route pattern (e.g., `/api/appointments`)

**Example:**
```
Status Threshold: 500
Route Filter: /appointments
```

**Use Cases:**
- Backend API errors
- Server failures
- Network issues

#### 3. Crash Marker 💥

Detects app crashes (no configuration needed).

**Properties:**
- None (automatically detects crashes)

**Use Cases:**
- Crash diagnostics
- Stability monitoring
- Production issue triage

### Logic Nodes (Blue)

Logic nodes combine multiple conditions.

#### 4. ANY (OR) ∨

Fires if **any** input condition matches.

**Properties:**
- Input connections from multiple triggers

**Example:**
```
ANY
 ├─ ui.freeze
 ├─ ui.jank
 └─ ui.anr
```

**Use Cases:**
- Multiple related conditions
- Broad monitoring
- Fallback triggers

#### 5. ALL (AND) ∧

Fires if **all** input conditions match.

**Properties:**
- Input connections from multiple triggers

**Example:**
```
ALL
 ├─ http.error (status >= 500)
 └─ user.authenticated = true
```

**Use Cases:**
- Composite conditions
- Narrow monitoring
- Specific scenarios

### Action Nodes (Green)

Action nodes define what happens when triggered.

#### 6. Flush Window 📤

Flushes buffered events from a time window.

**Properties:**
- **Window Minutes**: How far back to flush (e.g., 2, 5, 10)
- **Scope**: `session` (current session only) or `device` (all sessions)

**Example:**
```
Window Minutes: 2
Scope: session
```

**Result:** Sends last 2 minutes of events from current session to gateway.

**Use Cases:**
- Capture context around issues
- Send relevant data only
- Minimize data egress

#### 7. Annotate Trigger 🏷️

Adds metadata tags to flushed events.

**Properties:**
- **Trigger ID**: Identifier for this trigger
- **Reason**: Human-readable description

**Example:**
```
Trigger ID: ui-freeze-handler
Reason: UI freeze detected (>2s)
```

**Result:** All flushed events include these tags for filtering.

**Use Cases:**
- Tag events by cause
- Enable filtering in backend
- Add context

#### 8. Set Sampling 🎲

Adjusts event sampling rate temporarily.

**Properties:**
- **Rate**: Sampling rate 0.0-1.0 (0.0 = 0%, 1.0 = 100%)
- **Duration Minutes**: How long to apply new rate

**Example:**
```
Rate: 1.0
Duration Minutes: 10
```

**Result:** Capture 100% of events for next 10 minutes.

**Use Cases:**
- Temporarily increase detail
- Debug mode
- High-priority incidents

## Publishing Workflows

### Publish Process

1. **Create and validate workflow** (see Creating Workflows)
2. **Click "Publish" button** in toolbar
3. **Confirm publication**
4. **New version created** and activated automatically
5. **Mobile devices fetch updated config** on next poll (60s interval)

### What Gets Published

When you publish:
- **Graph JSON**: Visual workflow structure (for future editing)
- **DSL JSON**: Compiled executable format (for devices)
- **Version Number**: Auto-incremented (1, 2, 3, ...)
- **Published By**: User identifier (default: "admin")
- **Timestamp**: Publication time

### Compilation

The visual workflow is compiled to DSL format:

**Visual (Graph):**
```
EventMatch(ui.freeze) ──► FlushWindow(2min)
```

**Compiled (DSL):**
```json
{
  "id": "ui-freeze-handler",
  "trigger": {
    "any": [{"event": "ui.freeze"}]
  },
  "actions": [
    {"flush_window": {"minutes": 2, "scope": "session"}}
  ]
}
```

### Validation Before Publish

The system checks:
- ✅ Entry node is set
- ✅ All nodes are connected
- ✅ No cycles in graph
- ✅ All required properties filled
- ✅ Valid configuration values

If validation fails, fix errors before publishing.

## Managing Versions

### Version History

The left sidebar shows version history:

```
Versions
─────────
v3 (active) ★
  ui-freeze-handler
  crash-recovery

v2
  ui-freeze-handler

v1
  ui-freeze-handler
```

- **Active version**: Marked with ★, shown in bold
- **Version number**: Auto-incremented on each publish
- **Workflow list**: Which workflows are in that version

### Rollback to Previous Version

1. **Click on version** in version history
2. **Click "Rollback" button**
3. **Confirm rollback**
4. **Version activated immediately**
5. **Mobile devices fetch on next poll**

**Use Cases:**
- Undo problematic changes
- Return to known-good configuration
- A/B testing different workflows

### Viewing Version Details

Click on a version in the history to see:
- Version number
- Publication timestamp
- Published by (user)
- List of workflows
- DSL JSON (advanced)

## Monitoring Devices

### Device Monitor Tab

Click **"Device Monitor"** tab to see connected devices.

**Device Card Shows:**
- Device ID
- Session ID
- App ID
- Buffer usage (RAM/disk)
- Last seen timestamp
- Recent triggers fired
- Config version in use

**Status Indicators:**
- 🟢 **Green**: Seen < 1 minute ago (active)
- 🟡 **Yellow**: Seen 1-5 minutes ago (idle)
- 🔴 **Red**: Seen > 5 minutes ago (offline)

### Device Details

Click on a device card to see:
- Full device information
- Event buffer status
- Workflow execution history
- Recent triggers and actions
- Network status

### Real-Time Updates

Device monitor polls gateway every 30 seconds for:
- Heartbeat status
- Buffer levels
- Trigger activity
- Config version synchronization

## Best Practices

### Workflow Design

**DO:**
- ✅ Use descriptive workflow names
- ✅ Start with simple triggers
- ✅ Test workflows before deploying to production
- ✅ Use appropriate flush windows (not too large)
- ✅ Add annotations for filtering
- ✅ Document workflow purpose

**DON'T:**
- ❌ Create overly complex workflows
- ❌ Flush entire device history (use reasonable windows)
- ❌ Create cycles in workflow graphs
- ❌ Publish untested workflows
- ❌ Use generic names like "workflow1"

### Trigger Selection

**Choose the right trigger:**
- **Event Match**: For specific custom events
- **HTTP Error Match**: For backend API issues
- **Crash Marker**: For stability monitoring
- **ANY**: When multiple conditions should trigger
- **ALL**: When composite conditions required

### Window Sizing

**Flush Window Guidelines:**
- **1-2 minutes**: For high-frequency events
- **2-5 minutes**: For typical scenarios
- **5-10 minutes**: For crash recovery, low-frequency events
- **> 10 minutes**: Rarely needed, high data cost

**Consider:**
- Event frequency in your app
- Average session length
- Data egress costs
- Storage capacity

### Sampling Strategy

**Use Set Sampling for:**
- Debugging production issues
- Temporarily increasing detail
- High-priority incidents

**Default sampling:**
- Production: 0.01 (1%)
- Development: 1.0 (100%)
- After trigger: 1.0 for 5-10 minutes

## Example Workflows

### Example 1: UI Performance Monitoring

**Goal**: Capture context when UI freezes.

**Workflow:**
```
[Event Match: ui.freeze, duration_ms > 2000]
    │
    ├──► [Flush Window: 2 minutes, session]
    │
    └──► [Annotate: trigger_id=ui-freeze, reason=UI freeze detected]
```

**Steps:**
1. Create Event Match node
   - Event Name: `ui.freeze`
   - Predicate: `duration_ms > 2000`
2. Create Flush Window node
   - Window: 2 minutes
   - Scope: session
3. Create Annotate node
   - Trigger ID: ui-freeze
   - Reason: UI freeze detected
4. Connect: Event Match → Flush Window → Annotate
5. Set Event Match as entry node
6. Validate and publish

### Example 2: Crash Recovery

**Goal**: Send events leading up to crash.

**Workflow:**
```
[Crash Marker]
    │
    ├──► [Flush Window: 5 minutes, device]
    │
    ├──► [Annotate: trigger_id=crash, reason=App crashed]
    │
    └──► [Set Sampling: 1.0, 10 minutes]
```

**Steps:**
1. Create Crash Marker node (no config)
2. Create Flush Window node
   - Window: 5 minutes
   - Scope: device (all sessions)
3. Create Annotate node
   - Trigger ID: crash
   - Reason: App crashed
4. Create Set Sampling node
   - Rate: 1.0 (100%)
   - Duration: 10 minutes
5. Connect all nodes in sequence
6. Set Crash Marker as entry node
7. Validate and publish

### Example 3: Network Error Escalation

**Goal**: Deep dive on server errors for specific API.

**Workflow:**
```
[HTTP Error Match: status >= 500, route=/appointments]
    │
    ├──► [Flush Window: 2 minutes, session]
    │
    ├──► [Annotate: trigger_id=api-error, reason=Server error on appointments]
    │
    └──► [Set Sampling: 1.0, 10 minutes]
```

**Steps:**
1. Create HTTP Error Match node
   - Status Threshold: 500
   - Route Filter: /appointments
2. Create Flush Window node
   - Window: 2 minutes
   - Scope: session
3. Create Annotate node
   - Trigger ID: api-error
   - Reason: Server error on appointments
4. Create Set Sampling node
   - Rate: 1.0
   - Duration: 10 minutes
5. Connect all nodes
6. Set HTTP Error Match as entry node
7. Validate and publish

### Example 4: Combined Performance Triggers

**Goal**: Monitor multiple performance issues.

**Workflow:**
```
           ┌──► [Event Match: ui.freeze]
           │
[ANY] ─────┼──► [Event Match: ui.jank]
           │
           └──► [Event Match: ui.anr]
                    │
                    ├──► [Flush Window: 3 minutes, session]
                    │
                    └──► [Annotate: trigger_id=perf-issue, reason=Performance problem]
```

**Steps:**
1. Create ANY node
2. Create three Event Match nodes
   - ui.freeze
   - ui.jank
   - ui.anr
3. Create Flush Window node (3 minutes, session)
4. Create Annotate node
5. Connect:
   - Each Event Match → ANY
   - ANY → Flush Window
   - Flush Window → Annotate
6. Set ANY as entry node
7. Validate and publish

### Example 5: Authenticated User Errors

**Goal**: Track errors only for logged-in users.

**Workflow:**
```
           ┌──► [HTTP Error Match: status >= 400]
           │
[ALL] ─────┤
           │
           └──► [Event Match: user.authenticated = true]
                    │
                    └──► [Flush Window: 2 minutes, session]
```

**Steps:**
1. Create ALL node
2. Create HTTP Error Match (status >= 400)
3. Create Event Match (user.authenticated = true)
4. Create Flush Window (2 minutes, session)
5. Connect:
   - HTTP Error Match → ALL
   - Event Match → ALL
   - ALL → Flush Window
6. Set ALL as entry node
7. Validate and publish

## Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Ctrl/Cmd + S` | Save (auto-save active) |
| `Delete` | Delete selected node |
| `Ctrl/Cmd + Z` | Undo (planned) |
| `Ctrl/Cmd + V` | Validate workflow |
| `Ctrl/Cmd + P` | Publish workflow |
| `Space + Drag` | Pan canvas |
| `Scroll` | Zoom in/out |

## Tips and Tricks

### Workflow Organization

- **Naming**: Use clear, consistent names (e.g., `performance-ui-freeze`, `crash-recovery-main`)
- **Grouping**: Create separate workflows for different concerns
- **Documentation**: Use annotations to document trigger reasons

### Testing Workflows

1. **Start simple**: Test with single trigger and action
2. **Verify locally**: Test with Android emulator first
3. **Monitor logs**: Watch gateway and collector logs
4. **Use test events**: Send manual events via curl
5. **Iterate**: Refine based on actual data volume

### Performance

- **Minimize flush windows**: Smaller windows = less data
- **Use session scope**: More targeted than device scope
- **Appropriate sampling**: Don't always use 100%
- **Specific triggers**: Avoid broad matches

### Debugging

- **Check gateway logs**: See events being received
- **Check collector logs**: See OTEL logs exported
- **Use demo_run_id**: Track events end-to-end
- **Test with curl**: Send events manually
- **Version rollback**: Quickly revert problematic workflows

## Troubleshooting

### Workflow won't validate

**Error**: "Entry node not set"
- **Fix**: Right-click workflow, select entry node

**Error**: "Graph contains cycles"
- **Fix**: Remove circular connections

**Error**: "Disconnected nodes"
- **Fix**: Connect all nodes to workflow

### Publish fails

**Error**: "Network error"
- **Fix**: Check gateway port-forward: `curl http://localhost:8080/health`

**Error**: "Validation errors"
- **Fix**: Click Validate to see specific issues

### Devices not updating

**Problem**: Devices still using old config
- **Fix**: Wait 60 seconds for config poll, or restart app

**Problem**: Device shows wrong version
- **Fix**: Check device monitor for actual version

### No devices showing

**Problem**: Device monitor empty
- **Fix**: Check Android app is running and sending heartbeats

## Next Steps

Now that you know how to use the Control Plane UI:

1. **Try the examples**: Create the example workflows above
2. **Test on Android**: Deploy to emulator or device
3. **Monitor results**: Check Device Monitor tab
4. **Read Developer Guide**: Learn to extend the system
5. **Deploy to production**: See Operations Guide

## Related Documentation

- [Quick Start](QUICK_START.md) - Get up and running
- [Developer Guide](DEVELOPER_GUIDE.md) - Extend the system
- [API Reference](API_REFERENCE.md) - API documentation
- [Troubleshooting Guide](TROUBLESHOOTING_GUIDE.md) - Common issues

---

**Need help?** Check the logs or review the troubleshooting guide.
