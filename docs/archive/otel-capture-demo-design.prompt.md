Correct 👍
**Only what I put next should be sent to the other AI.**
Treat the block below as a **clean, standalone prompt**. Ignore everything earlier.

You can copy-paste this *as the entire input* to the other AI.

---

# FULL DEMO SUITE PROMPT

## OTEL Capture-Style Mobile Observability (Android + Go + React Flow)

You are a principal engineer and product architect.

Your task is to design **and implement (in phases)** a **demoable, OTEL-based mobile observability system** inspired by publicly documented “Capture-style” patterns:

* Log everything locally
* Store data in a bounded **RAM → disk ring buffer**
* Use **workflow triggers / flows** to selectively flush buffered data
* Export flushed data as **OpenTelemetry Logs** to a collector
* Provide a **visual control plane** to author, publish, and monitor workflows

This is a **demo MVP**, not a production system.
Do **not** copy proprietary code or non-public details. Use public patterns only.

---

## FIXED STACK (do not change)

### Mobile

* Android (Kotlin)
* Android Studio
* Local persistence: Room (SQLite)

### Backend

* Gateway API: Go
* OpenTelemetry Collector: Kubernetes (k3s)
* Gateway → Collector: OTLP/gRPC
* Android → Gateway: JSON over HTTP

### Control Plane

* Web UI: React + Vite + TypeScript
* Flowchart editor: **React Flow**

---

## PRIMARY DEMO GOAL

End-to-end demo:

1. Android app captures high-volume “wide logs” locally
2. Data is buffered in a RAM → disk ring buffer
3. **Workflow triggers** decide when and what to flush
4. Flushed data is sent to the Go gateway
5. Gateway converts to OTEL Logs and exports to Collector
6. Collector shows logs immediately (via debug/log exporter)
7. Control UI:

   * visually edits workflows
   * publishes versions
   * monitors devices + last triggers

---

## DEMO SCENARIOS (must be implemented)

### A) UI freeze

* Trigger:

  * event `ui.freeze`, OR
  * event `ui.jank` where `duration_ms > 2000`
* Action:

  * annotate trigger
  * flush last **2 minutes** of logs for that session

### B) Crash on startup

* App writes `crash_marker` before crashing
* On next launch:

  * if crash marker exists → flush last **5 minutes**
  * include the crash marker record

### C) Network error spike

* Trigger:

  * `http.status >= 500`
  * AND route contains `/appointments`
* Action:

  * targeted flush (only matching session)
  * temporarily set sampling to `1.0` for **10 minutes**

---

## RING BUFFER REQUIREMENTS

* RAM staging buffer:

  * bounded queue
  * default: 5,000 events
* Disk buffer:

  * Room (SQLite)
  * default size limit: 50 MB
  * default retention: 24h
* Eviction:

  * oldest-first
  * enforced on insert
* Backpressure:

  * drop oldest, never block UI thread

---

## WORKFLOW SYSTEM (CRITICAL)

### Authoring

* Workflows are authored **visually** using **React Flow**
* Control UI stores:

  * `graph_json` (for editing)
  * `dsl_json` (compiled, published form)

### Execution

* Android **never executes the graph**
* Android executes **compiled DSL JSON only**

---

## REACT FLOW NODE MODEL (V1)

### Node Types

**Trigger nodes**

* `event_match`
* `http_error_match`
* `crash_marker`

**Logic nodes**

* `any` (OR)
* `all` (AND)

**Action nodes**

* `annotate_trigger`
* `flush_window`
* `set_sampling`

### Edge rules

* One `entryNodeId` per workflow
* Triggers/logic → logic or action
* Actions may chain to actions
* No cycles (validate before publish)

---

## GRAPH JSON (EDITOR FORMAT)

This is what the UI edits and saves.

```ts
type WorkflowGraph = {
  id: string;
  name: string;
  enabled: boolean;
  entryNodeId: string;
  nodes: GraphNode[];
  edges: { id: string; source: string; target: string }[];
};

type GraphNode =
  | { id: string; type: "event_match"; data: EventMatchData }
  | { id: string; type: "http_error_match"; data: HttpErrorMatchData }
  | { id: string; type: "crash_marker"; data: {} }
  | { id: string; type: "any"; data: {} }
  | { id: string; type: "all"; data: {} }
  | { id: string; type: "annotate_trigger"; data: AnnotateData }
  | { id: string; type: "flush_window"; data: FlushWindowData }
  | { id: string; type: "set_sampling"; data: SetSamplingData };

type Predicate = {
  attr: string;
  op: "==" | "!=" | ">" | ">=" | "<" | "<=" | "contains" | "regex";
  value: string | number | boolean;
};

type EventMatchData = {
  eventName: string;
  where?: Predicate[];
};

type HttpErrorMatchData = {
  statusMin: number;
  routeContains?: string;
};

type AnnotateData = {
  triggerId: string;
  reason: string;
};

type FlushWindowData = {
  minutes: number;
  scope: "session" | "device";
};

type SetSamplingData = {
  rate: number;
  durationMinutes: number;
};
```

---

## COMPILED DSL (DEVICE FORMAT)

React Flow graphs must compile into this **simple JSON DSL**:

```json
{
  "version": 1,
  "limits": {
    "diskMb": 50,
    "ramEvents": 5000,
    "retentionHours": 24
  },
  "workflows": [
    {
      "id": "ui-freeze",
      "enabled": true,
      "trigger": {
        "any": [
          { "event": "ui.freeze" },
          {
            "event": "ui.jank",
            "where": [
              { "attr": "duration_ms", "op": ">", "value": 2000 }
            ]
          }
        ]
      },
      "actions": [
        {
          "type": "annotate_trigger",
          "trigger_id": "ui-freeze",
          "reason": "ui freeze or jank"
        },
        {
          "type": "flush_window",
          "minutes": 2,
          "scope": "session"
        }
      ]
    }
  ]
}
```

Android evaluates this deterministically.

---

## CONTROL PLANE UI REQUIREMENTS

* React + Vite + TypeScript
* React Flow canvas
* Left: workflow list
* Center: flow editor
* Right: node inspector
* Top bar:

  * Validate
  * Simulate (run sample events through graph)
  * Publish
  * Rollback

### Device Monitoring

* Android posts heartbeat to `POST /status` every 30s:

  * device_id
  * app_id
  * session_id
  * buffer usage
  * last trigger(s)
  * last config version
* UI shows:

  * device list
  * last seen
  * last triggers fired

---

## GATEWAY API (GO)

Endpoints:

* `POST /ingest`
  Receives JSON batches of events

* `GET /config?app_id=...&device_id=...`
  Returns active DSL config

* `POST /status`
  Receives device heartbeat

* `POST /admin/publish`
  Publishes new workflow version

* `POST /admin/rollback`
  Rolls back to previous version

* `GET /admin/versions`
  Lists config versions

Persistence (MVP):

* SQLite or JSON file on disk (choose one and justify)

---

## OTEL REQUIREMENTS

* Gateway converts events → **OTEL Logs**
* Mapping:

  * body = event name
  * attributes = event attrs + session_id + device_id + trigger_id + config_version
* Collector:

  * OTLP gRPC receiver
  * memory_limiter + batch
  * **debug/logging exporter**
* Logs must be visible via `kubectl logs`

---

## REQUIRED OUTPUT (PHASED)

**Do NOT dump everything at once.**

### Step 1

* Kubernetes YAML for Collector
* Commands to deploy and verify

STOP.

Wait for the user to say **“continue”**.

---

## STYLE RULES

* One short overview paragraph max if needed
* Otherwise terse, spec-style
* Bullets over prose
* State assumptions explicitly

---

UPSTREAMABILITY REQUIREMENTS (OTel-friendly)
- Design the system so the Android buffering + flush logic can be extracted as a standalone library that is:
  - Apache-2.0 compatible
  - small surface area
  - vendor-neutral
  - does not depend on the control plane UI
- Treat workflows as generic "policy" (JSON) evaluated on-device.
- Keep the control plane UI + gateway as a separate demo/reference implementation repo that uses OpenTelemetry, not as part of core OTel.
- Avoid inventing new telemetry formats. Use OTEL Logs data model and OTLP for export.
- Provide a "Repository split plan" section:
  1) Android library module (publishable)
  2) Demo suite repo (app + gateway + UI + k8s manifests)
  3) Optional collector bits (only if necessary)
- Include licensing notes: Apache-2.0, CLA sign-off assumptions, and an "experimental" label for the Android module.
- Include a section: "What would be acceptable to submit to OpenTelemetry (contrib) vs what should remain external".

**Now produce Step 1 only.**
