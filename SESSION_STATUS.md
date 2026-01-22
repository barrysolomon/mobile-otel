# Mobile OTEL Project - Current Status

**Last Updated:** January 21, 2026
**Session Summary:** Implemented auto-registration and authentication

---

## ✅ What's Working Now

### System Components
1. **Gateway** - Go service running on port 8080
2. **Control Plane UI** - React app on port 5173 with workflow palette
3. **Android App** - Built and installed on emulator with auth token support
4. **Database** - SQLite with auto-created data directory

### Recent Accomplishments (This Session)

#### 1. Fixed Android App Build System
- Updated Gradle to 8.9 for compatibility
- Restructured project with proper app module
- Added launcher icons
- Successfully built and installed APK

#### 2. Implemented Auto-Registration ✅
**File:** `gateway/internal/handlers/handlers.go` (lines 184-215)

Devices now automatically register when sending their first heartbeat:
- Checks if device exists on heartbeat receipt
- Auto-creates device record with:
  - Device token: `auto_{deviceId}_{timestamp}`
  - Device group: `"default"`
  - Timestamps for registration and last seen
- Logs: `"Device X auto-registered successfully"`

**Impact:** Devices now appear in BOTH Live Monitor AND Device Fleet views

#### 3. Added End-to-End Authentication ✅

**Android App Changes:**
- `GatewayClient.kt`: Added auth token parameter + HTTP Authorization interceptor
- `ObservabilitySDK.kt`: Accepts optional `authToken` in `initialize()`
- `app/build.gradle.kts`: Hardcoded demo token as BuildConfig field
  ```kotlin
  buildConfigField("String", "OTEL_AUTH_TOKEN", "\"demo_token_12345\"")
  ```
- `MainActivity.kt`: Passes `BuildConfig.OTEL_AUTH_TOKEN` to SDK

**Gateway Changes:**
- `main.go`: Reads `OTEL_AUTH_TOKEN` environment variable
- `internal/otel/exporter.go`: Accepts auth token + adds to OTLP headers
  ```go
  otlploggrpc.WithHeaders(map[string]string{
      "Authorization": "Bearer " + authToken,
  })
  ```

**Auth Flow:**
```
Android App → Gateway → OTEL Collector
(Bearer token) → (Bearer token) → (validates token)
```

---

## 🚀 How to Run Everything

### Terminal 1: Gateway
```bash
cd /Users/barrysolomon/Projects/Dash0/mobile-otel/gateway
export OTEL_AUTH_TOKEN="demo_token_12345"  # Optional
go run main.go
```

Expected logs:
```
Starting Mobile Observability Gateway
Port: 8080
Database: ./data/gateway.db
Collector: otel-collector.mobile-observability.svc.cluster.local:4317
OTEL Auth Token: configured
Server listening on :8080
```

### Terminal 2: Control Plane UI
```bash
cd /Users/barrysolomon/Projects/Dash0/mobile-otel/control-plane-ui
npm run dev
```
Opens at: http://localhost:5173

### Terminal 3: Android App
```bash
cd /Users/barrysolomon/Projects/Dash0/mobile-otel/android-app
./gradlew installDebug
adb shell am start -n com.mobile.observability.demo/.MainActivity
```

### Monitoring
```bash
# Watch gateway logs
cd gateway && go run main.go

# Watch Android logs
adb logcat | grep -E "ObservabilitySDK|GatewayClient"

# Watch for auto-registration
# Look for: "Device X auto-registered successfully"
```

---

## 📁 Key Configuration Files

### Android Build Configuration
**Location:** `/Users/barrysolomon/Projects/Dash0/mobile-otel/android-app/app/build.gradle.kts`

```kotlin
defaultConfig {
    // Gateway URL for emulator
    buildConfigField("String", "GATEWAY_URL", "\"http://10.0.2.2:8080\"")

    // Auth token (hardcoded for demo)
    buildConfigField("String", "OTEL_AUTH_TOKEN", "\"demo_token_12345\"")
}
```

**For Physical Device:** Change `10.0.2.2` to your computer's local IP

### Gateway Configuration
**Environment Variables:**
- `PORT` - Default: 8080
- `DB_PATH` - Default: ./data/gateway.db
- `OTEL_COLLECTOR_ENDPOINT` - Default: otel-collector.mobile-observability.svc.cluster.local:4317
- `OTEL_AUTH_TOKEN` - Default: "" (optional)

### Gradle Wrapper
**Location:** `/Users/barrysolomon/Projects/Dash0/mobile-otel/android-app/gradle/wrapper/gradle-wrapper.properties`
```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.9-bin.zip
```

---

## 🎯 What to Test

### 1. Auto-Registration
1. Start gateway
2. Launch Android app (it sends heartbeat every 30s)
3. Check gateway logs for: `"Device X auto-registered successfully"`
4. Open Control Plane UI → Devices → **Fleet** tab
5. Device should appear with:
   - Device ID
   - Device Group: "default"
   - Auto-generated token
   - Registration timestamp

### 2. Authentication Flow
1. Set `OTEL_AUTH_TOKEN="demo_token_12345"` on gateway
2. Launch Android app (already has matching token)
3. Tap "Generate Normal Traffic" or "UI Freeze"
4. Gateway forwards events to collector with Authorization header
5. Collector receives authenticated OTLP requests

### 3. End-to-End Workflow
1. **Control Plane UI** → Workflow Builder
2. Drag nodes from palette to create workflow
3. Click "Publish"
4. **Android App** → Tap demo buttons
5. **Control Plane UI** → Devices → Live Monitor
6. See device heartbeat + triggers updating

---

## ⚠️ Known Issues / To-Do

### Immediate
- [ ] OTEL Collector endpoint is K8s internal address (doesn't work locally)
- [ ] No actual OTEL collector running to receive authenticated events
- [ ] Auth token is hardcoded (not production-ready)

### Optional Enhancements
- [ ] Device-specific auth tokens (vs single shared token)
- [ ] Token refresh mechanism
- [ ] Secrets management integration
- [ ] Support for physical devices (IP configuration)

---

## 🎁 What I Offered to Help With Next

### Option 1: Set Up OTEL Collector with Authentication
Help you deploy and configure an OTEL collector that:
- Accepts authenticated OTLP/gRPC requests
- Validates the Bearer token
- Exports to a backend (Loki, Grafana Cloud, Honeycomb, etc.)

**Recommended Services:**
- **Grafana Cloud** (free tier, easy setup)
- **Honeycomb** (free tier, excellent for debugging)
- **Self-hosted Loki** (if you prefer local)

### Option 2: Configure Production-Ready Secrets Management
Replace hardcoded tokens with:
- **Android:** Encrypted SharedPreferences or Android Keystore
- **Gateway:** HashiCorp Vault, AWS Secrets Manager, or K8s Secrets
- Token rotation and refresh logic

### Option 3: Test Complete Flow
Walk through full end-to-end testing:
1. Device registration verification
2. Config fetching and caching
3. Event buffering and workflow evaluation
4. Selective flushing to collector
5. Data appearing in observability backend

### Option 4: Add More Demo Scenarios
Expand the Android app with:
- Network latency monitoring
- Crash reporting with stack traces
- User session replay triggers
- Custom event types

### Option 5: Deploy to Production
Help with:
- Kubernetes deployment for gateway
- Setting up actual OTEL collector
- Configuring Grafana/Loki for visualization
- CI/CD pipeline setup

---

## 📊 Architecture Overview

```
┌─────────────────┐
│  Android App    │
│  - SDK Init     │
│  - Auth Token   │
│  - Heartbeat    │
└────────┬────────┘
         │ http://10.0.2.2:8080
         │ Bearer: demo_token_12345
         ▼
┌─────────────────┐
│  Gateway :8080  │
│  - Auto-Register│
│  - Auth Passthru│
│  - OTLP Export  │
└────────┬────────┘
         │ OTLP/gRPC
         │ Bearer: demo_token_12345
         ▼
┌─────────────────┐
│ OTEL Collector  │
│  - Validates    │
│  - Processes    │
│  - Exports      │
└────────┬────────┘
         ▼
    Backend (Loki/Grafana)
```

---

## 🔗 Quick Reference

### Important Files Modified This Session

**Gateway:**
- `main.go` - Added OTEL_AUTH_TOKEN env var
- `internal/handlers/handlers.go` - Auto-registration logic (lines 184-215)
- `internal/otel/exporter.go` - Auth token in OTLP headers

**Android App:**
- `app/build.gradle.kts` - BuildConfig for auth token
- `network/GatewayClient.kt` - Authorization interceptor
- `ObservabilitySDK.kt` - Accept and pass auth token
- `MainActivity.kt` - Initialize SDK with token

**Control Plane UI:**
- `components/WorkflowBuilder.tsx` - Added node palette
- `App.css` - Palette styles

### Build Commands
```bash
# Gateway
cd gateway && go build ./...

# Android
cd android-app && ./gradlew assembleDebug

# Control Plane UI
cd control-plane-ui && npm run build
```

### Useful Endpoints
- Gateway Health: http://localhost:8080/health
- Control Plane UI: http://localhost:5173
- Gateway API Base: http://localhost:8080/api

---

## 💡 Tips for Next Session

1. **Before starting:** Run `go run main.go` in gateway dir to verify it starts
2. **Check emulator:** `adb devices` to ensure emulator is running
3. **Fresh start:** `adb logcat -c` to clear Android logs
4. **Monitor everything:** Have 3 terminals open (gateway, UI, adb logcat)
5. **Test auto-register:** Look for device in Fleet view after 30 seconds

---

**Ready to continue with:** Setting up authenticated OTEL collector or any other option above!
