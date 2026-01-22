# Remote Management Quick Start Guide

**Purpose**: Step-by-step guide to implement remote configuration management
**Audience**: Android developers implementing the management features
**Status**: Implementation guide based on architecture

---

## Phase 1: Core Infrastructure (2-3 days)

### Step 1: Add Remote Config Models

**File**: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/RemoteConfig.kt`

```kotlin
data class RemoteConfig(
    val version: String,
    val updatedAt: String,
    val otelConfig: OtelConfigUpdate,
    val environmentVars: Map<String, String>,
    val exportPolicies: List<ExportPolicy>,
    val pollingConfig: PollingConfig,
    val featureFlags: Map<String, Boolean>
)

data class OtelConfigUpdate(
    val serviceName: String?,
    val serviceVersion: String?,
    val protocol: String?,
    val collectorEndpoint: String?,
    val authToken: String?,
    val dataset: String?,
    val ramBufferSize: Int?,
    val diskBufferMb: Int?,
    val diskBufferTtlHours: Int?
    // ... other fields as Optional
)

data class ExportPolicy(
    val id: String,
    val enabled: Boolean,
    val priority: Int,
    val match: PolicyMatch,
    val actions: List<PolicyAction>
)

data class PollingConfig(
    val intervalSeconds: Int,
    val retryOnFailure: Boolean,
    val maxRetries: Int
)
```

### Step 2: Create Config Fetch Client

**File**: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/ConfigFetchClient.kt`

```kotlin
class ConfigFetchClient(
    private val configUrl: String,
    private val deviceToken: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetchConfig(currentVersion: String?): RemoteConfig? {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(configUrl)
                .header("Authorization", "Bearer $deviceToken")
                .apply {
                    if (currentVersion != null) {
                        header("If-None-Match", currentVersion)
                    }
                }
                .build()

            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> parseConfig(response.body!!.string())
                    304 -> null // Config unchanged
                    else -> throw IOException("Config fetch failed: ${response.code}")
                }
            }
        }
    }

    private fun parseConfig(json: String): RemoteConfig {
        val gson = Gson()
        return gson.fromJson(json, RemoteConfig::class.java)
    }
}
```

### Step 3: Implement Config Application Logic

**File**: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/RemoteConfigManager.kt`

```kotlin
object RemoteConfigManager {
    private const val PREFS_NAME = "remote_config"
    private const val KEY_CURRENT_VERSION = "current_version"
    private const val KEY_CONFIG_URL = "config_url"
    private const val KEY_DEVICE_TOKEN = "device_token"

    fun applyConfig(context: Context, config: RemoteConfig) {
        try {
            // Apply OTEL configuration
            config.otelConfig.let { update ->
                update.protocol?.let { ConfigManager.saveProtocol(context, it) }
                update.authToken?.let { ConfigManager.saveAuthToken(context, it) }
                update.dataset?.let { ConfigManager.saveDataset(context, it) }
                // ... apply other fields
            }

            // Apply environment variables
            config.environmentVars.forEach { (key, value) ->
                EnvironmentVarManager.set(context, key, value)
            }

            // Apply export policies
            PolicyManager.updatePolicies(context, config.exportPolicies)

            // Apply feature flags
            FeatureFlagManager.updateFlags(context, config.featureFlags)

            // Update polling interval
            savePollingInterval(context, config.pollingConfig.intervalSeconds)

            // Save version
            saveCurrentVersion(context, config.version)

            Log.i(TAG, "Config applied successfully: version=${config.version}")

            // Notify listeners
            broadcastConfigChanged(context)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply config", e)
            throw e
        }
    }

    fun getCurrentVersion(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CURRENT_VERSION, null)
    }

    fun saveCurrentVersion(context: Context, version: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CURRENT_VERSION, version)
            .apply()
    }

    fun getPollingInterval(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("polling_interval", 300)
    }

    private fun broadcastConfigChanged(context: Context) {
        val intent = Intent("io.opentelemetry.CONFIG_CHANGED")
        context.sendBroadcast(intent)
    }
}
```

---

## Phase 2: Background Service (1-2 days)

### Step 4: Create Config Fetch Service

**File**: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/config/ConfigFetchService.kt`

```kotlin
class ConfigFetchService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null
    private lateinit var configClient: ConfigFetchClient

    override fun onCreate() {
        super.onCreate()

        val configUrl = RemoteConfigManager.getConfigUrl(this)
        val deviceToken = RemoteConfigManager.getDeviceToken(this)

        if (configUrl != null && deviceToken != null) {
            configClient = ConfigFetchClient(configUrl, deviceToken)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (::configClient.isInitialized) {
            startPolling()
        } else {
            Log.w(TAG, "Config fetch disabled: missing URL or token")
        }
        return START_STICKY
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    fetchAndApplyConfig()
                } catch (e: Exception) {
                    Log.e(TAG, "Config fetch failed", e)
                }

                val interval = RemoteConfigManager.getPollingInterval(this@ConfigFetchService)
                delay(interval * 1000L)
            }
        }
    }

    private suspend fun fetchAndApplyConfig() {
        try {
            val currentVersion = RemoteConfigManager.getCurrentVersion(this)
            val newConfig = configClient.fetchConfig(currentVersion)

            if (newConfig != null) {
                RemoteConfigManager.applyConfig(this, newConfig)
                reportConfigApplied(newConfig.version, success = true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Config fetch error", e)
            val currentVersion = RemoteConfigManager.getCurrentVersion(this)
            if (currentVersion != null) {
                reportConfigApplied(currentVersion, success = false, error = e.message)
            }
        }
    }

    private fun reportConfigApplied(version: String, success: Boolean, error: String? = null) {
        // TODO: Report to management server
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ConfigFetchService"
    }
}
```

### Step 5: Add Service to Manifest

**File**: `otel-android-mobile/src/main/AndroidManifest.xml`

```xml
<service
    android:name=".config.ConfigFetchService"
    android:enabled="true"
    android:exported="false" />
```

### Step 6: Start Service from MobileLoggerProvider

**File**: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/MobileLoggerProvider.kt`

```kotlin
fun enableRemoteConfig(configUrl: String, deviceToken: String) {
    RemoteConfigManager.saveConfigUrl(context, configUrl)
    RemoteConfigManager.saveDeviceToken(context, deviceToken)

    val intent = Intent(context, ConfigFetchService::class.java)
    context.startService(intent)

    Log.i(TAG, "Remote config enabled: $configUrl")
}
```

---

## Phase 3: Dynamic Policy Updates (2-3 days)

### Step 7: Policy Manager

**File**: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/PolicyManager.kt`

```kotlin
object PolicyManager {
    private val policies = mutableListOf<ExportPolicy>()
    private val listeners = mutableListOf<PolicyChangeListener>()

    fun updatePolicies(context: Context, newPolicies: List<ExportPolicy>) {
        synchronized(policies) {
            policies.clear()
            policies.addAll(newPolicies.filter { it.enabled }.sortedByDescending { it.priority })
        }

        savePolicies(context, newPolicies)
        notifyListeners()

        Log.i(TAG, "Policies updated: ${policies.size} active policies")
    }

    fun evaluatePolicies(logRecord: LogRecordData): List<PolicyAction> {
        return synchronized(policies) {
            policies.flatMap { policy ->
                if (policy.match.matches(logRecord)) {
                    policy.actions
                } else {
                    emptyList()
                }
            }
        }
    }

    fun addListener(listener: PolicyChangeListener) {
        listeners.add(listener)
    }

    private fun notifyListeners() {
        listeners.forEach { it.onPoliciesChanged() }
    }

    interface PolicyChangeListener {
        fun onPoliciesChanged()
    }
}
```

### Step 8: Update MobileLogRecordProcessor

**File**: `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/buffering/MobileLogRecordProcessor.kt`

```kotlin
init {
    // Register for policy changes
    PolicyManager.addListener(object : PolicyManager.PolicyChangeListener {
        override fun onPoliciesChanged() {
            Log.i(TAG, "Policies reloaded dynamically")
        }
    })

    // Register for config changes
    val filter = IntentFilter("io.opentelemetry.CONFIG_CHANGED")
    context.registerReceiver(object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            reloadConfiguration()
        }
    }, filter)
}

private fun reloadConfiguration() {
    val newConfig = ConfigManager.loadConfig(context)
    // Apply new configuration
    // Reinitialize exporters if needed
    Log.i(TAG, "Configuration reloaded")
}
```

---

## Phase 4: Settings UI Integration (1 day)

### Step 9: Add Remote Config Settings

**File**: `examples/demo-app/android/src/main/res/layout/activity_settings.xml`

Add section:

```xml
<!-- Remote Configuration -->
<TextView
    android:text="Remote Configuration"
    android:textSize="18sp"
    android:textStyle="bold" />

<CheckBox
    android:id="@+id/checkboxEnableRemoteConfig"
    android:text="Enable remote configuration management" />

<EditText
    android:id="@+id/editConfigServerUrl"
    android:hint="e.g., https://config.example.com/api/v1/config"
    android:enabled="false" />

<EditText
    android:id="@+id/editDeviceToken"
    android:hint="Device token"
    android:inputType="textPassword"
    android:enabled="false" />
```

### Step 10: Wire Up Settings

**File**: `examples/demo-app/android/src/main/java/io/opentelemetry/android/demo/SettingsActivity.kt`

```kotlin
private lateinit var checkboxEnableRemoteConfig: CheckBox
private lateinit var editConfigServerUrl: EditText
private lateinit var editDeviceToken: EditText

// In onCreate
checkboxEnableRemoteConfig.setOnCheckedChangeListener { _, isChecked ->
    editConfigServerUrl.isEnabled = isChecked
    editDeviceToken.isEnabled = isChecked
}

// In saveConfiguration
if (checkboxEnableRemoteConfig.isChecked) {
    val configUrl = editConfigServerUrl.text.toString().trim()
    val deviceToken = editDeviceToken.text.toString().trim()

    if (configUrl.isNotBlank() && deviceToken.isNotBlank()) {
        loggerProvider.enableRemoteConfig(configUrl, deviceToken)
    }
}
```

---

## Phase 5: Testing (2-3 days)

### Step 11: Create Mock Config Server

**File**: `test-server/mock-config-server.js` (Node.js example)

```javascript
const express = require('express');
const app = express();

let currentConfig = {
  version: "1.0.0",
  updated_at: new Date().toISOString(),
  otel_config: {
    collector_endpoint: "http://10.0.2.2:4317",
    auth_token: "test_token",
    ram_buffer_size: 5000
  },
  environment_vars: {
    "FEATURE_FLAG_NEW_UI": "true"
  },
  export_policies: [],
  polling_config: {
    interval_seconds: 60
  }
};

app.get('/api/v1/config/:device_id', (req, res) => {
  const ifNoneMatch = req.headers['if-none-match'];

  if (ifNoneMatch === currentConfig.version) {
    res.status(304).send();
  } else {
    res.json(currentConfig);
  }
});

app.put('/admin/api/v1/config', express.json(), (req, res) => {
  currentConfig = {
    ...currentConfig,
    ...req.body,
    version: `${parseInt(currentConfig.version) + 1}.0.0`
  };
  res.json({ version: currentConfig.version });
});

app.listen(8080, () => console.log('Mock config server running on :8080'));
```

### Step 12: Integration Tests

```kotlin
@Test
fun testConfigFetch() = runTest {
    val client = ConfigFetchClient(
        "http://localhost:8080/api/v1/config/test-device",
        "test-token"
    )

    val config = client.fetchConfig(null)
    assertNotNull(config)
    assertEquals("1.0.0", config.version)
}

@Test
fun testConfigApplication() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val config = RemoteConfig(/* ... */)

    RemoteConfigManager.applyConfig(context, config)

    val applied = ConfigManager.loadConfig(context)
    assertEquals(config.otelConfig.collectorEndpoint, applied.collectorEndpoint)
}
```

---

## Phase 6: Documentation (1 day)

### Step 13: Update User Documentation

Add to QUICKSTART.md:

```markdown
## Remote Configuration (Optional)

To enable remote configuration management:

1. Deploy a configuration management server (see REMOTE_MANAGEMENT_ARCHITECTURE.md)
2. Register your device to get a device token
3. In Settings → Enable remote configuration
4. Enter your config server URL and device token
5. Save and restart

The app will now poll for configuration updates every 5 minutes.
```

---

## Quick Implementation Checklist

- [ ] Add RemoteConfig data models
- [ ] Create ConfigFetchClient for HTTP requests
- [ ] Implement RemoteConfigManager for applying configs
- [ ] Create ConfigFetchService background service
- [ ] Add service to AndroidManifest
- [ ] Implement PolicyManager for dynamic policies
- [ ] Update MobileLogRecordProcessor to listen for config changes
- [ ] Add remote config settings to Settings UI
- [ ] Wire up settings in SettingsActivity
- [ ] Create mock config server for testing
- [ ] Write integration tests
- [ ] Update documentation

---

## Estimated Timeline

- **Phase 1** (Models & Client): 2-3 days
- **Phase 2** (Background Service): 1-2 days
- **Phase 3** (Dynamic Policies): 2-3 days
- **Phase 4** (Settings UI): 1 day
- **Phase 5** (Testing): 2-3 days
- **Phase 6** (Documentation): 1 day

**Total**: 9-13 days of development time

---

## Dependencies to Add

```gradle
// In otel-android-mobile/build.gradle.kts
dependencies {
    // HTTP client (already have OkHttp)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines (already have)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // WorkManager for reliable background work (optional)
    implementation("androidx.work:work-runtime-ktx:2.9.0")
}
```

---

**Status**: Ready for implementation
**Priority**: Medium (can be added after Phase 4 testing complete)
**Impact**: Enables enterprise fleet management capabilities
