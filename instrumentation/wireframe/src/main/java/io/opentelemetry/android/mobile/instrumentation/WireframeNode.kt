// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

/**
 * A single node in a wireframe view-hierarchy tree.
 *
 * Each node represents one [android.view.View] and carries enough geometry and metadata
 * to reconstruct a wireframe rendering without any pixel data.
 *
 * Serialized to compact JSON via [toJson] — no external serialization library required.
 *
 * Example output:
 * ```json
 * {"type":"Button","bounds":[340,1900,740,2000],"id":"btn_book","clickable":true}
 * ```
 */
@Incubating
data class WireframeNode(
    /** Simple class name (e.g., "Button", "RecyclerView", "ConstraintLayout"). */
    val type: String,
    /** Bounds as [left, top, right, bottom] in window coordinates. */
    val bounds: IntArray,
    /** Android resource ID name, if available and config permits (e.g., "btn_book"). */
    val id: String? = null,
    /** Hint/placeholder text (never user-entered content). */
    val hint: String? = null,
    /** Accessibility content description. */
    val contentDescription: String? = null,
    /** Whether the view is clickable. Null if config omits clickable state. */
    val clickable: Boolean? = null,
    /** Whether the view is enabled. Null if config omits clickable state. */
    val enabled: Boolean? = null,
    /** Whether this node's children were truncated due to depth limit. */
    val truncated: Boolean = false,
    /** Child view nodes. */
    val children: List<WireframeNode> = emptyList()
) {
    /**
     * Serialize this node (and its subtree) to a compact JSON string.
     * Hand-rolled to avoid pulling in a JSON library dependency.
     */
    fun toJson(sb: StringBuilder = StringBuilder()): StringBuilder {
        sb.append('{')

        sb.append("\"type\":\"").append(escapeJson(type)).append('"')

        sb.append(",\"bounds\":[")
            .append(bounds[0]).append(',')
            .append(bounds[1]).append(',')
            .append(bounds[2]).append(',')
            .append(bounds[3]).append(']')

        id?.let { sb.append(",\"id\":\"").append(escapeJson(it)).append('"') }
        hint?.let { sb.append(",\"hint\":\"").append(escapeJson(it)).append('"') }
        contentDescription?.let { sb.append(",\"cd\":\"").append(escapeJson(it)).append('"') }
        clickable?.let { sb.append(",\"clickable\":").append(it) }
        enabled?.let { sb.append(",\"enabled\":").append(it) }

        if (truncated) {
            sb.append(",\"truncated\":true")
        }

        if (children.isNotEmpty()) {
            sb.append(",\"children\":[")
            children.forEachIndexed { index, child ->
                if (index > 0) sb.append(',')
                child.toJson(sb)
            }
            sb.append(']')
        }

        sb.append('}')
        return sb
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WireframeNode) return false
        return type == other.type &&
            bounds.contentEquals(other.bounds) &&
            id == other.id &&
            hint == other.hint &&
            contentDescription == other.contentDescription &&
            clickable == other.clickable &&
            enabled == other.enabled &&
            truncated == other.truncated &&
            children == other.children
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + bounds.contentHashCode()
        result = 31 * result + (id?.hashCode() ?: 0)
        result = 31 * result + (hint?.hashCode() ?: 0)
        result = 31 * result + (contentDescription?.hashCode() ?: 0)
        result = 31 * result + (clickable?.hashCode() ?: 0)
        result = 31 * result + (enabled?.hashCode() ?: 0)
        result = 31 * result + truncated.hashCode()
        result = 31 * result + children.hashCode()
        return result
    }

    companion object {
        /** Escape special JSON characters. */
        internal fun escapeJson(s: String): String {
            val sb = StringBuilder(s.length)
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c.code < 0x20) {
                        sb.append("\\u").append(String.format("%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
            return sb.toString()
        }
    }
}
