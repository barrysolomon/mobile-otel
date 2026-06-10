// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.EditText
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.android.mobile.instrumentation.Incubating
import java.util.WeakHashMap

@Incubating
class TextInputInstrumentation(
    private val config: TextInputConfig = TextInputConfig()
) : MobileInstrumentation, WindowEventListener {

    override val instrumentationName = "io.opentelemetry.android.mobile.text_input"

    private var hub: WindowEventHub? = null
    private var ctx: InstrumentationContext? = null
    private var logger: Logger? = null
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    private val attached = WeakHashMap<ViewTreeObserver, ViewTreeObserver.OnGlobalFocusChangeListener>()

    override fun install(application: Application, context: InstrumentationContext) {
        ctx = context
        hub = context.windowEventHub
        logger = context.logger(instrumentationName)
        context.windowEventHub.addListener(this)

        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                val root = activity.window?.decorView ?: return
                attachTo(root)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        lifecycleCallbacks = callbacks
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    override fun uninstall() {
        lifecycleCallbacks?.let { ctx?.application?.unregisterActivityLifecycleCallbacks(it) }
        hub?.removeListener(this)
        hub = null
        ctx = null
        logger = null
        lifecycleCallbacks = null
        attached.clear()
    }

    private fun attachTo(root: View) {
        val observer = root.viewTreeObserver
        if (!observer.isAlive || attached.containsKey(observer)) return
        val listener = ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, _ ->
            if (oldFocus is EditText) onFieldLeft(oldFocus)
        }
        observer.addOnGlobalFocusChangeListener(listener)
        attached[observer] = listener
    }

    /** Visible for testing — emit a text-input telemetry record for the given field. */
    internal fun emitTextInput(
        resourceId: String?,
        enabled: Boolean,
        charCount: Int? = null,
        text: String? = null
    ) {
        val log = logger ?: return
        val context = ctx ?: return
        val sessionProvider = context.sessionProvider
        val attrs = Attributes.builder()
            .put(MobileSemconv.SESSION_ID, sessionProvider.getSessionId())
            .put(MobileSemconv.VIEW_ID, sessionProvider.getViewId())
            .apply {
                resourceId?.let { put(MobileSemconv.UI_ELEMENT_ID, it) }
                sessionProvider.getCurrentScreenName()?.let { put(MobileSemconv.SCREEN_NAME, it) }
                put(io.opentelemetry.api.common.AttributeKey.booleanKey("ui.element.enabled"), enabled)
                if (config.captureCharCount && charCount != null) {
                    put(MobileSemconv.TEXT_CHAR_COUNT, charCount.toLong())
                }
                if (config.captureIsSet && charCount != null) {
                    put(MobileSemconv.TEXT_IS_SET, charCount > 0)
                }
                if (config.captureTextContent && text != null &&
                    resourceId != null && resourceId in config.textContentAllowlist) {
                    put(MobileSemconv.TEXT_CONTENT, text)
                }
            }
            .build()
        when (context.uiTelemetryMode) {
            UiTelemetryMode.EVENTS -> log.logRecordBuilder()
                .setBody(MobileSemconv.UI_TEXT_INPUT).setSeverity(Severity.INFO)
                .setAllAttributes(attrs).emit()
            UiTelemetryMode.SPANS  -> context.tracer(instrumentationName)
                .spanBuilder(MobileSemconv.UI_TEXT_INPUT).startSpan()
                .apply { setAllAttributes(attrs); end() }
            UiTelemetryMode.BOTH   -> {
                log.logRecordBuilder()
                    .setBody(MobileSemconv.UI_TEXT_INPUT).setSeverity(Severity.INFO)
                    .setAllAttributes(attrs).emit()
                context.tracer(instrumentationName)
                    .spanBuilder(MobileSemconv.UI_TEXT_INPUT).startSpan()
                    .apply { setAllAttributes(attrs); end() }
            }
        }
    }

    private fun onFieldLeft(field: EditText) {
        // Skip ALL emission for password fields — even metadata like char count /
        // is-set leaks information about a secret value. Detect every password
        // input-type variation (text + visible + web + numeric).
        if (isPasswordField(field)) return

        val resourceId: String? = if (field.id != View.NO_ID) {
            try { field.resources.getResourceEntryName(field.id) } catch (_: Exception) { null }
        } else null
        val text = field.text?.toString() ?: ""
        emitTextInput(
            resourceId = resourceId,
            enabled = field.isEnabled,
            charCount = text.length,
            text = text
        )
    }

    /** True if [field] is any password-input variation. */
    private fun isPasswordField(field: EditText): Boolean {
        val variation = field.inputType and InputType.TYPE_MASK_VARIATION
        val klass = field.inputType and InputType.TYPE_MASK_CLASS
        return when {
            klass == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                ) -> true
            klass == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD -> true
            else -> false
        }
    }
}
