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
            .put(AttributeKey.stringKey("session.id"), sessionTracker.getSessionId())
            .put(AttributeKey.stringKey("view.id"), sessionTracker.getViewId())
            .apply {
                if (screenName != null) {
                    put(AttributeKey.stringKey("screen.name"), screenName)
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
