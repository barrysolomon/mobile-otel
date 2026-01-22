package io.opentelemetry.android.mobile.tailing

import io.opentelemetry.sdk.logs.data.LogRecordData
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Circular buffer for keeping recent log records for pattern analysis.
 *
 * Thread-safe implementation using read-write locks for efficient concurrent access.
 * Automatically evicts oldest logs when buffer is full.
 *
 * Usage:
 * ```kotlin
 * val buffer = LogTailBuffer(config, triggers)
 *
 * // Add log to tail
 * val triggeredActions = buffer.addLog(logRecord)
 *
 * // Execute triggered actions
 * triggeredActions.forEach { trigger ->
 *     loggerProvider.flushWindow(trigger.flushWindowMinutes)
 * }
 * ```
 */
class LogTailBuffer(
    private val config: LogTailingConfig,
    private val triggers: List<LogTailTrigger> = emptyList()
) {
    private val lock = ReentrantReadWriteLock()
    private val buffer = CircularBuffer<LogRecordData>(config.tailSize)

    /**
     * Adds a log record to the tail buffer and evaluates triggers.
     *
     * @param logRecord Log record to add
     * @return List of triggers that matched (empty if none)
     */
    fun addLog(logRecord: LogRecordData): List<LogTailTrigger> {
        if (!config.enabled) {
            return emptyList()
        }

        // Check if this log should be included based on severity
        if (!config.shouldIncludeSeverity(logRecord.severity)) {
            return emptyList()
        }

        lock.write {
            // Add to circular buffer
            buffer.add(logRecord)
        }

        // Evaluate triggers (read-only, no lock needed after add)
        return evaluateTriggers()
    }

    /**
     * Gets a snapshot of current tail (most recent logs).
     *
     * @param count Number of logs to retrieve (default: all)
     * @return List of log records (newest first)
     */
    fun getTail(count: Int = config.tailSize): List<LogRecordData> {
        return lock.read {
            buffer.toList().takeLast(count).reversed()
        }
    }

    /**
     * Clears the tail buffer.
     */
    fun clear() {
        lock.write {
            buffer.clear()
        }
    }

    /**
     * Gets the current size of the tail.
     */
    fun size(): Int {
        return lock.read {
            buffer.size()
        }
    }

    /**
     * Evaluates all triggers against current tail.
     *
     * @return List of triggered actions
     */
    private fun evaluateTriggers(): List<LogTailTrigger> {
        val matchedTriggers = mutableListOf<LogTailTrigger>()

        lock.read {
            val tail = buffer.toList()

            for (trigger in triggers) {
                if (!trigger.enabled) continue

                // Get logs to analyze (last N logs)
                val logsToAnalyze = tail.takeLast(trigger.lookbackCount)

                // Evaluate pattern
                if (evaluatePattern(trigger.pattern, logsToAnalyze)) {
                    matchedTriggers.add(trigger)
                }
            }
        }

        return matchedTriggers
    }

    /**
     * Evaluates a pattern against a list of logs.
     */
    private fun evaluatePattern(pattern: TailPattern, logs: List<LogRecordData>): Boolean {
        return when (pattern) {
            is TailPattern.AnySeverity -> {
                logs.any { log ->
                    pattern.severities.contains(log.severity)
                }
            }

            is TailPattern.CountThreshold -> {
                val matchCount = logs.count { log ->
                    pattern.severities.contains(log.severity)
                }
                matchCount >= pattern.minCount
            }

            is TailPattern.EventNameMatch -> {
                logs.any { log ->
                    val body = log.body
                    @Suppress("DEPRECATION")
                    body.asString() == pattern.eventName
                }
            }

            is TailPattern.AttributeMatch -> {
                logs.any { log ->
                    matchAttribute(log, pattern.attributeName, pattern.operator, pattern.value)
                }
            }

            is TailPattern.CustomPredicate -> {
                logs.any { log ->
                    pattern.predicate(log)
                }
            }
        }
    }

    /**
     * Checks if a log record matches an attribute condition.
     */
    private fun matchAttribute(log: LogRecordData, attrName: String, operator: String, expectedValue: Any): Boolean {
        val attributes = log.attributes
        val actualValue = attributes.get(io.opentelemetry.api.common.AttributeKey.stringKey(attrName))
            ?: attributes.get(io.opentelemetry.api.common.AttributeKey.longKey(attrName))
            ?: attributes.get(io.opentelemetry.api.common.AttributeKey.doubleKey(attrName))
            ?: attributes.get(io.opentelemetry.api.common.AttributeKey.booleanKey(attrName))
            ?: return false

        return when (operator) {
            "=" -> actualValue == expectedValue
            "!=" -> actualValue != expectedValue
            ">" -> compareValues(actualValue, expectedValue) > 0
            "<" -> compareValues(actualValue, expectedValue) < 0
            ">=" -> compareValues(actualValue, expectedValue) >= 0
            "<=" -> compareValues(actualValue, expectedValue) <= 0
            "contains" -> actualValue.toString().contains(expectedValue.toString())
            else -> false
        }
    }

    /**
     * Compares two values for numeric comparison.
     */
    @Suppress("UNCHECKED_CAST")
    private fun compareValues(actual: Any, expected: Any): Int {
        return when {
            actual is Long && expected is Number -> actual.compareTo(expected.toLong())
            actual is Double && expected is Number -> actual.compareTo(expected.toDouble())
            actual is Int && expected is Number -> actual.compareTo(expected.toInt())
            else -> actual.toString().compareTo(expected.toString())
        }
    }
}

/**
 * Simple circular buffer implementation.
 */
private class CircularBuffer<T>(private val capacity: Int) {
    private val buffer = ArrayList<T>(capacity)
    private var writeIndex = 0
    private var isFull = false

    fun add(item: T) {
        if (isFull) {
            buffer[writeIndex] = item
        } else {
            buffer.add(item)
        }

        writeIndex = (writeIndex + 1) % capacity
        if (writeIndex == 0) {
            isFull = true
        }
    }

    fun toList(): List<T> {
        return if (isFull) {
            // Return in chronological order
            buffer.subList(writeIndex, capacity) + buffer.subList(0, writeIndex)
        } else {
            buffer.toList()
        }
    }

    fun clear() {
        buffer.clear()
        writeIndex = 0
        isFull = false
    }

    fun size(): Int {
        return if (isFull) capacity else buffer.size
    }
}
