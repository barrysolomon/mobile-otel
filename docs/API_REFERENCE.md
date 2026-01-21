# API Reference

Complete API documentation for the Mobile Observability Gateway.

## Table of Contents

1. [Overview](#overview)
2. [Authentication](#authentication)
3. [Base URL](#base-url)
4. [Event Ingestion](#event-ingestion)
5. [Configuration](#configuration)
6. [Admin Endpoints](#admin-endpoints)
7. [Health & Status](#health--status)
8. [Error Responses](#error-responses)
9. [Rate Limiting](#rate-limiting)
10. [Examples](#examples)

## Overview

The Gateway API provides HTTP endpoints for:
- Event ingestion from mobile devices
- Workflow configuration retrieval
- Administrative operations (publish, rollback, versions)
- Health monitoring

**Protocol:** HTTP/1.1
**Content-Type:** application/json
**Port:** 8080 (default, configurable)

## Authentication

### Current Implementation (Demo)

No authentication required. All endpoints are open.

### Production Recommendations

**API Key Authentication:**
```http
POST /ingest
X-API-Key: your-api-key-here
Content-Type: application/json
```

**JWT Authentication:**
```http
POST /admin/publish
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json
```

## Base URL

**Development (port-forwarded):**
```
http://localhost:8080
```

**Production (example):**
```
https://gateway.yourcompany.com
```

**Kubernetes (internal):**
```
http://otel-gateway.mobile-observability.svc.cluster.local:8080
```

## Event Ingestion

### POST /ingest

Ingest events from mobile devices.

#### Request

```http
POST /ingest
Content-Type: application/json
```

**Body:**
```json
{
  "events": [
    {
      "event_name": "string",
      "timestamp": number,
      "session_id": "string",
      "device_id": "string",
      "app_id": "string",
      "config_version": number,
      "trigger_id": "string (optional)",
      "attributes": {
        "key": "value"
      }
    }
  ]
}
```

**Fields:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| events | array | Yes | Array of event objects |
| event_name | string | Yes | Event identifier (e.g., "ui.freeze") |
| timestamp | number | Yes | Unix timestamp in milliseconds |
| session_id | string | Yes | Unique session identifier (UUID) |
| device_id | string | Yes | Unique device identifier |
| app_id | string | Yes | Application identifier |
| config_version | number | Yes | Config version used by device |
| trigger_id | string | No | Workflow that triggered flush |
| attributes | object | No | Custom event attributes (any JSON-serializable values) |

#### Response

**Success (200 OK):**
```json
{
  "received": 5,
  "status": "ok"
}
```

| Field | Type | Description |
|-------|------|-------------|
| received | number | Number of events received |
| status | string | "ok" on success |

**Error (400 Bad Request):**
```json
{
  "error": "Invalid request body"
}
```

**Error (500 Internal Server Error):**
```json
{
  "error": "Failed to export events"
}
```

#### Example

```bash
curl -X POST http://localhost:8080/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "events": [
      {
        "event_name": "ui.freeze",
        "timestamp": 1705780000000,
        "session_id": "550e8400-e29b-41d4-a716-446655440000",
        "device_id": "device-12345",
        "app_id": "demo-app",
        "config_version": 1,
        "trigger_id": "ui-freeze-handler",
        "attributes": {
          "demo_run_id": "run-1705780000000",
          "duration_ms": 3500,
          "screen": "MainActivity"
        }
      }
    ]
  }'
```

## Configuration

### GET /config

Retrieve active workflow configuration for a device.

#### Request

```http
GET /config?app_id={app_id}&device_id={device_id}
```

**Query Parameters:**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| app_id | string | Yes | Application identifier |
| device_id | string | Yes | Device identifier |

#### Response

**Success (200 OK):**
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
      "id": "ui-freeze-handler",
      "enabled": true,
      "trigger": {
        "any": [
          {
            "event": "ui.freeze",
            "where": [
              {
                "field": "duration_ms",
                "op": ">",
                "value": 2000
              }
            ]
          }
        ]
      },
      "actions": [
        {
          "flush_window": {
            "minutes": 2,
            "scope": "session"
          }
        },
        {
          "annotate": {
            "trigger_id": "ui-freeze-handler",
            "reason": "UI freeze detected"
          }
        }
      ]
    }
  ]
}
```

**DSL Structure:**

| Field | Type | Description |
|-------|------|-------------|
| version | number | Config version number |
| limits | object | Buffer and retention limits |
| limits.diskMb | number | Disk buffer size in MB |
| limits.ramEvents | number | RAM buffer size in events |
| limits.retentionHours | number | Event retention time |
| workflows | array | Array of workflow definitions |
| workflows[].id | string | Workflow identifier |
| workflows[].enabled | boolean | Whether workflow is active |
| workflows[].trigger | object | Trigger definition (ANY/ALL) |
| workflows[].actions | array | Actions to execute |

**Trigger Operators:**

| Operator | Description | Example |
|----------|-------------|---------|
| == | Equals | `{"field": "status", "op": "==", "value": "error"}` |
| != | Not equals | `{"field": "status", "op": "!=", "value": "ok"}` |
| > | Greater than | `{"field": "duration_ms", "op": ">", "value": 1000}` |
| >= | Greater or equal | `{"field": "status_code", "op": ">=", "value": 400}` |
| < | Less than | `{"field": "duration_ms", "op": "<", "value": 100}` |
| <= | Less or equal | `{"field": "status_code", "op": "<=", "value": 399}` |
| contains | String contains | `{"field": "route", "op": "contains", "value": "/api"}` |
| regex | Regex match | `{"field": "error_msg", "op": "regex", "value": ".*timeout.*"}` |

**Action Types:**

| Action | Description | Parameters |
|--------|-------------|------------|
| flush_window | Flush time window | minutes (number), scope (string) |
| annotate | Add metadata | trigger_id (string), reason (string) |
| set_sampling | Adjust sampling | rate (0.0-1.0), duration_minutes (number) |

**Error (400 Bad Request):**
```json
{
  "error": "Missing required parameter: app_id"
}
```

**No Active Config (200 OK):**
```json
{
  "version": 0,
  "limits": {
    "diskMb": 50,
    "ramEvents": 5000,
    "retentionHours": 24
  },
  "workflows": []
}
```

#### Example

```bash
curl "http://localhost:8080/config?app_id=demo-app&device_id=device-12345"
```

## Admin Endpoints

### POST /admin/publish

Publish a new workflow configuration.

#### Request

```http
POST /admin/publish
Content-Type: application/json
```

**Body:**
```json
{
  "graph_json": "string",
  "dsl_json": "string",
  "published_by": "string"
}
```

**Fields:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| graph_json | string | Yes | React Flow graph (JSON string) |
| dsl_json | string | Yes | Compiled DSL config (JSON string) |
| published_by | string | Yes | Publisher identifier |

#### Response

**Success (200 OK):**
```json
{
  "version": 2,
  "status": "published"
}
```

| Field | Type | Description |
|-------|------|-------------|
| version | number | New version number |
| status | string | "published" on success |

**Error (400 Bad Request):**
```json
{
  "error": "Invalid DSL JSON"
}
```

**Error (500 Internal Server Error):**
```json
{
  "error": "Failed to save configuration"
}
```

#### Example

```bash
curl -X POST http://localhost:8080/admin/publish \
  -H "Content-Type: application/json" \
  -d '{
    "graph_json": "[{\"id\":\"ui-freeze\",\"name\":\"UI Freeze Handler\",\"enabled\":true,\"nodes\":[],\"edges\":[]}]",
    "dsl_json": "{\"version\":1,\"limits\":{\"diskMb\":50,\"ramEvents\":5000,\"retentionHours\":24},\"workflows\":[{\"id\":\"ui-freeze\",\"enabled\":true,\"trigger\":{\"any\":[{\"event\":\"ui.freeze\"}]},\"actions\":[{\"flush_window\":{\"minutes\":2,\"scope\":\"session\"}}]}]}",
    "published_by": "admin"
  }'
```

### POST /admin/rollback

Rollback to a previous configuration version.

#### Request

```http
POST /admin/rollback
Content-Type: application/json
```

**Body:**
```json
{
  "version": 1
}
```

**Fields:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| version | number | Yes | Version number to rollback to |

#### Response

**Success (200 OK):**
```json
{
  "version": 1,
  "status": "rolled_back"
}
```

| Field | Type | Description |
|-------|------|-------------|
| version | number | Activated version number |
| status | string | "rolled_back" on success |

**Error (404 Not Found):**
```json
{
  "error": "Version not found"
}
```

**Error (500 Internal Server Error):**
```json
{
  "error": "Failed to rollback"
}
```

#### Example

```bash
curl -X POST http://localhost:8080/admin/rollback \
  -H "Content-Type: application/json" \
  -d '{"version": 1}'
```

### GET /admin/versions

List all configuration versions.

#### Request

```http
GET /admin/versions?limit={limit}
```

**Query Parameters:**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| limit | number | No | 50 | Maximum versions to return |

#### Response

**Success (200 OK):**
```json
{
  "versions": [
    {
      "version": 2,
      "published_at": "2024-01-20T12:00:00Z",
      "published_by": "admin",
      "active": true,
      "dsl_json": "{...}",
      "graph_json": "[...]"
    },
    {
      "version": 1,
      "published_at": "2024-01-19T10:00:00Z",
      "published_by": "admin",
      "active": false,
      "dsl_json": "{...}",
      "graph_json": "[...]"
    }
  ]
}
```

**Fields:**

| Field | Type | Description |
|-------|------|-------------|
| versions | array | Array of version objects |
| versions[].version | number | Version number |
| versions[].published_at | string | ISO 8601 timestamp |
| versions[].published_by | string | Publisher identifier |
| versions[].active | boolean | Whether this version is active |
| versions[].dsl_json | string | DSL configuration (JSON string) |
| versions[].graph_json | string | Graph configuration (JSON string) |

**Error (500 Internal Server Error):**
```json
{
  "error": "Failed to retrieve versions"
}
```

#### Example

```bash
curl "http://localhost:8080/admin/versions?limit=10"
```

## Health & Status

### GET /health

Health check endpoint.

#### Request

```http
GET /health
```

#### Response

**Success (200 OK):**
```json
{
  "status": "healthy"
}
```

**Service Unavailable (503):**
```json
{
  "status": "unhealthy",
  "error": "Database connection failed"
}
```

#### Example

```bash
curl http://localhost:8080/health
```

### GET /status

Detailed status information (optional, not yet implemented).

#### Request

```http
GET /status
```

#### Response

**Success (200 OK):**
```json
{
  "status": "healthy",
  "uptime_seconds": 3600,
  "version": "1.0.0",
  "otel_collector": {
    "connected": true,
    "endpoint": "otel-collector:4317"
  },
  "database": {
    "connected": true,
    "path": "/data/gateway.db"
  },
  "stats": {
    "events_received": 12345,
    "events_exported": 12340,
    "events_failed": 5
  }
}
```

## Error Responses

All errors follow a consistent format:

```json
{
  "error": "Error message description"
}
```

### HTTP Status Codes

| Code | Status | Description |
|------|--------|-------------|
| 200 | OK | Request successful |
| 400 | Bad Request | Invalid request (malformed JSON, missing parameters) |
| 401 | Unauthorized | Authentication required (production) |
| 403 | Forbidden | Insufficient permissions (production) |
| 404 | Not Found | Resource not found (e.g., version doesn't exist) |
| 429 | Too Many Requests | Rate limit exceeded (production) |
| 500 | Internal Server Error | Server-side error |
| 503 | Service Unavailable | Service temporarily unavailable |

### Common Error Messages

| Error | Cause | Solution |
|-------|-------|----------|
| "Invalid request body" | Malformed JSON | Check JSON syntax |
| "Missing required parameter: app_id" | Query parameter missing | Add required parameters |
| "Failed to parse DSL JSON" | Invalid DSL format | Validate DSL structure |
| "Version not found" | Rollback to non-existent version | Check available versions |
| "Failed to connect to OTEL Collector" | Collector unreachable | Verify collector is running |

## Rate Limiting

### Current Implementation (Demo)

No rate limiting.

### Production Recommendations

**Per Device:**
- 100 requests per minute
- 1000 events per request

**Headers:**
```http
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 95
X-RateLimit-Reset: 1705780060
```

**Rate Limit Exceeded (429):**
```json
{
  "error": "Rate limit exceeded",
  "retry_after": 60
}
```

## Examples

### Complete Workflow: Create, Publish, Test

#### 1. Create Workflow via UI

Create workflow in Control Plane UI:
- Event Match: ui.freeze, duration_ms > 2000
- Flush Window: 2 minutes, session
- Annotate: trigger_id=ui-freeze, reason=UI freeze detected

#### 2. Publish Workflow

```bash
# Note: UI does this automatically, shown here for reference
curl -X POST http://localhost:8080/admin/publish \
  -H "Content-Type: application/json" \
  -d '{
    "graph_json": "[{\"id\":\"ui-freeze\",\"name\":\"UI Freeze Handler\",\"enabled\":true,\"entryNodeId\":\"node-1\",\"nodes\":[{\"id\":\"node-1\",\"type\":\"event_match\",\"data\":{\"eventName\":\"ui.freeze\",\"predicates\":[{\"field\":\"duration_ms\",\"op\":\">\",\"value\":2000}]},\"position\":{\"x\":100,\"y\":100}},{\"id\":\"node-2\",\"type\":\"flush_window\",\"data\":{\"windowMinutes\":2,\"scope\":\"session\"},\"position\":{\"x\":300,\"y\":100}},{\"id\":\"node-3\",\"type\":\"annotate\",\"data\":{\"triggerId\":\"ui-freeze\",\"reason\":\"UI freeze detected\"},\"position\":{\"x\":500,\"y\":100}}],\"edges\":[{\"id\":\"e1\",\"source\":\"node-1\",\"target\":\"node-2\"},{\"id\":\"e2\",\"source\":\"node-2\",\"target\":\"node-3\"}]}]",
    "dsl_json": "{\"version\":1,\"limits\":{\"diskMb\":50,\"ramEvents\":5000,\"retentionHours\":24},\"workflows\":[{\"id\":\"ui-freeze\",\"enabled\":true,\"trigger\":{\"any\":[{\"event\":\"ui.freeze\",\"where\":[{\"field\":\"duration_ms\",\"op\":\">\",\"value\":2000}]}]},\"actions\":[{\"flush_window\":{\"minutes\":2,\"scope\":\"session\"}},{\"annotate\":{\"trigger_id\":\"ui-freeze\",\"reason\":\"UI freeze detected\"}}]}]}",
    "published_by": "admin"
  }'
```

Response:
```json
{
  "version": 1,
  "status": "published"
}
```

#### 3. Device Fetches Config

```bash
curl "http://localhost:8080/config?app_id=demo-app&device_id=device-12345"
```

Response:
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
          {
            "event": "ui.freeze",
            "where": [
              {
                "field": "duration_ms",
                "op": ">",
                "value": 2000
              }
            ]
          }
        ]
      },
      "actions": [
        {
          "flush_window": {
            "minutes": 2,
            "scope": "session"
          }
        },
        {
          "annotate": {
            "trigger_id": "ui-freeze",
            "reason": "UI freeze detected"
          }
        }
      ]
    }
  ]
}
```

#### 4. Device Sends Events

```bash
curl -X POST http://localhost:8080/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "events": [
      {
        "event_name": "ui.freeze",
        "timestamp": 1705780000000,
        "session_id": "550e8400-e29b-41d4-a716-446655440000",
        "device_id": "device-12345",
        "app_id": "demo-app",
        "config_version": 1,
        "trigger_id": "ui-freeze",
        "attributes": {
          "demo_run_id": "run-1705780000000",
          "duration_ms": 3500,
          "screen": "MainActivity"
        }
      }
    ]
  }'
```

Response:
```json
{
  "received": 1,
  "status": "ok"
}
```

### Batch Event Ingestion

```bash
curl -X POST http://localhost:8080/ingest \
  -H "Content-Type: application/json" \
  -d '{
    "events": [
      {
        "event_name": "screen.view",
        "timestamp": 1705780000000,
        "session_id": "550e8400-e29b-41d4-a716-446655440000",
        "device_id": "device-12345",
        "app_id": "demo-app",
        "config_version": 1,
        "attributes": {
          "demo_run_id": "run-1705780000000",
          "screen_name": "MainActivity"
        }
      },
      {
        "event_name": "button.click",
        "timestamp": 1705780001000,
        "session_id": "550e8400-e29b-41d4-a716-446655440000",
        "device_id": "device-12345",
        "app_id": "demo-app",
        "config_version": 1,
        "attributes": {
          "demo_run_id": "run-1705780000000",
          "button_id": "submit"
        }
      },
      {
        "event_name": "http.request",
        "timestamp": 1705780002000,
        "session_id": "550e8400-e29b-41d4-a716-446655440000",
        "device_id": "device-12345",
        "app_id": "demo-app",
        "config_version": 1,
        "attributes": {
          "demo_run_id": "run-1705780000000",
          "method": "POST",
          "url": "/api/appointments",
          "status_code": 200,
          "duration_ms": 150
        }
      }
    ]
  }'
```

Response:
```json
{
  "received": 3,
  "status": "ok"
}
```

### Version Management

#### List Versions
```bash
curl "http://localhost:8080/admin/versions?limit=5"
```

#### Rollback to Version 1
```bash
curl -X POST http://localhost:8080/admin/rollback \
  -H "Content-Type: application/json" \
  -d '{"version": 1}'
```

#### Verify Active Version
```bash
curl "http://localhost:8080/admin/versions?limit=1"
```

## Client Libraries

### JavaScript/TypeScript

```typescript
// gateway-client.ts
import axios from 'axios';

export class GatewayClient {
  private baseURL: string;

  constructor(baseURL: string) {
    this.baseURL = baseURL;
  }

  async ingest(events: Event[]): Promise<{ received: number; status: string }> {
    const response = await axios.post(`${this.baseURL}/ingest`, { events });
    return response.data;
  }

  async getConfig(appId: string, deviceId: string): Promise<DSLConfig> {
    const response = await axios.get(`${this.baseURL}/config`, {
      params: { app_id: appId, device_id: deviceId },
    });
    return response.data;
  }

  async publish(graphJson: string, dslJson: string, publishedBy: string): Promise<{ version: number }> {
    const response = await axios.post(`${this.baseURL}/admin/publish`, {
      graph_json: graphJson,
      dsl_json: dslJson,
      published_by: publishedBy,
    });
    return response.data;
  }

  async rollback(version: number): Promise<{ version: number; status: string }> {
    const response = await axios.post(`${this.baseURL}/admin/rollback`, { version });
    return response.data;
  }

  async listVersions(limit: number = 50): Promise<{ versions: ConfigVersion[] }> {
    const response = await axios.get(`${this.baseURL}/admin/versions`, {
      params: { limit },
    });
    return response.data;
  }

  async health(): Promise<{ status: string }> {
    const response = await axios.get(`${this.baseURL}/health`);
    return response.data;
  }
}
```

### Kotlin (Android)

```kotlin
// GatewayClient.kt
class GatewayClient(private val baseUrl: String) {
    private val client = OkHttpClient()
    private val gson = Gson()

    suspend fun ingest(events: List<Event>): IngestResponse = withContext(Dispatchers.IO) {
        val request = IngestRequest(events)
        val json = gson.toJson(request)

        val httpRequest = Request.Builder()
            .url("$baseUrl/ingest")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(httpRequest).execute()
        val body = response.body?.string() ?: throw IOException("Empty response")

        if (!response.isSuccessful) {
            throw IOException("Ingest failed: ${response.code}")
        }

        gson.fromJson(body, IngestResponse::class.java)
    }

    suspend fun getConfig(appId: String, deviceId: String): DSLConfig = withContext(Dispatchers.IO) {
        val url = "$baseUrl/config?app_id=$appId&device_id=$deviceId"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw IOException("Empty response")

        if (!response.isSuccessful) {
            throw IOException("GetConfig failed: ${response.code}")
        }

        gson.fromJson(body, DSLConfig::class.java)
    }
}
```

## Related Documentation

- [User Guide](USER_GUIDE.md) - How to use the Control Plane UI
- [Developer Guide](DEVELOPER_GUIDE.md) - Extending the system
- [Quick Start](QUICK_START.md) - Get up and running
- [Operations Guide](OPERATIONS_GUIDE.md) - Production deployment

---

**Last Updated:** 2024-01-20
**API Version:** 1.0
