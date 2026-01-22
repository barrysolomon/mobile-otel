package io.opentelemetry.android.mobile.tailing

/**
 * Configuration for log tailing - keeping a circular buffer of recent logs for context.
 *
 * Log tailing captures the last N log records in memory, allowing triggers to
 * evaluate patterns across multiple logs (e.g., "3 HTTP errors in last 10 logs").
 *
 * This is essential for:
 * - Crash debugging (what happened before crash?)
 * - Error pattern detection (repeated failures)
 * - User journey tracking (what actions led to error?)
 * - Anomaly detection (unusual log patterns)
 *
 * Usage:
 * ```kotlin
 * // Keep last 50 logs, trigger on 3 errors
 * val config = LogTailingConfig(
 *     tailSize = 50,
 *     enabled = true
 * )
 * ```
 *
 * @property enabled Whether log tailing is enabled (default: true)
 * @property tailSize Number of recent logs to keep in circular buffer (default: 100)
 * @property includeDebugLogs Include DEBUG level logs in tail (default: false)
 * @property includeInfoLogs Include INFO level logs in tail (default: true)
 * @property includeWarnLogs Include WARN level logs in tail (default: true)
 * @property includeErrorLogs Include ERROR level logs in tail (default: true)
 * @property includeFatalLogs Include FATAL level logs in tail (default: true)
 */
data class LogTailingConfig(
    val enabled: Boolean = true,
    val tailSize: Int = 100,
    val includeDebugLogs: Boolean = false,
    val includeInfoLogs: Boolean = true,
    val includeWarnLogs: Boolean = true,
    val includeErrorLogs: Boolean = true,
    val includeFatalLogs: Boolean = true
) {
    init {
        require(tailSize > 0) { "tailSize must be positive" }
        require(tailSize <= 1000) { "tailSize cannot exceed 1000 (memory constraint)" }
    }

    companion object {
        /**
         * Default configuration: 100 logs, INFO and above.
         */
        fun default() = LogTailingConfig()

        /**
         * Small tail: 20 logs (minimal memory).
         */
        fun small() = LogTailingConfig(
            tailSize = 20
        )

        /**
         * Medium tail: 50 logs (balanced).
         */
        fun medium() = LogTailingConfig(
            tailSize = 50
        )

        /**
         * Large tail: 200 logs (comprehensive context).
         */
        fun large() = LogTailingConfig(
            tailSize = 200
        )

        /**
         * Errors only: Only keep ERROR and FATAL logs.
         */
        fun errorsOnly() = LogTailingConfig(
            tailSize = 50,
            includeDebugLogs = false,
            includeInfoLogs = false,
            includeWarnLogs = false,
            includeErrorLogs = true,
            includeFatalLogs = true
        )

        /**
         * Verbose: Include all log levels including DEBUG.
         */
        fun verbose() = LogTailingConfig(
            tailSize = 100,
            includeDebugLogs = true,
            includeInfoLogs = true,
            includeWarnLogs = true,
            includeErrorLogs = true,
            includeFatalLogs = true
        )

        /**
         * Disabled: No log tailing (for memory-constrained devices).
         */
        fun disabled() = LogTailingConfig(
            enabled = false,
            tailSize = 0
        )
    }

    /**
     * Checks if a severity level should be included in tail.
     */
    fun shouldIncludeSeverity(severity: io.opentelemetry.api.logs.Severity): Boolean {
        return when (severity) {
            io.opentelemetry.api.logs.Severity.TRACE,
            io.opentelemetry.api.logs.Severity.TRACE2,
            io.opentelemetry.api.logs.Severity.TRACE3,
            io.opentelemetry.api.logs.Severity.TRACE4 -> includeDebugLogs

            io.opentelemetry.api.logs.Severity.DEBUG,
            io.opentelemetry.api.logs.Severity.DEBUG2,
            io.opentelemetry.api.logs.Severity.DEBUG3,
            io.opentelemetry.api.logs.Severity.DEBUG4 -> includeDebugLogs

            io.opentelemetry.api.logs.Severity.INFO,
            io.opentelemetry.api.logs.Severity.INFO2,
            io.opentelemetry.api.logs.Severity.INFO3,
            io.opentelemetry.api.logs.Severity.INFO4 -> includeInfoLogs

            io.opentelemetry.api.logs.Severity.WARN,
            io.opentelemetry.api.logs.Severity.WARN2,
            io.opentelemetry.api.logs.Severity.WARN3,
            io.opentelemetry.api.logs.Severity.WARN4 -> includeWarnLogs

            io.opentelemetry.api.logs.Severity.ERROR,
            io.opentelemetry.api.logs.Severity.ERROR2,
            io.opentelemetry.api.logs.Severity.ERROR3,
            io.opentelemetry.api.logs.Severity.ERROR4 -> includeErrorLogs

            io.opentelemetry.api.logs.Severity.FATAL,
            io.opentelemetry.api.logs.Severity.FATAL2,
            io.opentelemetry.api.logs.Severity.FATAL3,
            io.opentelemetry.api.logs.Severity.FATAL4 -> includeFatalLogs

            else -> true  // Include unknown severities
        }
    }
}

/**
 * Pattern-based trigger for log tail analysis.
 *
 * Triggers export when a pattern is detected in recent logs, e.g.:
 * - "3 HTTP errors in last 10 logs"
 * - "Any FATAL log"
 * - "2 slow requests followed by crash"
 *
 * @property id Unique identifier for this trigger
 * @property name Human-readable name
 * @property enabled Whether trigger is active
 * @property pattern Pattern to match (see TailPattern)
 * @property lookbackCount Number of recent logs to analyze (default: 10)
 * @property flushWindowMinutes Minutes of events to flush when triggered (default: 5)
 */
data class LogTailTrigger(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val pattern: TailPattern,
    val lookbackCount: Int = 10,
    val flushWindowMinutes: Int = 5
) {
    init {
        require(lookbackCount > 0) { "lookbackCount must be positive" }
        require(lookbackCount <= 100) { "lookbackCount cannot exceed 100" }
    }

    companion object {
        /**
         * Trigger on app start (first log of new session).
         */
        fun onAppStart() = LogTailTrigger(
            id = "tail-app-start",
            name = "App Start",
            pattern = TailPattern.EventNameMatch("app.start"),
            lookbackCount = 1,
            flushWindowMinutes = 5
        )

        /**
         * Trigger on force close (app killed by user).
         */
        fun onForceClose() = LogTailTrigger(
            id = "tail-force-close",
            name = "Force Close",
            pattern = TailPattern.EventNameMatch("app.force_close"),
            lookbackCount = 50,  // Capture more context before force close
            flushWindowMinutes = 15
        )

        /**
         * Trigger on any ERROR or FATAL log.
         */
        fun onAnyError() = LogTailTrigger(
            id = "tail-any-error",
            name = "Any Error Log",
            pattern = TailPattern.AnySeverity(
                severities = listOf(
                    io.opentelemetry.api.logs.Severity.ERROR,
                    io.opentelemetry.api.logs.Severity.FATAL
                )
            ),
            lookbackCount = 1,
            flushWindowMinutes = 5
        )

        /**
         * Trigger on 3+ errors in last 10 logs.
         */
        fun onRepeatedErrors(count: Int = 3) = LogTailTrigger(
            id = "tail-repeated-errors",
            name = "Repeated Errors ($count+ in 10 logs)",
            pattern = TailPattern.CountThreshold(
                severities = listOf(io.opentelemetry.api.logs.Severity.ERROR),
                minCount = count
            ),
            lookbackCount = 10,
            flushWindowMinutes = 10
        )

        /**
         * Trigger on event name pattern (e.g., "crash", "http.error").
         */
        fun onEventName(eventName: String) = LogTailTrigger(
            id = "tail-event-$eventName",
            name = "Event: $eventName",
            pattern = TailPattern.EventNameMatch(eventName),
            lookbackCount = 1,
            flushWindowMinutes = 5
        )

        /**
         * Trigger on attribute pattern (e.g., http.status_code >= 500).
         */
        fun onAttribute(attrName: String, op: String, value: Any) = LogTailTrigger(
            id = "tail-attr-$attrName",
            name = "Attribute: $attrName $op $value",
            pattern = TailPattern.AttributeMatch(attrName, op, value),
            lookbackCount = 5,
            flushWindowMinutes = 5
        )

        /**
         * Trigger on any HTTP/API error (4xx or 5xx status codes).
         * Captures context when API calls fail.
         */
        fun onApiError() = LogTailTrigger(
            id = "tail-api-error",
            name = "API Error (4xx/5xx)",
            pattern = TailPattern.AttributeMatch("http.status_code", ">=", 400),
            lookbackCount = 10,
            flushWindowMinutes = 10
        )

        /**
         * Trigger on HTTP server errors (5xx status codes only).
         * Useful for backend issues that require immediate investigation.
         */
        fun onServerError() = LogTailTrigger(
            id = "tail-server-error",
            name = "Server Error (5xx)",
            pattern = TailPattern.AttributeMatch("http.status_code", ">=", 500),
            lookbackCount = 10,
            flushWindowMinutes = 10
        )

        /**
         * Trigger on repeated API errors (multiple failures in sequence).
         * Detects API error cascades that indicate serious backend issues.
         *
         * @param count Minimum number of API errors to trigger (default: 3)
         * @param lookback Number of recent logs to analyze (default: 10)
         */
        fun onRepeatedApiErrors(count: Int = 3, lookback: Int = 10) = LogTailTrigger(
            id = "tail-repeated-api-errors",
            name = "Repeated API Errors ($count+ in $lookback logs)",
            pattern = TailPattern.CustomPredicate { _ ->
                // This will be evaluated by LogTailBuffer using the full tail
                false  // Placeholder - actual evaluation happens in evaluatePattern
            },
            lookbackCount = lookback,
            flushWindowMinutes = 15
        )
    }
}

/**
 * Pattern to match in log tail.
 */
sealed class TailPattern {
    /**
     * Match any log with specified severities.
     */
    data class AnySeverity(
        val severities: List<io.opentelemetry.api.logs.Severity>
    ) : TailPattern()

    /**
     * Match count of logs with specified severities exceeding threshold.
     */
    data class CountThreshold(
        val severities: List<io.opentelemetry.api.logs.Severity>,
        val minCount: Int
    ) : TailPattern()

    /**
     * Match event name (log body).
     */
    data class EventNameMatch(
        val eventName: String
    ) : TailPattern()

    /**
     * Match attribute condition.
     */
    data class AttributeMatch(
        val attributeName: String,
        val operator: String,  // "=", ">", "<", ">=", "<=", "contains"
        val value: Any
    ) : TailPattern()

    /**
     * Match custom predicate function.
     */
    data class CustomPredicate(
        val predicate: (io.opentelemetry.sdk.logs.data.LogRecordData) -> Boolean
    ) : TailPattern()
}
