package io.opentelemetry.android.mobile.metrics

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.annotation.RequiresPermission
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Collects device health metrics on-demand for export during crashes or trigger events.
 *
 * Follows OpenTelemetry semantic conventions for device and system metrics.
 * Designed for mobile: lightweight, fast capture, privacy-safe.
 *
 * Usage:
 * ```kotlin
 * val collector = DeviceMetricsCollector(context, meter, config)
 *
 * // Capture all configured metrics
 * collector.captureMetrics(CaptureReason.CRASH)
 *
 * // Query specific metrics
 * val memoryMb = collector.getAvailableMemoryMb()
 * val batteryPercent = collector.getBatteryLevel()
 * ```
 */
class DeviceMetricsCollector(
    private val context: Context,
    private val meter: Meter,
    private val config: DeviceMetricsConfig
) {
    private val lastCaptureTime = AtomicLong(0)

    /**
     * Captures all configured metrics based on configuration.
     *
     * @param reason Why metrics are being captured
     * @param force Force capture even if rate-limited
     * @return true if metrics were captured, false if rate-limited
     */
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    fun captureMetrics(reason: CaptureReason, force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        val lastCapture = lastCaptureTime.get()
        val rateLimitMs = 60 * 1000L  // 60 seconds default

        // Check rate limit unless forced
        if (!force && (now - lastCapture) < rateLimitMs) {
            return false
        }

        lastCaptureTime.set(now)

        val baseAttributes = Attributes.builder()
            .put(AttributeKey.stringKey("capture.reason"), reason.name.lowercase())
            .put(AttributeKey.longKey("capture.timestamp_ms"), now)
            .build()

        // Capture configured metrics
        if (config.captureMemory) captureMemoryMetrics(baseAttributes)
        if (config.captureBattery) captureBatteryMetrics(baseAttributes)
        if (config.captureCpu) captureCpuMetrics(baseAttributes)
        if (config.captureNetwork) captureNetworkMetrics(baseAttributes)
        if (config.captureStorage) captureStorageMetrics(baseAttributes)
        if (config.captureThermal) captureThermalMetrics(baseAttributes)
        if (config.captureDisplay) captureDisplayMetrics(baseAttributes)
        if (config.captureSystem) captureSystemMetrics(baseAttributes)
        if (config.captureApp) captureAppMetrics(baseAttributes)
        if (config.captureLocation) captureLocationMetrics(baseAttributes)

        return true
    }

    /**
     * Memory metrics: used, available, total, low memory state.
     */
    private fun captureMemoryMetrics(attributes: Attributes) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val availableMb = memInfo.availMem / (1024 * 1024)
        val totalMb = memInfo.totalMem / (1024 * 1024)
        val usedMb = totalMb - availableMb

        // Record memory metrics as counters (snapshot metrics)
        meter.counterBuilder("device.memory.used_mb")
            .setDescription("Memory currently used by app (MB)")
            .build()
            .add(usedMb, attributes)

        meter.counterBuilder("device.memory.available_mb")
            .setDescription("Memory available to app (MB)")
            .build()
            .add(availableMb, attributes)

        // Low memory flag
        meter.counterBuilder("device.memory.low_memory")
            .setDescription("Low memory state detected")
            .build()
            .add(if (memInfo.lowMemory) 1 else 0, attributes)
    }

    /**
     * Battery metrics: level, charging state, health, temperature.
     */
    private fun captureBatteryMetrics(attributes: Attributes) {
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryPercent = if (scale > 0) (level * 100 / scale) else -1

            val temperature = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            val tempCelsius = if (temperature > 0) temperature / 10.0 else -1.0

            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            val health = batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)

            // Record battery metrics as counters (snapshot metrics)
            val batteryAttributes = Attributes.builder()
                .putAll(attributes)
                .put(AttributeKey.booleanKey("battery.charging"), isCharging)
                .put(AttributeKey.stringKey("battery.health"), getBatteryHealthString(health))
                .build()

            if (batteryPercent >= 0) {
                meter.counterBuilder("device.battery.level_percent")
                    .setDescription("Battery level percentage (0-100)")
                    .build()
                    .add(batteryPercent.toLong(), batteryAttributes)
            }

            if (tempCelsius > 0) {
                meter.counterBuilder("device.battery.temperature_celsius")
                    .setDescription("Battery temperature in Celsius")
                    .ofDoubles()
                    .build()
                    .add(tempCelsius, batteryAttributes)
            }
        }
    }

    /**
     * CPU metrics: usage, core count, architecture.
     */
    private fun captureCpuMetrics(attributes: Attributes) {
        // CPU core count
        val coreCount = Runtime.getRuntime().availableProcessors()

        // CPU usage (simplified - would need /proc/stat for accuracy)
        // For now, just report core count and architecture
        val cpuAttributes = Attributes.builder()
            .putAll(attributes)
            .put(AttributeKey.longKey("cpu.core_count"), coreCount.toLong())
            .put(AttributeKey.stringKey("cpu.architecture"), Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            .build()

        meter.counterBuilder("device.cpu.info")
            .build()
            .add(1, cpuAttributes)
    }

    /**
     * Network metrics: type, connection state, capabilities.
     */
    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    private fun captureNetworkMetrics(attributes: Attributes) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }

        val networkType = when {
            capabilities == null -> "none"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "unknown"
        }

        val isConnected = capabilities != null

        val networkAttributes = Attributes.builder()
            .putAll(attributes)
            .put(AttributeKey.stringKey("network.type"), networkType)
            .put(AttributeKey.booleanKey("network.connected"), isConnected)
            .build()

        meter.counterBuilder("device.network.snapshot")
            .build()
            .add(if (isConnected) 1 else 0, networkAttributes)
    }

    /**
     * Storage metrics: used, available, cache size.
     */
    private fun captureStorageMetrics(attributes: Attributes) {
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalBytes = stat.blockCountLong * stat.blockSizeLong
        val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
        val usedBytes = totalBytes - availableBytes

        val usedMb = usedBytes / (1024 * 1024)
        val availableMb = availableBytes / (1024 * 1024)

        // Record storage metrics as counters (snapshot metrics)
        meter.counterBuilder("device.storage.used_mb")
            .setDescription("Internal storage used (MB)")
            .build()
            .add(usedMb, attributes)

        meter.counterBuilder("device.storage.available_mb")
            .build()
            .add(availableMb, attributes)

        // Cache size
        val cacheDir = context.cacheDir
        val cacheSize = getFolderSize(cacheDir)
        val cacheMb = cacheSize / (1024 * 1024)

        meter.counterBuilder("device.storage.cache_mb")
            .build()
            .add(cacheMb, attributes)
    }

    /**
     * Thermal metrics: throttling state.
     */
    private fun captureThermalMetrics(attributes: Attributes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val thermalStatus = powerManager.currentThermalStatus

            // Map to 0-4 scale
            val thermalLevel = when (thermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> 0L
                PowerManager.THERMAL_STATUS_LIGHT -> 1L
                PowerManager.THERMAL_STATUS_MODERATE -> 2L
                PowerManager.THERMAL_STATUS_SEVERE -> 3L
                PowerManager.THERMAL_STATUS_CRITICAL,
                PowerManager.THERMAL_STATUS_EMERGENCY,
                PowerManager.THERMAL_STATUS_SHUTDOWN -> 4L
                else -> 0L
            }

            // Record thermal state as counter (snapshot metric)
            meter.counterBuilder("device.thermal.state")
                .setDescription("Thermal throttling state (0=none, 1=light, 2=moderate, 3=severe, 4=critical)")
                .build()
                .add(thermalLevel, attributes)
        }
    }

    /**
     * Display metrics: orientation, resolution, screen state.
     */
    private fun captureDisplayMetrics(attributes: Attributes) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(displayMetrics)

        val displayAttributes = Attributes.builder()
            .putAll(attributes)
            .put(AttributeKey.longKey("display.width_px"), displayMetrics.widthPixels.toLong())
            .put(AttributeKey.longKey("display.height_px"), displayMetrics.heightPixels.toLong())
            .put(AttributeKey.doubleKey("display.density"), displayMetrics.density.toDouble())
            .build()

        meter.counterBuilder("device.display.info")
            .build()
            .add(1, displayAttributes)
    }

    /**
     * System metrics: OS version, API level, uptime.
     */
    private fun captureSystemMetrics(attributes: Attributes) {
        val systemAttributes = Attributes.builder()
            .putAll(attributes)
            .put(AttributeKey.stringKey("os.version"), Build.VERSION.RELEASE)
            .put(AttributeKey.longKey("os.api_level"), Build.VERSION.SDK_INT.toLong())
            .put(AttributeKey.stringKey("device.model"), Build.MODEL)
            .put(AttributeKey.stringKey("device.manufacturer"), Build.MANUFACTURER)
            .put(AttributeKey.longKey("system.uptime_ms"), android.os.SystemClock.elapsedRealtime())
            .build()

        meter.counterBuilder("device.system.info")
            .build()
            .add(1, systemAttributes)
    }

    /**
     * App metrics: version, foreground state, install time.
     */
    private fun captureAppMetrics(attributes: Attributes) {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionName = packageInfo.versionName ?: "unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

        val appAttributes = Attributes.builder()
            .putAll(attributes)
            .put(AttributeKey.stringKey("app.version"), versionName)
            .put(AttributeKey.longKey("app.version_code"), versionCode)
            .put(AttributeKey.longKey("app.install_time_ms"), packageInfo.firstInstallTime)
            .put(AttributeKey.longKey("app.update_time_ms"), packageInfo.lastUpdateTime)
            .build()

        meter.counterBuilder("device.app.info")
            .build()
            .add(1, appAttributes)
    }

    /**
     * Location metrics: coarse location (country, timezone) - privacy-safe.
     */
    private fun captureLocationMetrics(attributes: Attributes) {
        // Only capture coarse, privacy-safe location data
        val timezone = java.util.TimeZone.getDefault().id
        val country = java.util.Locale.getDefault().country

        val locationAttributes = Attributes.builder()
            .putAll(attributes)
            .put(AttributeKey.stringKey("geo.timezone"), timezone)
            .put(AttributeKey.stringKey("geo.country"), country)
            .build()

        meter.counterBuilder("device.location.info")
            .build()
            .add(1, locationAttributes)
    }

    // Helper methods

    fun getAvailableMemoryMb(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.availMem / (1024 * 1024)
    }

    fun getBatteryLevel(): Int {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (scale > 0) (level * 100 / scale) else -1
        } else {
            -1
        }
    }

    private fun getBatteryHealthString(health: Int): String {
        return when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        }
    }

    private fun getFolderSize(file: File): Long {
        var size: Long = 0
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                size += getFolderSize(child)
            }
        } else {
            size = file.length()
        }
        return size
    }
}

/**
 * Reason why device metrics are being captured.
 */
enum class CaptureReason {
    APP_START,
    FORCE_CLOSE,
    CRASH,
    ERROR,
    MANUAL_FLUSH,
    SCHEDULED_FLUSH,
    WORKFLOW_TRIGGER,
    MANUAL_CAPTURE
}
