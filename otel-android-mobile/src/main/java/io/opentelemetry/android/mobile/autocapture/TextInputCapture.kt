/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import java.util.WeakHashMap

/**
 * Captures text-input events when an EditText loses focus.
 *
 * Uses a single [ViewTreeObserver.OnGlobalFocusChangeListener] per root view so the
 * listener fires whenever the focused view changes — no per-EditText TextWatcher needed.
 * The event is emitted only when the *outgoing* focused view is an EditText, which means
 * the user finished editing a field (moved to another field, tapped away, or submitted).
 *
 * Emitted event name: `ui.text_input`
 * Key attributes: `ui.element.resource_id`, `screen.name`, `session.id`, `view.id`
 */
class TextInputCapture(
    private val logger: Logger,
    private val tracer: Tracer,
    private val sessionTracker: SessionTracker,
    private val options: AutoCaptureOptions
) {
    // WeakHashMap so we don't prevent GC of detached root views.
    private val attached = WeakHashMap<ViewTreeObserver, ViewTreeObserver.OnGlobalFocusChangeListener>()

    fun attachTo(root: View) {
        if (!options.captureTextInput) return
        val observer = root.viewTreeObserver
        if (!observer.isAlive) return
        if (attached.containsKey(observer)) return

        val listener = ViewTreeObserver.OnGlobalFocusChangeListener { oldFocus, _ ->
            if (oldFocus is EditText) {
                onFieldLeft(oldFocus)
            }
        }
        observer.addOnGlobalFocusChangeListener(listener)
        attached[observer] = listener
    }

    private fun onFieldLeft(field: EditText) {
        val capturedContext = Context.current()
        val screenName = sessionTracker.getCurrentScreenName()

        val resourceId: String? = if (field.id != View.NO_ID) {
            try { field.resources.getResourceEntryName(field.id) } catch (_: Exception) { null }
        } else null

        val attrs = Attributes.builder()
            .put(AttributeKey.stringKey("mobile.session.id"), sessionTracker.getSessionId())
            .put(AttributeKey.stringKey("mobile.view.id"), sessionTracker.getViewId())
            .apply {
                if (resourceId != null) put(AttributeKey.stringKey("ui.element.resource_id"), resourceId)
                if (screenName != null) put(AttributeKey.stringKey("mobile.screen.name"), screenName)
                put(AttributeKey.booleanKey("ui.element.enabled"), field.isEnabled)
            }
            .build()

        val parentSpan = Span.fromContext(capturedContext)
        if (parentSpan.spanContext.isValid && parentSpan.spanContext.isSampled) {
            val child = tracer.spanBuilder("ui.text_input")
                .setParent(capturedContext)
                .setSpanKind(SpanKind.INTERNAL)
                .setAllAttributes(attrs)
                .startSpan()
            child.end()
        } else {
            logger.logRecordBuilder()
                .setBody("ui.text_input")
                .setSeverity(Severity.INFO)
                .setAllAttributes(attrs)
                .setContext(capturedContext)
                .emit()
        }
    }
}
