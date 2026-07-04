// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.util.Log
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity

/**
 * Captures Timber log calls as OTel log events.
 *
 * Installs a [timber.log.Timber.Tree] that forwards log messages to the
 * OTel Logger. Only logs at or above the configured minimum priority are
 * captured (default: [Log.INFO]).
 *
 * Emits log events with:
 * - body: the log message
 * - severity: mapped from Android log priority
 * - attributes: `log.tag`, `log.priority`
 *
 * Requires `timber` on the classpath. If Timber is not available, install()
 * is a no-op with a warning.
 */
@Incubating
class TimberInstrumentation(
    private val minPriority: Int = Log.INFO
) : MobileInstrumentation {

    override val instrumentationName = "io.opentelemetry.android.mobile.timber"

    private var tree: Any? = null // Typed as Any to avoid hard dependency on Timber

    override fun install(application: Application, context: InstrumentationContext) {
        try {
            val logger = context.logger(instrumentationName)
            val otelTree = OTelTimberTree(logger, minPriority)
            // Use reflection to call Timber.plant(tree) to avoid compile-time dependency
            val timberClass = Class.forName("timber.log.Timber")
            val plantMethod = timberClass.getMethod("plant", Class.forName("timber.log.Timber\$Tree"))
            plantMethod.invoke(null, otelTree)
            tree = otelTree
        } catch (e: ClassNotFoundException) {
            Log.w("TimberInstrumentation", "Timber not on classpath — skipping instrumentation")
        } catch (e: Exception) {
            Log.e("TimberInstrumentation", "Failed to install Timber tree", e)
        }
    }

    override fun uninstall() {
        try {
            val t = tree ?: return
            val timberClass = Class.forName("timber.log.Timber")
            val uprootMethod = timberClass.getMethod("uproot", Class.forName("timber.log.Timber\$Tree"))
            uprootMethod.invoke(null, t)
            tree = null
        } catch (e: Exception) {
            Log.w("TimberInstrumentation", "Failed to uninstall Timber tree", e)
        }
    }
}

/**
 * A Timber.Tree that forwards logs to an OTel Logger.
 *
 * Extends timber.log.Timber.Tree via reflection-free inheritance.
 * This class must be on the classpath when Timber is available.
 */
internal class OTelTimberTree(
    private val logger: Logger,
    private val minPriority: Int
) : timber.log.Timber.Tree() {

    override fun isLoggable(tag: String?, priority: Int): Boolean {
        return priority >= minPriority
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val severity = mapPriority(priority)
        val attrs = Attributes.builder()
            .put(AttributeKey.stringKey("log.tag"), tag ?: "")
            .put(AttributeKey.longKey("log.priority"), priority.toLong())
        if (t != null) {
            attrs.put(AttributeKey.stringKey("exception.type"), t.javaClass.name)
            attrs.put(AttributeKey.stringKey("exception.message"), t.message ?: "")
        }

        logger.logRecordBuilder()
            .setBody(message)
            .setSeverity(severity)
            .setAllAttributes(attrs.build())
            .emit()
    }

    private fun mapPriority(priority: Int): Severity = when (priority) {
        Log.VERBOSE -> Severity.TRACE
        Log.DEBUG -> Severity.DEBUG
        Log.INFO -> Severity.INFO
        Log.WARN -> Severity.WARN
        Log.ERROR -> Severity.ERROR
        Log.ASSERT -> Severity.FATAL
        else -> Severity.UNDEFINED_SEVERITY_NUMBER
    }
}
