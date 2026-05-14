// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import io.opentelemetry.api.common.AttributeKey

/**
 * Centralized OTel attribute key constants for mobile instrumentation.
 * Keys prefixed "mobile.*" are custom incubating attributes aligned with the OTel Mobile SIG.
 * Standard semconv keys (exception.*, http.*, network.*) follow OTel spec 1.x.
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
    @JvmField val SESSION_ID             = AttributeKey.stringKey("mobile.session.id")
    @JvmField val VIEW_ID                = AttributeKey.stringKey("mobile.view.id")
    @JvmField val SCREEN_NAME            = AttributeKey.stringKey("mobile.screen.name")
    @JvmField val UI_ELEMENT_ID          = AttributeKey.stringKey("ui.element.resource_id")
    @JvmField val UI_ELEMENT_CLASS       = AttributeKey.stringKey("ui.element.class")
    @JvmField val UI_ELEMENT_LABEL       = AttributeKey.stringKey("ui.element.label")
    @JvmField val UI_ELEMENT_ENABLED     = AttributeKey.booleanKey("ui.element.enabled")
    @JvmField val UI_ELEMENT_CLICKABLE   = AttributeKey.booleanKey("ui.element.clickable")
    @JvmField val SWIPE_DIRECTION        = AttributeKey.stringKey("ui.swipe.direction")
    @JvmField val TEXT_CHAR_COUNT        = AttributeKey.longKey("ui.element.char_count")
    @JvmField val TEXT_IS_SET            = AttributeKey.booleanKey("ui.element.is_set")
    @JvmField val TEXT_CONTENT           = AttributeKey.stringKey("ui.element.text")
    @JvmField val PREVIOUS_SCREEN        = AttributeKey.stringKey("ui.previous_screen")
    @JvmField val TIME_ON_SCREEN_MS      = AttributeKey.longKey("ui.time_on_screen_ms")
    @JvmField val RECOVERY_TYPE          = AttributeKey.stringKey("mobile.recovery_type")
    @JvmField val SESSION_RENEWED        = AttributeKey.booleanKey("mobile.session.renewed")
    @JvmField val BACKGROUND_DURATION_MS = AttributeKey.longKey("mobile.background_duration_ms")

    // ── Standard semconv keys (OTel spec 1.x) ────────────────────────────────
    @JvmField val EXCEPTION_TYPE         = AttributeKey.stringKey("exception.type")
    @JvmField val EXCEPTION_MESSAGE      = AttributeKey.stringKey("exception.message")
    @JvmField val EXCEPTION_STACKTRACE   = AttributeKey.stringKey("exception.stacktrace")
    @JvmField val NETWORK_CONNECTION_TYPE = AttributeKey.stringKey("network.connection.type")

    // ── Screenshot ──────────────────────────────────────────────────────────
    const val UI_SCREENSHOT = "ui.screenshot"

    @JvmField val SCREENSHOT_TRIGGER    = AttributeKey.stringKey("mobile.screenshot.trigger")
    @JvmField val SCREENSHOT_FORMAT     = AttributeKey.stringKey("mobile.screenshot.format")
    @JvmField val SCREENSHOT_WIDTH      = AttributeKey.longKey("mobile.screenshot.width")
    @JvmField val SCREENSHOT_HEIGHT     = AttributeKey.longKey("mobile.screenshot.height")
    @JvmField val SCREENSHOT_SIZE_BYTES = AttributeKey.longKey("mobile.screenshot.size_bytes")
    @JvmField val SCREENSHOT_DATA_URL   = AttributeKey.stringKey("mobile.screenshot.data_url")
    @JvmField val SCREENSHOT_REDACTED   = AttributeKey.booleanKey("mobile.screenshot.redacted")

    // ── Wireframe ─────────────────────────────────────────────────────────
    const val UI_WIREFRAME     = "ui.wireframe"      // Full wireframe payload
    const val UI_WIREFRAME_REF = "ui.wireframe.ref"  // Reference to a prior wireframe by id

    @JvmField val WIREFRAME_ID         = AttributeKey.stringKey("mobile.wireframe.id")
    @JvmField val WIREFRAME_TRIGGER    = AttributeKey.stringKey("mobile.wireframe.trigger")
    @JvmField val WIREFRAME_SEQUENCE   = AttributeKey.longKey("mobile.wireframe.sequence")
    @JvmField val WIREFRAME_SIZE_BYTES = AttributeKey.longKey("mobile.wireframe.size_bytes")
    @JvmField val WIREFRAME_NODE_COUNT = AttributeKey.longKey("mobile.wireframe.node_count")
    @JvmField val WIREFRAME_DATA       = AttributeKey.stringKey("mobile.wireframe.data")

    // ── DataStore (Amplify) ──────────────────────────────────────────────
    const val DATASTORE_SYNC              = "datastore.sync"
    const val DATASTORE_OUTBOX_ENQUEUED   = "datastore.outbox.enqueued"
    const val DATASTORE_OUTBOX_PROCESSED  = "datastore.outbox.processed"
    const val DATASTORE_OUTBOX_CONFLICT   = "datastore.outbox.conflict"
    const val DATASTORE_MODEL_SYNCED      = "datastore.model.synced"
    const val DATASTORE_SYNC_FAILED       = "datastore.sync.failed"
    const val DATASTORE_NETWORK_CHANGED   = "datastore.network.changed"
    const val DATASTORE_SUBSCRIPTION_EST  = "datastore.subscription.established"

    @JvmField val SYNC_DIRECTION          = AttributeKey.stringKey("sync.direction")
    @JvmField val SYNC_MODEL              = AttributeKey.stringKey("sync.model")
    @JvmField val SYNC_ADDED             = AttributeKey.longKey("sync.added")
    @JvmField val SYNC_UPDATED           = AttributeKey.longKey("sync.updated")
    @JvmField val SYNC_DELETED           = AttributeKey.longKey("sync.deleted")
    @JvmField val MUTATION_MODEL          = AttributeKey.stringKey("mutation.model")
    @JvmField val MUTATION_TYPE           = AttributeKey.stringKey("mutation.type")
    @JvmField val MUTATION_SUCCESS        = AttributeKey.booleanKey("mutation.success")
    @JvmField val CONFLICT_STRATEGY       = AttributeKey.stringKey("conflict.strategy")
    @JvmField val NETWORK_TYPE            = AttributeKey.stringKey("network.type")
    @JvmField val NETWORK_SUBTYPE         = AttributeKey.stringKey("network.subtype")
    @JvmField val NETWORK_PREVIOUS_TYPE   = AttributeKey.stringKey("network.previous_type")
    @JvmField val ERROR_TYPE              = AttributeKey.stringKey("error.type")
    @JvmField val ERROR_MESSAGE           = AttributeKey.stringKey("error.message")
}
