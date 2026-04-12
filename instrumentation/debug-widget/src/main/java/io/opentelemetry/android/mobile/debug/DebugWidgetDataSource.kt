// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.debug

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import io.opentelemetry.android.mobile.MobileOtel
import io.opentelemetry.android.mobile.OTelMobile
import io.opentelemetry.android.mobile.export.ExportStatus
import io.opentelemetry.android.mobile.export.ExportStatusListener
import io.opentelemetry.android.mobile.export.ExportStatusManager
import io.opentelemetry.android.mobile.instrumentation.InstrumentationContext

/**
 * Aggregates SDK and device state for the debug widget overlay.
 *
 * Pulls buffer statistics from [MobileOtel], listens to [ExportStatusManager]
 * for export outcomes, reads device health from Android system services, and
 * queries [OTelMobile] for recovery type.
 */
class DebugWidgetDataSource(
    private val instrumentationContext: InstrumentationContext
) {
    /**
     * Snapshot of all data displayed by the debug widget.
     */
    data class WidgetState(
        val ramEvents: Int,
        val ramCapacity: Int,
        val diskEvents: Int,
        val exportStatus: ExportStatus?,
        val recoveryType: String?,
        val batteryPercent: Int,
        val memoryAvailableMb: Long,
        val networkType: String,
        val lastExportTimeMs: Long,
        val sessionId: String
    )

    @Volatile
    private var lastExportStatus: ExportStatus? = null
    @Volatile
    private var lastExportTimeMs: Long = 0L

    private val exportListener = ExportStatusListener { status ->
        lastExportStatus = status
        if (status is ExportStatus.Success) {
            lastExportTimeMs = System.currentTimeMillis()
        }
    }

    fun start() {
        ExportStatusManager.addListener(exportListener)
    }

    fun stop() {
        ExportStatusManager.removeListener(exportListener)
    }

    fun getState(): WidgetState {
        val app = instrumentationContext.application
        val stats = MobileOtel.getBufferStats()

        return WidgetState(
            ramEvents = stats?.ramBufferSize ?: 0,
            ramCapacity = stats?.ramBufferCapacity ?: 5000,
            diskEvents = stats?.diskBufferSize ?: 0,
            exportStatus = lastExportStatus,
            recoveryType = OTelMobile.getLastRecoveryType(),
            batteryPercent = getBatteryPercent(app),
            memoryAvailableMb = getAvailableMemoryMb(app),
            networkType = getNetworkType(app),
            lastExportTimeMs = lastExportTimeMs,
            sessionId = instrumentationContext.sessionProvider.getSessionId()
        )
    }

    private fun getBatteryPercent(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    private fun getAvailableMemoryMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        return mi.availMem / (1024 * 1024)
    }

    private fun getNetworkType(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork ?: return "none"
        val caps = cm.getNetworkCapabilities(network) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }
}
