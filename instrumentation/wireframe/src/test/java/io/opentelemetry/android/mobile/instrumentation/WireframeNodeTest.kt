// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WireframeNodeTest {

    @Test fun `minimal node serializes correctly`() {
        val node = WireframeNode(type = "Button", bounds = intArrayOf(10, 20, 110, 70))
        val json = node.toJson().toString()
        assertEquals("""{"type":"Button","bounds":[10,20,110,70]}""", json)
    }

    @Test fun `node with id serializes correctly`() {
        val node = WireframeNode(type = "Button", bounds = intArrayOf(0, 0, 100, 50), id = "btn_ok")
        val json = node.toJson().toString()
        assertTrue(json.contains(""""id":"btn_ok""""))
    }

    @Test fun `node with hint serializes correctly`() {
        val node = WireframeNode(type = "EditText", bounds = intArrayOf(0, 0, 200, 50), hint = "Enter name")
        val json = node.toJson().toString()
        assertTrue(json.contains(""""hint":"Enter name""""))
    }

    @Test fun `node with content description serializes correctly`() {
        val node = WireframeNode(type = "ImageView", bounds = intArrayOf(0, 0, 50, 50), contentDescription = "Logo")
        val json = node.toJson().toString()
        assertTrue(json.contains(""""cd":"Logo""""))
    }

    @Test fun `clickable and enabled state serialize correctly`() {
        val node = WireframeNode(type = "Button", bounds = intArrayOf(0, 0, 100, 50), clickable = true, enabled = false)
        val json = node.toJson().toString()
        assertTrue(json.contains(""""clickable":true"""))
        assertTrue(json.contains(""""enabled":false"""))
    }

    @Test fun `null clickable and enabled are omitted`() {
        val node = WireframeNode(type = "View", bounds = intArrayOf(0, 0, 100, 50))
        val json = node.toJson().toString()
        assertFalse(json.contains("clickable"))
        assertFalse(json.contains("enabled"))
    }

    @Test fun `truncated flag serializes correctly`() {
        val node = WireframeNode(type = "FrameLayout", bounds = intArrayOf(0, 0, 100, 100), truncated = true)
        val json = node.toJson().toString()
        assertTrue(json.contains(""""truncated":true"""))
    }

    @Test fun `truncated false is omitted`() {
        val node = WireframeNode(type = "View", bounds = intArrayOf(0, 0, 100, 100), truncated = false)
        val json = node.toJson().toString()
        assertFalse(json.contains("truncated"))
    }

    @Test fun `children serialize correctly`() {
        val child1 = WireframeNode(type = "Button", bounds = intArrayOf(10, 10, 90, 40))
        val child2 = WireframeNode(type = "TextView", bounds = intArrayOf(10, 50, 90, 80))
        val parent = WireframeNode(type = "LinearLayout", bounds = intArrayOf(0, 0, 100, 100), children = listOf(child1, child2))

        val json = parent.toJson().toString()
        assertTrue(json.contains(""""children":["""))
        assertTrue(json.contains(""""type":"Button""""))
        assertTrue(json.contains(""""type":"TextView""""))
    }

    @Test fun `nested tree serializes correctly`() {
        val leaf = WireframeNode(type = "Button", bounds = intArrayOf(20, 20, 80, 40), id = "btn_leaf")
        val mid = WireframeNode(type = "FrameLayout", bounds = intArrayOf(10, 10, 90, 50), children = listOf(leaf))
        val root = WireframeNode(type = "LinearLayout", bounds = intArrayOf(0, 0, 100, 100), children = listOf(mid))

        val json = root.toJson().toString()
        // Verify nesting structure.
        assertTrue(json.contains(""""type":"LinearLayout""""))
        assertTrue(json.contains(""""type":"FrameLayout""""))
        assertTrue(json.contains(""""type":"Button""""))
        assertTrue(json.contains(""""id":"btn_leaf""""))
    }

    @Test fun `empty children list is omitted`() {
        val node = WireframeNode(type = "View", bounds = intArrayOf(0, 0, 100, 100))
        val json = node.toJson().toString()
        assertFalse(json.contains("children"))
    }

    @Test fun `special characters in strings are escaped`() {
        val node = WireframeNode(type = "View", bounds = intArrayOf(0, 0, 100, 100), hint = "Line1\nLine2")
        val json = node.toJson().toString()
        assertTrue(json.contains("""Line1\nLine2"""))
        assertFalse(json.contains("\n"))
    }

    @Test fun `quotes in strings are escaped`() {
        val node = WireframeNode(type = "View", bounds = intArrayOf(0, 0, 100, 100), hint = """Say "hello"""")
        val json = node.toJson().toString()
        assertTrue(json.contains("""Say \"hello\""""))
    }

    @Test fun `escapeJson handles all special chars`() {
        assertEquals("""hello""", WireframeNode.escapeJson("hello"))
        assertEquals("""say \"hi\"""", WireframeNode.escapeJson("""say "hi""""))
        assertEquals("""back\\slash""", WireframeNode.escapeJson("""back\slash"""))
        assertEquals("""new\\nline""", WireframeNode.escapeJson("new\\nline"))
        assertEquals("""tab\\there""", WireframeNode.escapeJson("tab\\there"))
    }

    @Test fun `equality works with same data`() {
        val a = WireframeNode(type = "Button", bounds = intArrayOf(0, 0, 100, 50), id = "btn")
        val b = WireframeNode(type = "Button", bounds = intArrayOf(0, 0, 100, 50), id = "btn")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun `full node with all fields serializes correctly`() {
        val node = WireframeNode(
            type = "Button",
            bounds = intArrayOf(10, 20, 200, 80),
            id = "btn_submit",
            hint = "Submit",
            contentDescription = "Submit form",
            clickable = true,
            enabled = true,
            children = listOf(
                WireframeNode(type = "TextView", bounds = intArrayOf(15, 25, 195, 75))
            )
        )
        val json = node.toJson().toString()
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(json.contains(""""type":"Button""""))
        assertTrue(json.contains(""""bounds":[10,20,200,80]"""))
        assertTrue(json.contains(""""id":"btn_submit""""))
        assertTrue(json.contains(""""hint":"Submit""""))
        assertTrue(json.contains(""""cd":"Submit form""""))
        assertTrue(json.contains(""""clickable":true"""))
        assertTrue(json.contains(""""enabled":true"""))
        assertTrue(json.contains(""""children":["""))
    }
}
