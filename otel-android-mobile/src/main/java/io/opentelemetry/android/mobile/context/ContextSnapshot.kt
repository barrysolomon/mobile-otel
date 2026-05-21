/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.context

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import io.opentelemetry.android.mobile.config.MobileConfig
import java.util.Locale
import java.util.TimeZone

/**
 * Privacy-safe device and geo context snapshot.
 *
 * This class captures coarse device and geographic context without collecting PII:
 *
 * **Privacy Safeguards**:
 * - ❌ No GPS coordinates (no latitude/longitude)
 * - ❌ No precise location (no city/street/postal code)
 * - ❌ No device identifiers (no IMEI, Android ID, Advertising ID)
 * - ❌ No user identifiers (no phone number, email, name)
 * - ❌ No network details (no IP address, SSID, carrier name)
 * - ✅ Coarse geo only: country, region (best-effort), timezone
 * - ✅ Non-PII device state: network type, battery, OS version
 *
 * **Performance**:
 * - No network calls
 * - No disk I/O
 * - < 1ms to compute
 * - Safe to call on every policy evaluation
 *
 * @property country ISO 3166-1 alpha-2 country code (e.g., "US", "GB")
 * @property region State/province, best-effort, nullable (e.g., "CA", "Ontario")
 * @property timezone IANA timezone (e.g., "America/Los_Angeles")
 * @property locale BCP-47 language tag (e.g., "en-US")
 * @property appVersion Application version from BuildConfig
 * @property osVersion Android SDK_INT (e.g., 33 = Android 13)
 * @property deviceClass Device form factor: phone/tablet/unknown
 * @property networkType Network connectivity: wifi/cellular/offline/unknown
 * @property batteryState Battery status: charging/low/normal/unknown
 * @property buildChannel Developer-provided channel: prod/beta/internal/unknown
 * @property deviceType Optional user-provided device type (e.g., "smartphone", "tablet", "phablet")
 * @property userRegion Optional user-provided region (e.g., "us", "eu", "asia", "latam")
 * @property ageGroup Optional user-provided age group (e.g., "18-24", "25-34", "35-44")
 * @property tier Optional user-provided subscription tier (e.g., "free", "basic", "premium")
 */
data class ContextSnapshot(
    // Geo (coarse only, privacy-safe)
    val country: String,        // e.g., "US"
    val region: String?,        // e.g., "CA" (best-effort, nullable)
    val timezone: String,       // e.g., "America/Los_Angeles"
    val locale: String,         // e.g., "en-US"

    // Device (non-PII only)
    val appVersion: String,     // e.g., "1.2.3"
    val osVersion: Int,         // e.g., 33 (SDK_INT)
    val deviceClass: String,    // "phone" | "tablet" | "unknown"
    val networkType: String,    // "wifi" | "cellular" | "offline" | "unknown"
    val batteryState: String,   // "charging" | "low" | "normal" | "unknown"
    val buildChannel: String,   // "prod" | "beta" | "internal" | "unknown"

    // User demographics (optional, app-provided)
    val deviceType: String? = null,   // e.g., "smartphone", "tablet", "phablet"
    val userRegion: String? = null,   // e.g., "us", "eu", "asia", "latam"
    val ageGroup: String? = null,     // e.g., "18-24", "25-34", "35-44"
    val tier: String? = null          // e.g., "free", "basic", "premium"
) {
    companion object {
        const val DEVICE_CLASS_PHONE = "phone"
        const val DEVICE_CLASS_TABLET = "tablet"
        const val DEVICE_CLASS_UNKNOWN = "unknown"

        const val NETWORK_WIFI = "wifi"
        const val NETWORK_CELLULAR = "cellular"
        const val NETWORK_OFFLINE = "offline"
        const val NETWORK_UNKNOWN = "unknown"

        const val BATTERY_CHARGING = "charging"
        const val BATTERY_LOW = "low"
        const val BATTERY_NORMAL = "normal"
        const val BATTERY_UNKNOWN = "unknown"

        const val CHANNEL_PROD = "prod"
        const val CHANNEL_BETA = "beta"
        const val CHANNEL_INTERNAL = "internal"
        const val CHANNEL_UNKNOWN = "unknown"
    }
}

/**
 * Provider for creating ContextSnapshot instances.
 *
 * This object is responsible for gathering device and geo context in a privacy-safe way.
 * All data collection follows OpenTelemetry privacy guidelines.
 */
object ContextSnapshotProvider {
    private const val TAG = "ContextSnapshotProvider"

    /**
     * Creates a context snapshot for the current device state.
     *
     * This is a lightweight operation (< 1ms) suitable for frequent calls.
     *
     * @param context Android application context
     * @param config Mobile configuration
     * @return ContextSnapshot with current device/geo state
     */
    fun getSnapshot(context: Context, config: MobileConfig): ContextSnapshot {
        // SR-011 + SR-024: only read demographics when the app explicitly
        // opts in via MobileConfig.userContextPrefsName. The SDK no longer
        // bakes the demo app's prefs file name into library code, and apps
        // that don't need demographics never touch SharedPreferences here.
        val prefs = config.userContextPrefsName?.let {
            context.getSharedPreferences(it, Context.MODE_PRIVATE)
        }

        return ContextSnapshot(
            // Geo (coarse, privacy-safe)
            country = getCountry(),
            region = getRegion(),
            timezone = getTimezone(),
            locale = getLocale(),

            // Device (non-PII)
            appVersion = config.serviceVersion,
            osVersion = getOsVersion(),
            deviceClass = getDeviceClass(context),
            networkType = getNetworkType(context),
            batteryState = getBatteryState(context),
            buildChannel = config.buildChannel ?: ContextSnapshot.CHANNEL_UNKNOWN,

            // User demographics — opt-in via MobileConfig.userContextPrefsName.
            deviceType = prefs?.getString("user_device_type", null),
            userRegion = prefs?.getString("user_region", null),
            ageGroup = prefs?.getString("user_age_group", null),
            tier = prefs?.getString("user_tier", null),
        )
    }

    /**
     * Gets country code from default locale.
     *
     * Returns ISO 3166-1 alpha-2 country code (e.g., "US", "GB").
     * This is public information derived from device locale setting.
     *
     * @return Country code, or "" if unavailable
     */
    private fun getCountry(): String {
        return try {
            Locale.getDefault().country.uppercase()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get country", e)
            ""
        }
    }

    /**
     * Gets region/state from timezone (best-effort).
     *
     * This is a heuristic: we extract the region from the timezone ID.
     * For example, "America/Los_Angeles" -> "Los_Angeles" (not accurate state, but unique identifier).
     *
     * **Note**: Android doesn't provide a standard "state/province" API.
     * This is best-effort and may be null or inaccurate.
     *
     * @return Region identifier or null if unavailable
     */
    private fun getRegion(): String? {
        return try {
            val tz = TimeZone.getDefault().id
            // Extract region from timezone (e.g., "America/Los_Angeles" -> "Los_Angeles")
            if (tz.contains("/")) {
                tz.split("/").lastOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get region", e)
            null
        }
    }

    /**
     * Gets IANA timezone ID.
     *
     * Returns timezone like "America/Los_Angeles", "Europe/London", etc.
     * This is public information from device settings.
     *
     * @return Timezone ID, or "UTC" if unavailable
     */
    private fun getTimezone(): String {
        return try {
            TimeZone.getDefault().id
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get timezone", e)
            "UTC"
        }
    }

    /**
     * Gets BCP-47 locale.
     *
     * Returns locale like "en-US", "es-ES", "ja-JP", etc.
     * This is public information from device settings.
     *
     * @return Locale string
     */
    private fun getLocale(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Locale.getDefault().toLanguageTag()
            } else {
                "${Locale.getDefault().language}-${Locale.getDefault().country}"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get locale", e)
            "en-US"
        }
    }

    /**
     * Gets Android OS version (SDK_INT).
     *
     * Returns numeric SDK level (e.g., 33 = Android 13).
     *
     * @return SDK_INT value
     */
    private fun getOsVersion(): Int {
        return Build.VERSION.SDK_INT
    }

    /**
     * Gets device class based on screen size.
     *
     * Uses heuristic: smallest screen width to classify phone vs tablet.
     * - sw < 600dp: phone
     * - sw >= 600dp: tablet
     *
     * @param context Android context
     * @return "phone", "tablet", or "unknown"
     */
    private fun getDeviceClass(context: Context): String {
        return try {
            val config = context.resources.configuration
            val smallestScreenWidthDp = config.smallestScreenWidthDp
            when {
                smallestScreenWidthDp >= 600 -> ContextSnapshot.DEVICE_CLASS_TABLET
                smallestScreenWidthDp > 0 -> ContextSnapshot.DEVICE_CLASS_PHONE
                else -> ContextSnapshot.DEVICE_CLASS_UNKNOWN
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get device class", e)
            ContextSnapshot.DEVICE_CLASS_UNKNOWN
        }
    }

    /**
     * Gets current network type.
     *
     * Returns:
     * - "wifi" - Connected via WiFi
     * - "cellular" - Connected via mobile data
     * - "offline" - No connectivity
     * - "unknown" - Unable to determine
     *
     * **Privacy**: We do NOT collect SSID, IP address, or carrier info.
     *
     * @param context Android context
     * @return Network type string
     */
    @SuppressLint("MissingPermission") // Permission declared in app manifest, not library
    private fun getNetworkType(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return ContextSnapshot.NETWORK_UNKNOWN

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return ContextSnapshot.NETWORK_OFFLINE
                val capabilities = cm.getNetworkCapabilities(network)
                    ?: return ContextSnapshot.NETWORK_UNKNOWN

                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ContextSnapshot.NETWORK_WIFI
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ContextSnapshot.NETWORK_CELLULAR
                    else -> ContextSnapshot.NETWORK_UNKNOWN
                }
            } else {
                @Suppress("DEPRECATION")
                val activeNetwork = cm.activeNetworkInfo
                when {
                    activeNetwork == null -> ContextSnapshot.NETWORK_OFFLINE
                    activeNetwork.type == ConnectivityManager.TYPE_WIFI -> ContextSnapshot.NETWORK_WIFI
                    activeNetwork.type == ConnectivityManager.TYPE_MOBILE -> ContextSnapshot.NETWORK_CELLULAR
                    else -> ContextSnapshot.NETWORK_UNKNOWN
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get network type", e)
            ContextSnapshot.NETWORK_UNKNOWN
        }
    }

    /**
     * Gets battery state.
     *
     * Returns:
     * - "charging" - Battery is charging
     * - "low" - Battery level < 15%
     * - "normal" - Battery level >= 15% and not charging
     * - "unknown" - Unable to determine
     *
     * @param context Android context
     * @return Battery state string
     */
    private fun getBatteryState(context: Context): String {
        return try {
            val batteryIntent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )

            if (batteryIntent == null) {
                return ContextSnapshot.BATTERY_UNKNOWN
            }

            // Check if charging
            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

            if (isCharging) {
                return ContextSnapshot.BATTERY_CHARGING
            }

            // Check battery level
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                val batteryPct = (level.toFloat() / scale.toFloat() * 100).toInt()
                return if (batteryPct < 15) {
                    ContextSnapshot.BATTERY_LOW
                } else {
                    ContextSnapshot.BATTERY_NORMAL
                }
            }

            ContextSnapshot.BATTERY_UNKNOWN
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get battery state", e)
            ContextSnapshot.BATTERY_UNKNOWN
        }
    }
}
