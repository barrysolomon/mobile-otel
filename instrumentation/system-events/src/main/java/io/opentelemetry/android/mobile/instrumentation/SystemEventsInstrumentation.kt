// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity

/**
 * Captures Android system event broadcasts as OTel log events.
 *
 * Registers [BroadcastReceiver]s for power, battery, storage, and
 * connectivity-related system intents and emits corresponding OTel
 * log records via the shared [InstrumentationContext] logger.
 *
 * Emitted events:
 * - `device.battery.low`       — [Intent.ACTION_BATTERY_LOW]
 * - `device.battery.okay`      — [Intent.ACTION_BATTERY_OKAY]
 * - `device.power.connected`   — [Intent.ACTION_POWER_CONNECTED]
 * - `device.power.disconnected`— [Intent.ACTION_POWER_DISCONNECTED]
 * - `device.airplane_mode`     — [Intent.ACTION_AIRPLANE_MODE_CHANGED] (with `enabled` attribute)
 * - `device.storage.low`       — [Intent.ACTION_DEVICE_STORAGE_LOW]
 *
 * All events carry [MobileSemconv.SESSION_ID] and [MobileSemconv.VIEW_ID]
 * from the [MobileSessionProvider].
 */
@Incubating
class SystemEventsInstrumentation : MobileInstrumentation {

    override val instrumentationName = "io.opentelemetry.android.mobile.system-events"

    private var logger: Logger? = null
    private var sessionProvider: MobileSessionProvider? = null
    private var application: Application? = null
    private var receiver: BroadcastReceiver? = null

    companion object {
        // Event names
        const val DEVICE_BATTERY_LOW = "device.battery.low"
        const val DEVICE_BATTERY_OKAY = "device.battery.okay"
        const val DEVICE_POWER_CONNECTED = "device.power.connected"
        const val DEVICE_POWER_DISCONNECTED = "device.power.disconnected"
        const val DEVICE_AIRPLANE_MODE = "device.airplane_mode"
        const val DEVICE_STORAGE_LOW = "device.storage.low"
    }

    override fun install(application: Application, context: InstrumentationContext) {
        this.application = application
        this.logger = context.logger(instrumentationName)
        this.sessionProvider = context.sessionProvider

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            @Suppress("DEPRECATION")
            addAction(Intent.ACTION_DEVICE_STORAGE_LOW)
        }

        val br = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_BATTERY_LOW ->
                        emitLog(DEVICE_BATTERY_LOW, Severity.WARN)

                    Intent.ACTION_BATTERY_OKAY ->
                        emitLog(DEVICE_BATTERY_OKAY, Severity.INFO)

                    Intent.ACTION_POWER_CONNECTED ->
                        emitLog(DEVICE_POWER_CONNECTED, Severity.INFO)

                    Intent.ACTION_POWER_DISCONNECTED ->
                        emitLog(DEVICE_POWER_DISCONNECTED, Severity.INFO)

                    Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                        val enabled = intent.getBooleanExtra("state", false)
                        emitLog(
                            DEVICE_AIRPLANE_MODE,
                            Severity.INFO,
                            Attributes.builder()
                                .put("device.airplane_mode.enabled", enabled)
                                .build()
                        )
                    }

                    @Suppress("DEPRECATION")
                    Intent.ACTION_DEVICE_STORAGE_LOW ->
                        emitLog(DEVICE_STORAGE_LOW, Severity.WARN)
                }
            }
        }

        receiver = br
        // API 33+ requires explicit export flag. These are system broadcasts only
        // (not sent by other apps), so RECEIVER_NOT_EXPORTED is the secure choice.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(br, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            application.registerReceiver(br, filter)
        }
    }

    override fun uninstall() {
        receiver?.let { application?.unregisterReceiver(it) }
        receiver = null
        application = null
        logger = null
        sessionProvider = null
    }

    private fun emitLog(name: String, severity: Severity, extra: Attributes = Attributes.empty()) {
        val sp = sessionProvider ?: return
        logger?.logRecordBuilder()
            ?.setBody(name)
            ?.setSeverity(severity)
            ?.setAllAttributes(
                Attributes.builder()
                    .put(MobileSemconv.SESSION_ID, sp.getSessionId())
                    .put(MobileSemconv.VIEW_ID, sp.getViewId())
                    .putAll(extra)
                    .build()
            )
            ?.emit()
    }
}
