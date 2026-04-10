// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import io.opentelemetry.android.mobile.breadcrumb.JourneyBreadcrumb
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity

@Incubating
@Supersedes("screen_orientation")
class ScreenOrientationInstrumentation : MobileInstrumentation {

    override val instrumentationName = "io.opentelemetry.android.mobile.screen-orientation"

    private var application: Application? = null
    private var callback: ComponentCallbacks2? = null
    private var lastOrientation: Int = Configuration.ORIENTATION_UNDEFINED

    override fun install(application: Application, context: InstrumentationContext) {
        this.application = application
        lastOrientation = application.resources.configuration.orientation
        val logger = context.logger(instrumentationName)
        val sp = context.sessionProvider

        callback = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) {
                val newOrientation = newConfig.orientation
                if (newOrientation != lastOrientation
                    && newOrientation != Configuration.ORIENTATION_UNDEFINED
                ) {
                    val prev = orientationName(lastOrientation)
                    val curr = orientationName(newOrientation)
                    lastOrientation = newOrientation

                    logger.logRecordBuilder()
                        .setBody("device.orientation")
                        .setSeverity(Severity.INFO)
                        .setAllAttributes(
                            Attributes.builder()
                                .put("device.orientation", curr)
                                .put("device.orientation.previous", prev)
                                .put(MobileSemconv.SESSION_ID, sp.getSessionId())
                                .put(MobileSemconv.VIEW_ID, sp.getViewId())
                                .apply {
                                    sp.getCurrentScreenName()?.let {
                                        put(MobileSemconv.SCREEN_NAME, it)
                                    }
                                }
                                .build()
                        )
                        .emit()

                    val screenName = sp.getCurrentScreenName() ?: "unknown"
                    context.addBreadcrumb(
                        JourneyBreadcrumb.custom(
                            screen = screenName,
                            action = "orientation",
                            attributes = mapOf("to" to curr)
                        )
                    )
                }
            }

            override fun onLowMemory() {}
            override fun onTrimMemory(level: Int) {}
        }
        application.registerComponentCallbacks(callback)
    }

    override fun uninstall() {
        callback?.let { application?.unregisterComponentCallbacks(it) }
        callback = null
        application = null
    }

    private fun orientationName(orientation: Int): String = when (orientation) {
        Configuration.ORIENTATION_PORTRAIT -> "portrait"
        Configuration.ORIENTATION_LANDSCAPE -> "landscape"
        else -> "unknown"
    }
}
