/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.autocapture

data class CoordinateBucket(
    val row: Int,
    val col: Int,
    val gridSize: Int
)

object CoordinateBucketer {
    fun bucket(x: Float, y: Float, width: Int, height: Int, gridSize: Int): CoordinateBucket? {
        if (width <= 0 || height <= 0 || gridSize <= 0) return null
        val clampedX = x.coerceIn(0f, width.toFloat() - 1f)
        val clampedY = y.coerceIn(0f, height.toFloat() - 1f)

        val col = ((clampedX / width) * gridSize).toInt().coerceIn(0, gridSize - 1)
        val row = ((clampedY / height) * gridSize).toInt().coerceIn(0, gridSize - 1)
        return CoordinateBucket(row = row, col = col, gridSize = gridSize)
    }
}
