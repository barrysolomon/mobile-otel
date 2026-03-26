// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Captures a screenshot of the current activity's window at trigger points and emits
 * it as an OTel log record with base64-encoded image data.
 *
 * **Trigger modes:**
 * - Manual: call [captureScreenshot] from your code at any point.
 * - On error: when [ScreenshotConfig.captureOnError] is true, an uncaught exception
 *   handler is installed that captures before the app crashes.
 *
 * **Privacy:** when [ScreenshotConfig.redactTextViews] is true, solid rectangles are
 * drawn over all [TextView] bounds after capture, masking text content.
 *
 * **Transport:** the image is emitted as a data URL (`data:image/jpeg;base64,…`) in
 * the `mobile.screenshot.data_url` log attribute. This is directly renderable in any
 * browser or dashboard UI. A [ScreenshotConfig.maxPayloadKb] guard silently drops
 * captures that exceed the configured size limit.
 *
 * **Rate limiting:** at most [ScreenshotConfig.maxCapturesPerMinute] captures per minute.
 */
@Incubating
class ScreenshotInstrumentation(
    private val config: ScreenshotConfig = ScreenshotConfig()
) : MobileInstrumentation {

    override val instrumentationName = "io.opentelemetry.android.mobile.screenshot"

    private var ctx: InstrumentationContext? = null
    private var logger: Logger? = null
    private var tracer: Tracer? = null
    private var uiTelemetryMode: UiTelemetryMode = UiTelemetryMode.EVENTS
    private var application: Application? = null
    private var callbacks: Application.ActivityLifecycleCallbacks? = null
    private var previousExceptionHandler: Thread.UncaughtExceptionHandler? = null

    @Volatile private var currentActivity: WeakReference<Activity>? = null

    private val rateLimiter = RateLimiter(config.maxCapturesPerMinute)

    // Background thread for PixelCopy callbacks.
    private var pixelCopyThread: HandlerThread? = null
    private var pixelCopyHandler: Handler? = null

    private val redactPaint = Paint().apply {
        color = Color.DKGRAY
        style = Paint.Style.FILL
    }

    override fun install(application: Application, context: InstrumentationContext) {
        if (!config.enabled) return

        this.application = application
        this.ctx = context
        this.logger = context.logger(instrumentationName)
        this.tracer = context.tracer(instrumentationName)
        this.uiTelemetryMode = context.uiTelemetryMode

        // Start background handler thread for PixelCopy.
        val thread = HandlerThread("OTel-ScreenshotCapture").apply { start() }
        pixelCopyThread = thread
        pixelCopyHandler = Handler(thread.looper)

        // Track the current foreground activity.
        val cb = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                currentActivity = WeakReference(activity)
                if (config.captureOnScreenView) {
                    // Delay to let the screen finish rendering before capture.
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (currentActivity?.get() === activity) {
                            captureScreenshot("screen_view")
                        }
                    }, config.screenViewDelayMs)
                }
            }
            override fun onActivityPaused(activity: Activity) {
                if (currentActivity?.get() === activity) {
                    currentActivity = null
                }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        callbacks = cb
        application.registerActivityLifecycleCallbacks(cb)

        // Auto-capture on uncaught exceptions.
        if (config.captureOnError) {
            previousExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    captureScreenshotSync("error")
                } catch (_: Exception) {
                    // Best-effort — don't let screenshot capture prevent the crash handler chain.
                }
                previousExceptionHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    override fun uninstall() {
        callbacks?.let { application?.unregisterActivityLifecycleCallbacks(it) }
        callbacks = null

        if (config.captureOnError && previousExceptionHandler != null) {
            Thread.setDefaultUncaughtExceptionHandler(previousExceptionHandler)
            previousExceptionHandler = null
        }

        pixelCopyThread?.quitSafely()
        pixelCopyThread = null
        pixelCopyHandler = null

        currentActivity = null
        application = null
        ctx = null
        logger = null
        tracer = null
        uiTelemetryMode = UiTelemetryMode.EVENTS
        rateLimiter.reset()
    }

    /**
     * Capture a screenshot of the current foreground activity.
     *
     * @param trigger Describes what triggered the capture (e.g., "manual", "error", "freeze").
     *   Recorded as the `mobile.screenshot.trigger` attribute.
     */
    fun captureScreenshot(trigger: String = "manual") {
        if (!config.enabled) return
        if (!rateLimiter.tryAcquire()) {
            Log.d(TAG, "Screenshot rate limit exceeded, skipping capture")
            return
        }

        val activity = currentActivity?.get() ?: run {
            Log.d(TAG, "No foreground activity, skipping capture")
            return
        }

        captureFromActivity(activity, trigger)
    }

    /**
     * Synchronous capture — used from crash handler where we can't afford async.
     * Falls back to View.draw() (no PixelCopy) for reliability.
     */
    private fun captureScreenshotSync(trigger: String) {
        if (!config.enabled) return
        if (!rateLimiter.tryAcquire()) return

        val activity = currentActivity?.get() ?: return

        try {
            val rootView = activity.window.decorView.rootView
            if (rootView.width <= 0 || rootView.height <= 0) return

            val bitmap = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            rootView.draw(canvas)

            val scaled = scaleBitmap(bitmap)
            if (scaled !== bitmap) bitmap.recycle()

            if (config.redactTextViews) {
                redactTextViews(rootView, scaled, rootView.width, rootView.height)
            }

            emitScreenshot(scaled, trigger, activity)
            scaled.recycle()
        } catch (e: Exception) {
            Log.w(TAG, "Sync screenshot capture failed: ${e.message}")
        }
    }

    private fun captureFromActivity(activity: Activity, trigger: String) {
        val window = activity.window ?: return
        val decorView = window.decorView
        val rootView = decorView.rootView

        if (rootView.width <= 0 || rootView.height <= 0) return

        val bitmap = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
        val handler = pixelCopyHandler

        if (handler != null) {
            // Prefer PixelCopy — captures hardware-accelerated content.
            try {
                val latch = CountDownLatch(1)
                var copyResult = PixelCopy.ERROR_UNKNOWN

                // PixelCopy must be called with the window, not a view.
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    PixelCopy.request(window, bitmap, { result ->
                        copyResult = result
                        latch.countDown()
                    }, handler)
                } else {
                    // If called from background thread, post to main to get the window.
                    val mainLatch = CountDownLatch(1)
                    Handler(Looper.getMainLooper()).post {
                        try {
                            PixelCopy.request(window, bitmap, { result ->
                                copyResult = result
                                latch.countDown()
                            }, handler)
                        } catch (e: Exception) {
                            latch.countDown()
                        }
                        mainLatch.countDown()
                    }
                    mainLatch.await(2, TimeUnit.SECONDS)
                }

                if (latch.await(3, TimeUnit.SECONDS) && copyResult == PixelCopy.SUCCESS) {
                    processAndEmit(bitmap, rootView, trigger, activity)
                    return
                }
            } catch (e: Exception) {
                Log.d(TAG, "PixelCopy failed, falling back to View.draw: ${e.message}")
            }
        }

        // Fallback: View.draw()
        try {
            val fallbackAction = Runnable {
                try {
                    val canvas = Canvas(bitmap)
                    rootView.draw(canvas)
                    processAndEmit(bitmap, rootView, trigger, activity)
                } catch (e: Exception) {
                    Log.w(TAG, "View.draw fallback failed: ${e.message}")
                    bitmap.recycle()
                }
            }

            if (Looper.myLooper() == Looper.getMainLooper()) {
                fallbackAction.run()
            } else {
                Handler(Looper.getMainLooper()).post(fallbackAction)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Screenshot capture failed: ${e.message}")
            bitmap.recycle()
        }
    }

    private fun processAndEmit(bitmap: Bitmap, rootView: View, trigger: String, activity: Activity) {
        val scaled = scaleBitmap(bitmap)
        if (scaled !== bitmap) bitmap.recycle()

        if (config.redactTextViews) {
            redactTextViews(rootView, scaled, rootView.width, rootView.height)
        }

        emitScreenshot(scaled, trigger, activity)
        scaled.recycle()
    }

    /**
     * Downscale the bitmap to fit within [ScreenshotConfig.maxWidthPx] x [ScreenshotConfig.maxHeightPx]
     * while preserving aspect ratio.
     */
    internal fun scaleBitmap(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= config.maxWidthPx && h <= config.maxHeightPx) return bitmap

        val scaleW = config.maxWidthPx.toFloat() / w
        val scaleH = config.maxHeightPx.toFloat() / h
        val scale = minOf(scaleW, scaleH)

        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)

        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    /**
     * Draw solid rectangles over all [TextView] bounds in the scaled bitmap.
     * Maps coordinates from the original view dimensions to the scaled bitmap dimensions.
     */
    internal fun redactTextViews(rootView: View, bitmap: Bitmap, originalWidth: Int, originalHeight: Int) {
        val canvas = Canvas(bitmap)
        val scaleX = bitmap.width.toFloat() / originalWidth
        val scaleY = bitmap.height.toFloat() / originalHeight

        val textViewBounds = mutableListOf<Rect>()
        collectTextViewBounds(rootView, textViewBounds)

        val location = IntArray(2)
        val rootLocation = IntArray(2)
        rootView.getLocationOnScreen(rootLocation)

        for (rect in textViewBounds) {
            canvas.drawRect(
                rect.left * scaleX,
                rect.top * scaleY,
                rect.right * scaleX,
                rect.bottom * scaleY,
                redactPaint
            )
        }
    }

    /**
     * Recursively collect the on-screen bounds of all visible [TextView]s relative to the root view.
     */
    private fun collectTextViewBounds(view: View, bounds: MutableList<Rect>) {
        if (view.visibility != View.VISIBLE) return

        if (view is TextView && view.text.isNotEmpty()) {
            val location = IntArray(2)
            view.getLocationInWindow(location)
            bounds.add(Rect(
                location[0],
                location[1],
                location[0] + view.width,
                location[1] + view.height
            ))
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectTextViewBounds(view.getChildAt(i), bounds)
            }
        }
    }

    private fun emitScreenshot(bitmap: Bitmap, trigger: String, activity: Activity) {
        val log = logger ?: return
        val context = ctx ?: return
        val sessionProvider = context.sessionProvider

        // Compress the image.
        val baos = ByteArrayOutputStream()
        val compressFormat = when (config.format) {
            ScreenshotFormat.PNG -> Bitmap.CompressFormat.PNG
            ScreenshotFormat.JPEG -> Bitmap.CompressFormat.JPEG
        }
        bitmap.compress(compressFormat, config.quality, baos)
        val bytes = baos.toByteArray()

        // Enforce payload size limit.
        val payloadKb = bytes.size / 1024
        if (payloadKb > config.maxPayloadKb) {
            Log.d(TAG, "Screenshot payload ${payloadKb}KB exceeds limit ${config.maxPayloadKb}KB, dropping")
            return
        }

        // Build a data URL — directly renderable in browsers and dashboard UIs.
        val mimeType = when (config.format) {
            ScreenshotFormat.PNG -> "image/png"
            ScreenshotFormat.JPEG -> "image/jpeg"
        }
        val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val dataUrl = "data:$mimeType;base64,$base64Data"

        val screenName = context.sessionProvider.getCurrentScreenName()

        val attrs = Attributes.builder()
            .put(MobileSemconv.SESSION_ID, sessionProvider.getSessionId())
            .put(MobileSemconv.VIEW_ID, sessionProvider.getViewId())
            .put(MobileSemconv.SCREENSHOT_TRIGGER, trigger)
            .put(MobileSemconv.SCREENSHOT_FORMAT, config.format.name.lowercase())
            .put(MobileSemconv.SCREENSHOT_WIDTH, bitmap.width.toLong())
            .put(MobileSemconv.SCREENSHOT_HEIGHT, bitmap.height.toLong())
            .put(MobileSemconv.SCREENSHOT_SIZE_BYTES, bytes.size.toLong())
            .put(MobileSemconv.SCREENSHOT_REDACTED, config.redactTextViews)
            .put(MobileSemconv.SCREENSHOT_DATA_URL, dataUrl)
            .apply {
                screenName?.let { put(MobileSemconv.SCREEN_NAME, it) }
            }
            .build()

        emitUiTelemetry(MobileSemconv.UI_SCREENSHOT, attrs)
    }

    private fun emitUiTelemetry(name: String, attrs: Attributes) {
        when (uiTelemetryMode) {
            UiTelemetryMode.EVENTS -> logger?.logRecordBuilder()
                ?.setBody(name)?.setSeverity(Severity.INFO)?.setAllAttributes(attrs)?.emit()
            UiTelemetryMode.SPANS -> tracer
                ?.spanBuilder(name)?.setSpanKind(SpanKind.INTERNAL)?.startSpan()
                ?.apply { setAllAttributes(attrs); end() }
            UiTelemetryMode.BOTH -> {
                logger?.logRecordBuilder()
                    ?.setBody(name)?.setSeverity(Severity.INFO)?.setAllAttributes(attrs)?.emit()
                tracer
                    ?.spanBuilder(name)?.setSpanKind(SpanKind.INTERNAL)?.startSpan()
                    ?.apply { setAllAttributes(attrs); end() }
            }
        }
    }

    /** Visible for testing — returns the currently tracked foreground activity. */
    internal val trackedActivity: Activity? get() = currentActivity?.get()

    /** Visible for testing — returns whether the instrumentation is installed. */
    internal val isInstalled: Boolean get() = callbacks != null

    companion object {
        private const val TAG = "OTel-Screenshot"
    }
}
