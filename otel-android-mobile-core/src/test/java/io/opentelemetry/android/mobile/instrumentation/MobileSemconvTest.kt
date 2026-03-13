// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import org.junit.Test
import kotlin.test.assertEquals

class MobileSemconvTest {

    @Test fun `event name constants are correct`() {
        assertEquals("ui.tap",         MobileSemconv.UI_TAP)
        assertEquals("ui.long_press",  MobileSemconv.UI_LONG_PRESS)
        assertEquals("ui.swipe",       MobileSemconv.UI_SWIPE)
        assertEquals("ui.scroll",      MobileSemconv.UI_SCROLL)
        assertEquals("ui.text_input",  MobileSemconv.UI_TEXT_INPUT)
        assertEquals("ui.back_press",  MobileSemconv.UI_BACK_PRESS)
        assertEquals("ui.screen_view", MobileSemconv.UI_SCREEN_VIEW)
        assertEquals("app.start",      MobileSemconv.APP_START)
        assertEquals("app.foreground", MobileSemconv.APP_FOREGROUND)
        assertEquals("app.background", MobileSemconv.APP_BACKGROUND)
        assertEquals("screen.render",  MobileSemconv.SCREEN_RENDER)
        assertEquals("app.startup",    MobileSemconv.APP_STARTUP)
    }

    @Test fun `attribute key names are correct`() {
        assertEquals("mobile.session.id",        MobileSemconv.SESSION_ID.key)
        assertEquals("mobile.view.id",          MobileSemconv.VIEW_ID.key)
        assertEquals("mobile.screen.name",      MobileSemconv.SCREEN_NAME.key)
        assertEquals("ui.element.resource_id",  MobileSemconv.UI_ELEMENT_ID.key)
        assertEquals("ui.swipe.direction",      MobileSemconv.SWIPE_DIRECTION.key)
        assertEquals("recovery_type",           MobileSemconv.RECOVERY_TYPE.key)
        assertEquals("session.renewed",         MobileSemconv.SESSION_RENEWED.key)
        assertEquals("background_duration_ms",  MobileSemconv.BACKGROUND_DURATION_MS.key)
    }
}
