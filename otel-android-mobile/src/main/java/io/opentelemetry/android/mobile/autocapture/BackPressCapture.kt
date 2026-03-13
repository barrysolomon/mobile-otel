/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity

class BackPressCapture(
    private val logger: Logger,
    private val sessionTracker: SessionTracker
) {
    fun onBackPressed() {
        val screenName = sessionTracker.getCurrentScreenName()
        val attributes = Attributes.builder()
            .put(AttributeKey.stringKey("mobile.session.id"), sessionTracker.getSessionId())
            .put(AttributeKey.stringKey("mobile.view.id"), sessionTracker.getViewId())
            .apply {
                if (screenName != null) {
                    put(AttributeKey.stringKey("mobile.screen.name"), screenName)
                }
            }
            .build()

        logger.logRecordBuilder()
            .setBody("ui.back_press")
            .setSeverity(Severity.INFO)
            .setAllAttributes(attributes)
            .emit()
    }
}
