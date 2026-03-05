# User Guide

Complete guide to using the Mobile Observability Control Plane UI.

## Table of Contents

1. [Overview](#overview)
2. [Getting Started](#getting-started)
3. [Creating Export Policies](#creating-export-policies)
4. [Node Types](#node-types)
5. [Publishing](#publishing)
6. [Managing Versions](#managing-versions)
7. [Monitoring Devices](#monitoring-devices)
8. [Best Practices](#best-practices)
9. [Example Export Policies](#example-export-policies)

## Overview

The Control Plane UI lets you visually author and manage **export policies** that control when mobile devices send observability data. Instead of streaming everything all the time, policies define conditions (triggers) that cause the SDK to flush a window of buffered events.

**Key Concepts:**

- **Export Policy**: A set of trigger conditions + flush actions. When a trigger fires, the SDK flushes the matching time window of events.
- **Trigger**: A condition on the event stream (e.g., `ui.freeze` detected, HTTP 5xx cascade, crash marker on launch).
- **Action**: What the SDK does when triggered (e.g., flush last 2 minutes, increase sampling rate).
- **Node**: A building block in the visual policy editor.
- **Version**: Each published config is versioned — rollback anytime.

> Note: The Control Plane UI code and APIs currently use the term "workflow" internally. These map 1:1 to export policies.

## Getting Started

### Accessing the UI

1. Ensure the gateway is running:

   ```bash
   kubectl port-forward -n mobile-observability svc/otel-gateway 8080:8080
   ```

2. Start the UI:

   ```bash
   cd control-plane-ui
   npm run dev
   ```

3. Open <http://localhost:3000>

### UI Layout

```
┌─────────────────────────────────────────────────────────────┐
│  Mobile Observability Control Plane            [Validate]   │
│                                                  [Publish]  │
├──────────────┬──────────────────────────────────────────────┤
│              │                                              │
│  Policies    │           Canvas (React Flow)                │
│              │                                              │
│  □ Policy 1  │     ┌──────────┐         ┌──────────┐      │
│  □ Policy 2  │     │ Trigger  │────────►│  Action  │      │
│  ■ Policy 3  │     └──────────┘         └──────────┘      │
│              │                                              │
│  [+ New]     │                                              │
│              │                                              │
├──────────────┼──────────────────────────────────────────────┤
│  Versions    │           Properties Panel                   │
│              │                                              │
│  v3 (active) │     Selected Node:                           │
│  v2          │     Type: Event Match                        │
│  v1          │     Event Name: ui.freeze                    │
│              │     Predicates: duration_ms > 2000           │
└──────────────┴──────────────────────────────────────────────┘
```

**Main Areas:**

- **Top Toolbar**: Validate, Publish, tab navigation
- **Left Sidebar**: Policy list and version history
- **Center Canvas**: Visual policy editor (React Flow)
- **Right Panel**: Properties for selected node

## Creating Export Policies

### Step 1: Create a New Policy

1. Click **"+ New"** in the left sidebar
2. Enter a name (e.g., "UI Freeze Handler")
3. Click **Create** — the policy opens on the canvas

### Step 2: Add Nodes

Right-click on the canvas and select a node type from the menu.

### Step 3: Configure Nodes

Click a node to select it. Edit its properties in the right panel. Changes save automatically.

### Step 4: Connect Nodes

Hover over a node — connection handles appear on the edges. Drag from an output handle to an input handle on the target node.

### Step 5: Set Entry Point

Right-click the policy in the sidebar → **"Set Entry Node"**, or check **"Entry Node"** in the selected node's properties panel.

### Step 6: Validate

Click **"Validate"** in the toolbar. Fix any errors (missing entry node, disconnected nodes, cycles). When valid, **"Graph valid ✓"** appears.

## Node Types

### Trigger Nodes

Trigger nodes detect conditions in the event stream.

#### Event Match

Matches events by name with optional attribute predicates.

```
Event Name: ui.freeze
Predicates:
  - field: duration_ms
    operator: >
    value: 2000
```

Use for: UI performance issues, custom business events.

#### HTTP Error Match

Matches HTTP errors by status code and optional route pattern.

```
Status Threshold: 500
Route Filter: /api/appointments
```

Use for: backend 5xx cascades, specific API failures.

#### Crash Marker

Fires when a crash/ANR/OOM marker is detected on app restart. No configuration — the SDK's `RecoveryTracker` sets the marker before the process dies.

Use for: crash diagnostics, stability monitoring.

### Logic Nodes

#### ANY (OR)

Fires when **any** connected trigger matches. Use to combine related conditions (e.g., `ui.freeze` OR `ui.jank` OR `app.anr`).

#### ALL (AND)

Fires when **all** connected triggers match. Use for composite conditions (e.g., HTTP error AND user is authenticated).

### Action Nodes

#### Flush Window

Exports buffered events from a time window.

```
Window Minutes: 2
Scope: session     # or "device" (all sessions)
```

This is the core action — export only the event window around a problem, not everything.

#### Annotate Trigger

Adds metadata tags to flushed events for backend filtering.

```
Trigger ID: ui-freeze-handler
Reason: UI freeze detected (>2s)
```

#### Set Sampling

Temporarily increases the sampling rate after a trigger.

```
Rate: 1.0           # 100%
Duration Minutes: 10
```

Use to capture full detail during an incident without keeping 100% sampling permanently.

## Publishing

1. Create and validate the policy
2. Click **"Publish"** in the toolbar
3. A new version is created and activated
4. Mobile devices fetch the updated config on their next poll (default: 60s)

### What Gets Published

- The visual graph JSON (for future editing in the UI)
- The compiled DSL JSON (what the Android SDK evaluates)
- Auto-incremented version number

### Compiled DSL Example

A visual `EventMatch → FlushWindow` graph compiles to:

```json
{
  "id": "ui-freeze-handler",
  "enabled": true,
  "match": {
    "logicalOperator": "and",
    "attributes": {
      "event.name": { "equals": "ui.freeze" },
      "duration_ms": { "gt": 2000 }
    }
  },
  "actions": { "flushWindowMinutes": 2 }
}
```

## Managing Versions

The left sidebar shows version history:

```
Versions
─────────
v3 (active) ★
  ui-freeze-handler
  crash-recovery

v2
  ui-freeze-handler
```

### Rollback

Click a previous version → **"Rollback"**. The version activates immediately; devices pick it up on next poll.

## Monitoring Devices

The **Device Monitor** tab shows connected devices with:

- Device ID, session ID
- Buffer usage (RAM / disk)
- Config version in use
- Last seen timestamp
- Recent triggers fired

Status indicators:

- Green: active (seen < 1 min ago)
- Yellow: idle (1-5 min)
- Red: offline (> 5 min)

## Best Practices

### Flush Window Sizing

| Scenario | Recommended window |
| --- | --- |
| High-frequency events (taps, scroll) | 1-2 min |
| UI freezes, HTTP errors | 2-5 min |
| Crash recovery, rare events | 5-10 min |

### Sampling

- Default production sampling: 1% (0.01)
- Use **Set Sampling** to bump to 100% for 10 min after a trigger
- Don't permanently set 100% — it defeats the purpose of conditional export

### Design

- Keep policies focused: one trigger type per policy is easier to reason about
- Use **Annotate** on every policy so you can filter events by cause in your backend
- Test with the demo app before deploying to production devices

## Example Export Policies

### UI Freeze

```
[Event Match: ui.freeze, duration_ms > 2000]
    ├──► [Flush Window: 2 min, session]
    └──► [Annotate: ui-freeze-handler]
```

### Crash Recovery

```
[Crash Marker]
    ├──► [Flush Window: 5 min, device]
    ├──► [Annotate: crash]
    └──► [Set Sampling: 1.0, 10 min]
```

### HTTP Error Cascade

```
[HTTP Error Match: status >= 500, /api/]
    ├──► [Flush Window: 2 min, session]
    └──► [Annotate: server-error]
```

### Multi-Signal Performance

```
           ┌──► [Event Match: ui.freeze]
[ANY] ─────┼──► [Event Match: app.anr]
           └──► [Event Match: ui.jank]
                    ├──► [Flush Window: 3 min, session]
                    └──► [Annotate: perf-issue]
```

## Keyboard Shortcuts

| Shortcut | Action |
| --- | --- |
| `Delete` | Delete selected node |
| `Ctrl/Cmd + V` | Validate policy |
| `Ctrl/Cmd + P` | Publish |
| `Space + Drag` | Pan canvas |
| `Scroll` | Zoom in/out |

## Related Documentation

- [Quick Start](QUICK_START.md) — get up and running
- [Android SDK Guide](ANDROID_SDK_GUIDE.md) — SDK integration
- [API Reference](API_REFERENCE.md) — gateway API
- [Troubleshooting Guide](TROUBLESHOOTING_GUIDE.md) — common issues
