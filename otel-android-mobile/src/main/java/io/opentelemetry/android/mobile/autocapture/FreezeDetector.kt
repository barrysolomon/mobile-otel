package io.opentelemetry.android.mobile.autocapture

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.opentelemetry.android.mobile.MobileLoggerProvider
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class FreezeDetector(
    private val logger: Logger,
    private val provider: MobileLoggerProvider,
    private val sessionTracker: SessionTracker,
    private val options: AutoCaptureOptions
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "OTel-FreezeWatchdog").apply { isDaemon = true }
    }

    private val tick = Runnable { lastTickAtMs = SystemClock.uptimeMillis() }
    @Volatile
    private var lastTickAtMs: Long = 0

    @Volatile
    private var lastFreezeAtMs: Long = 0

    private var future: ScheduledFuture<*>? = null

    fun start() {
        if (!options.freezeDetectorEnabled) return
        lastTickAtMs = SystemClock.uptimeMillis()
        mainHandler.post(tick)
        future = scheduler.scheduleAtFixedRate(
            { checkFreeze() },
            250,
            250,
            TimeUnit.MILLISECONDS
        )
    }

    fun stop() {
        future?.cancel(false)
        scheduler.shutdownNow()
    }

    private fun checkFreeze() {
        val now = SystemClock.uptimeMillis()
        val delay = now - lastTickAtMs
        if (delay < options.freezeThresholdMs) {
            mainHandler.post(tick)
            return
        }

        if (now - lastFreezeAtMs < options.freezeCooldownMs) {
            return
        }

        lastFreezeAtMs = now
        val screenName = sessionTracker.getCurrentScreenName()

        val attributes = Attributes.builder()
            .put(AttributeKey.stringKey("session.id"), sessionTracker.getSessionId())
            .put(AttributeKey.stringKey("view.id"), sessionTracker.getViewId())
            .put(AttributeKey.longKey("ui.freeze.delay_ms"), delay)
            .apply {
                if (screenName != null) {
                    put(AttributeKey.stringKey("screen.name"), screenName)
                }
            }
            .build()

        logger.logRecordBuilder()
            .setBody("ui.freeze")
            .setSeverity(Severity.WARN)
            .setAllAttributes(attributes)
            .emit()

        // Flush recent window to disk + export when possible
        provider.flushWindowAndClearAll()
    }
}
