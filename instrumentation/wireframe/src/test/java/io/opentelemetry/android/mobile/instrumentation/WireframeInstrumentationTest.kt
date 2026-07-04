// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.mockk.mockk
import io.opentelemetry.sdk.testing.junit4.OpenTelemetryRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class WireframeInstrumentationTest {

    @get:Rule val otelRule = OpenTelemetryRule.create()

    @Test fun `instrumentationName is correct`() {
        assertEquals(
            "io.opentelemetry.android.mobile.wireframe",
            WireframeInstrumentation().instrumentationName
        )
    }

    @Test fun `install registers callbacks`() {
        val app = mockk<Application>(relaxed = true)
        val hub = WindowEventHub()
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        val inst = WireframeInstrumentation()
        assertFalse(inst.isInstalled)

        inst.install(app, ctx)
        assertTrue(inst.isInstalled)

        inst.uninstall()
    }

    @Test fun `install with disabled config is a no-op`() {
        val app = mockk<Application>(relaxed = true)
        val hub = WindowEventHub()
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        val inst = WireframeInstrumentation(WireframeConfig(enabled = false))
        inst.install(app, ctx)
        assertFalse(inst.isInstalled)
    }

    @Test fun `uninstall cleans up state`() {
        val app = mockk<Application>(relaxed = true)
        val hub = WindowEventHub()
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        val inst = WireframeInstrumentation()
        inst.install(app, ctx)
        assertTrue(inst.isInstalled)

        inst.uninstall()
        assertFalse(inst.isInstalled)
        assertNull(inst.trackedActivity)
    }

    @Test fun `captureWireframe with no activity is a no-op`() {
        val app = mockk<Application>(relaxed = true)
        val hub = WindowEventHub()
        val ctx = InstrumentationContext(otelRule.openTelemetry, DefaultMobileSessionProvider(), hub, app)

        val inst = WireframeInstrumentation()
        inst.install(app, ctx)

        // Should not crash.
        inst.captureWireframe("test")

        inst.uninstall()
    }

    @Test fun `captureWireframe when disabled is a no-op`() {
        val inst = WireframeInstrumentation(WireframeConfig(enabled = false))
        inst.captureWireframe("test")
    }

    @Test fun `buildTree captures simple view`() {
        val controller = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        val activity = controller.get()

        val button = Button(activity).apply {
            layoutParams = ViewGroup.LayoutParams(200, 80)
            id = View.generateViewId()
        }

        val inst = WireframeInstrumentation(WireframeConfig(
            includeResourceIds = false,
            includeClickableState = true
        ))

        val node = inst.buildTree(button, 0)
        assertEquals("Button", node.type)
        assertTrue(node.clickable == true)
        assertTrue(node.children.isEmpty())

        controller.destroy()
    }

    @Test fun `buildTree captures nested hierarchy`() {
        val controller = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        val activity = controller.get()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val child1 = Button(activity).apply {
            layoutParams = ViewGroup.LayoutParams(200, 80)
        }
        val child2 = TextView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(200, 40)
        }
        root.addView(child1)
        root.addView(child2)

        val inst = WireframeInstrumentation()
        val node = inst.buildTree(root, 0)

        assertEquals("LinearLayout", node.type)
        assertEquals(2, node.children.size)
        assertEquals("Button", node.children[0].type)
        assertEquals("TextView", node.children[1].type)

        controller.destroy()
    }

    @Test fun `buildTree truncates at maxDepth`() {
        val controller = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        val activity = controller.get()

        val inner = FrameLayout(activity).apply {
            addView(Button(activity))
        }
        val outer = FrameLayout(activity).apply { addView(inner) }

        val inst = WireframeInstrumentation(WireframeConfig(maxDepth = 1))
        val node = inst.buildTree(outer, 0)

        // Depth 0 = outer, depth 1 = inner (at limit, has children → truncated)
        assertEquals("FrameLayout", node.type)
        assertEquals(1, node.children.size)
        val innerNode = node.children[0]
        assertEquals("FrameLayout", innerNode.type)
        assertTrue(innerNode.truncated, "Should be truncated at maxDepth")
        assertTrue(innerNode.children.isEmpty(), "Children should not be traversed past maxDepth")

        controller.destroy()
    }

    @Test fun `buildTree skips invisible views`() {
        val controller = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        val activity = controller.get()

        val root = LinearLayout(activity)
        val visible = Button(activity)
        val gone = TextView(activity).apply { visibility = View.GONE }
        val invisible = TextView(activity).apply { visibility = View.INVISIBLE }
        root.addView(visible)
        root.addView(gone)
        root.addView(invisible)

        val inst = WireframeInstrumentation()
        val node = inst.buildTree(root, 0)

        // Only the visible Button should appear.
        assertEquals(1, node.children.size)
        assertEquals("Button", node.children[0].type)

        controller.destroy()
    }

    @Test fun `buildTree extracts hint from EditText`() {
        val controller = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        val activity = controller.get()

        val editText = EditText(activity).apply {
            hint = "Enter email"
            setText("secret@test.com")
        }

        val inst = WireframeInstrumentation(WireframeConfig(includeTextHints = true))
        val node = inst.buildTree(editText, 0)

        assertEquals("Enter email", node.hint)
        // The actual text "secret@test.com" must NOT appear anywhere in the node.
        val json = node.toJson().toString()
        assertFalse(json.contains("secret@test.com"), "User-entered text must not appear in wireframe")

        controller.destroy()
    }

    @Test fun `buildTree respects includeTextHints false`() {
        val controller = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        val activity = controller.get()

        val editText = EditText(activity).apply { hint = "Enter email" }

        val inst = WireframeInstrumentation(WireframeConfig(includeTextHints = false))
        val node = inst.buildTree(editText, 0)

        assertNull(node.hint)

        controller.destroy()
    }

    @Test fun `node tree serializes to valid JSON`() {
        val child = WireframeNode(type = "Button", bounds = intArrayOf(10, 10, 90, 40), clickable = true)
        val root = WireframeNode(type = "FrameLayout", bounds = intArrayOf(0, 0, 100, 100), children = listOf(child))
        val json = root.toJson().toString()

        // Basic structural validation.
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        assertTrue(json.contains(""""type":"FrameLayout""""))
        assertTrue(json.contains(""""type":"Button""""))
        assertTrue(json.contains(""""children":["""))
    }

    @Test fun `default version is 1_0_0`() {
        assertEquals("1.0.0", WireframeInstrumentation().instrumentationVersion)
    }
}
