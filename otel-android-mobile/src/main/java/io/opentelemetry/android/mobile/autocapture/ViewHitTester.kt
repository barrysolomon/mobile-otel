package io.opentelemetry.android.mobile.autocapture

import android.view.View
import android.view.ViewGroup

data class HitTestResult(
    val view: View?,
    val confidence: String
)

object ViewHitTester {
    fun hitTest(root: View, x: Int, y: Int, maxDepth: Int): HitTestResult {
        val found = findDeepestClickable(root, x, y, 0, maxDepth)
        if (found != null) {
            return HitTestResult(found, "high")
        }

        val fallback = findDeepestView(root, x, y, 0, maxDepth)
        return HitTestResult(fallback, if (fallback != null) "medium" else "low")
    }

    private fun findDeepestClickable(view: View, x: Int, y: Int, depth: Int, maxDepth: Int): View? {
        if (!isPointInside(view, x, y) || !view.isShown || depth > maxDepth) return null

        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)
                val hit = findDeepestClickable(child, x, y, depth + 1, maxDepth)
                if (hit != null) return hit
            }
        }

        return if (view.isClickable || view.isLongClickable) view else null
    }

    private fun findDeepestView(view: View, x: Int, y: Int, depth: Int, maxDepth: Int): View? {
        if (!isPointInside(view, x, y) || !view.isShown || depth > maxDepth) return null

        if (view is ViewGroup) {
            for (i in view.childCount - 1 downTo 0) {
                val child = view.getChildAt(i)
                val hit = findDeepestView(child, x, y, depth + 1, maxDepth)
                if (hit != null) return hit
            }
        }

        return view
    }

    private fun isPointInside(view: View, x: Int, y: Int): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val left = location[0]
        val top = location[1]
        val right = left + view.width
        val bottom = top + view.height
        return x >= left && x <= right && y >= top && y <= bottom
    }
}
