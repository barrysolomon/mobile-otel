package io.opentelemetry.android.mobile.breadcrumb

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe circular buffer for storing journey breadcrumbs.
 *
 * This buffer maintains a fixed-size history of user actions (breadcrumbs) using
 * a FIFO eviction policy. When the buffer is full, the oldest breadcrumb is removed
 * to make room for new ones.
 *
 * The buffer is thread-safe and can be accessed concurrently from multiple threads.
 *
 * @property maxSize Maximum number of breadcrumbs to store (default: 50)
 */
class JourneyBreadcrumbBuffer(
    private val maxSize: Int = 50
) {
    private val buffer = ArrayDeque<JourneyBreadcrumb>(maxSize)
    private val lock = ReentrantReadWriteLock()
    private val json = Json { prettyPrint = false }

    /**
     * Add a breadcrumb to the buffer.
     *
     * If the buffer is full, the oldest breadcrumb will be removed (FIFO).
     *
     * @param breadcrumb The breadcrumb to add
     */
    fun add(breadcrumb: JourneyBreadcrumb) {
        lock.write {
            if (buffer.size >= maxSize) {
                buffer.removeFirst()
            }
            buffer.addLast(breadcrumb)
        }
    }

    /**
     * Add multiple breadcrumbs at once.
     *
     * @param breadcrumbs Collection of breadcrumbs to add
     */
    fun addAll(breadcrumbs: Collection<JourneyBreadcrumb>) {
        breadcrumbs.forEach { add(it) }
    }

    /**
     * Get all breadcrumbs as a list (ordered oldest to newest).
     *
     * @return Immutable list of breadcrumbs
     */
    fun toList(): List<JourneyBreadcrumb> {
        return lock.read {
            buffer.toList()
        }
    }

    /**
     * Get the last N breadcrumbs.
     *
     * @param count Number of breadcrumbs to retrieve
     * @return List of last N breadcrumbs (or fewer if buffer has less)
     */
    fun takeLast(count: Int): List<JourneyBreadcrumb> {
        return lock.read {
            buffer.takeLast(count)
        }
    }

    /**
     * Get breadcrumbs from a specific time window.
     *
     * @param windowMs Time window in milliseconds (e.g., 120000 for last 2 minutes)
     * @return List of breadcrumbs within the time window
     */
    fun getWindow(windowMs: Long): List<JourneyBreadcrumb> {
        val threshold = System.currentTimeMillis() - windowMs
        return lock.read {
            buffer.filter { it.timestamp >= threshold }
        }
    }

    /**
     * Serialize breadcrumbs to JSON string.
     *
     * @return JSON representation of all breadcrumbs
     */
    fun toJson(): String {
        return lock.read {
            json.encodeToString(buffer.toList())
        }
    }

    /**
     * Get the current size of the buffer.
     *
     * @return Number of breadcrumbs currently in the buffer
     */
    fun size(): Int {
        return lock.read {
            buffer.size
        }
    }

    /**
     * Check if the buffer is empty.
     *
     * @return True if buffer has no breadcrumbs
     */
    fun isEmpty(): Boolean {
        return lock.read {
            buffer.isEmpty()
        }
    }

    /**
     * Clear all breadcrumbs from the buffer.
     */
    fun clear() {
        lock.write {
            buffer.clear()
        }
    }

    /**
     * Get the first (oldest) breadcrumb, or null if buffer is empty.
     *
     * @return Oldest breadcrumb or null
     */
    fun first(): JourneyBreadcrumb? {
        return lock.read {
            buffer.firstOrNull()
        }
    }

    /**
     * Get the last (newest) breadcrumb, or null if buffer is empty.
     *
     * @return Newest breadcrumb or null
     */
    fun last(): JourneyBreadcrumb? {
        return lock.read {
            buffer.lastOrNull()
        }
    }

    /**
     * Get the total duration covered by breadcrumbs (first to last).
     *
     * @return Duration in milliseconds, or 0 if buffer has less than 2 breadcrumbs
     */
    fun duration(): Long {
        return lock.read {
            if (buffer.size < 2) {
                0L
            } else {
                buffer.last().timestamp - buffer.first().timestamp
            }
        }
    }

    /**
     * Get breadcrumbs of a specific type.
     *
     * @param type The breadcrumb type to filter by
     * @return List of breadcrumbs matching the type
     */
    fun filterByType(type: BreadcrumbType): List<JourneyBreadcrumb> {
        return lock.read {
            buffer.filter { it.type == type }
        }
    }

    /**
     * Get breadcrumbs for a specific screen.
     *
     * @param screen The screen name to filter by
     * @return List of breadcrumbs for that screen
     */
    fun filterByScreen(screen: String): List<JourneyBreadcrumb> {
        return lock.read {
            buffer.filter { it.screen == screen }
        }
    }

    /**
     * Get a summary of breadcrumbs for logging.
     *
     * @return Summary string with count, duration, and type breakdown
     */
    fun summary(): String {
        return lock.read {
            if (buffer.isEmpty()) {
                "No breadcrumbs"
            } else {
                val typeCounts = buffer.groupingBy { it.type }.eachCount()
                val durationSec = duration() / 1000
                "Breadcrumbs: ${buffer.size}, Duration: ${durationSec}s, " +
                        "Types: ${typeCounts.entries.joinToString { "${it.key}=${it.value}" }}"
            }
        }
    }

    companion object {
        /**
         * Create a breadcrumb buffer with custom size.
         *
         * @param maxSize Maximum number of breadcrumbs
         * @return New breadcrumb buffer
         */
        fun create(maxSize: Int = 50): JourneyBreadcrumbBuffer {
            require(maxSize > 0) { "maxSize must be positive" }
            return JourneyBreadcrumbBuffer(maxSize)
        }
    }
}
