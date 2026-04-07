/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.export

/**
 * Status of a telemetry export attempt.
 *
 * Register a listener via [ExportStatusListener] to receive these updates.
 * Useful for debugging auth failures, network issues, and buffer overflow.
 */
sealed class ExportStatus {
    /** Export succeeded. [eventCount] events were delivered. */
    data class Success(val eventCount: Int) : ExportStatus()

    /** Export failed after all retries. Events remain in buffer. */
    data class Failed(
        val reason: String,
        val eventCount: Int,
        val attempt: Int
    ) : ExportStatus()

    /** Authentication error — token invalid, expired, or blocked. */
    data class AuthError(
        val reason: String,
        val eventCount: Int
    ) : ExportStatus()

    /** Export is being retried. */
    data class Retrying(
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Long
    ) : ExportStatus()
}

/**
 * Listener for export status events.
 *
 * Called on a background thread — do not block or perform heavy UI work.
 *
 * Usage:
 * ```kotlin
 * ExportStatusManager.addListener { status ->
 *     when (status) {
 *         is ExportStatus.AuthError -> Log.e("MyApp", "Auth failed: ${status.reason}")
 *         is ExportStatus.Failed -> analytics.track("export_failed", status.reason)
 *         is ExportStatus.Success -> {} // typically silent
 *         is ExportStatus.Retrying -> {} // optional: show progress
 *     }
 * }
 * ```
 */
fun interface ExportStatusListener {
    fun onExportStatus(status: ExportStatus)
}

/**
 * Global registry for export status listeners.
 *
 * Thread-safe. Listeners are called synchronously on the exporter thread.
 */
object ExportStatusManager {
    private val listeners = mutableListOf<ExportStatusListener>()

    fun addListener(listener: ExportStatusListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: ExportStatusListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    fun clearListeners() {
        synchronized(listeners) { listeners.clear() }
    }

    internal fun notify(status: ExportStatus) {
        val snapshot: List<ExportStatusListener>
        synchronized(listeners) { snapshot = listeners.toList() }
        for (listener in snapshot) {
            try {
                listener.onExportStatus(status)
            } catch (_: Exception) {
                // Don't let listener failures break the export pipeline
            }
        }
    }
}
