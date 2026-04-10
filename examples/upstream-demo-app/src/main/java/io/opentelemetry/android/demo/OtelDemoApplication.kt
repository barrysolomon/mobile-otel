/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.demo

import android.app.Application
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.logs.LogRecordBuilder
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.LoggerProvider
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer

const val TAG = "otel.demo"

class OtelDemoApplication : Application() {
    companion object {
        var openTelemetry: OpenTelemetry? = null
        var sessionId: String = ""

        fun tracer(name: String): Tracer? = openTelemetry?.getTracer(name)
        fun logger(name: String): Logger? = openTelemetry?.logsBridge?.get(name)
        fun meter(name: String): Meter? = openTelemetry?.getMeter(name)

        fun counter(name: String): LongCounter? {
            return openTelemetry?.meterProvider?.get("demo.app")?.counterBuilder(name)?.build()
        }

        fun eventBuilder(scopeName: String, eventName: String): LogRecordBuilder {
            if (openTelemetry == null) {
                return LoggerProvider.noop().get("noop").logRecordBuilder()
            }
            val logger = openTelemetry!!.logsBridge.loggerBuilder(scopeName).build()
            return logger.logRecordBuilder().setEventName(eventName)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ExportConfig.load(this)
        SdkInitializer.initialize(this)
    }
}
