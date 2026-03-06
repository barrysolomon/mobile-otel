// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import io.opentelemetry.api.common.AttributeKey

/**
 * Centralized semantic convention constants for mobile instrumentation.
 *
 * All event names and attribute keys used across instrumentation modules
 * are defined here to prevent magic strings and ensure consistency.
 * Aligns with the OTel Mobile Semconv SIG working group output.
 */
object MobileSemconv {

    // ── Event names ──────────────────────────────────────────────────────────
    const val UI_TAP         = "ui.tap"
    const val UI_LONG_PRESS  = "ui.long_press"
    const val UI_SWIPE       = "ui.swipe"
    const val UI_SCROLL      = "ui.scroll"
    const val UI_TEXT_INPUT  = "ui.text_input"
    const val UI_BACK_PRESS  = "ui.back_press"
    const val UI_SCREEN_VIEW = "ui.screen_view"
    const val APP_START      = "app.start"
    const val APP_FOREGROUND = "app.foreground"
    const val APP_BACKGROUND = "app.background"
    const val SCREEN_RENDER  = "screen.render"
    const val APP_STARTUP    = "app.startup"

    // ── Attribute keys ───────────────────────────────────────────────────────
    @JvmField val SESSION_ID             = AttributeKey.stringKey("session.id")
    @JvmField val VIEW_ID                = AttributeKey.stringKey("view.id")
    @JvmField val SCREEN_NAME            = AttributeKey.stringKey("screen.name")
    @JvmField val UI_ELEMENT_ID          = AttributeKey.stringKey("ui.element.resource_id")
    @JvmField val SWIPE_DIRECTION        = AttributeKey.stringKey("ui.swipe.direction")
    @JvmField val RECOVERY_TYPE          = AttributeKey.stringKey("recovery_type")
    @JvmField val SESSION_RENEWED        = AttributeKey.booleanKey("session.renewed")
    @JvmField val BACKGROUND_DURATION_MS = AttributeKey.longKey("background_duration_ms")
}
