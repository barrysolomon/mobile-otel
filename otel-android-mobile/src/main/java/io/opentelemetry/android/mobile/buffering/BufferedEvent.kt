/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.os.SystemClock
import io.opentelemetry.sdk.logs.data.LogRecordData

/**
 * Wraps a [LogRecordData] with a monotonic timestamp for clock-skew-safe
 * flush window calculations.
 *
 * [monotonicMs] is captured from [SystemClock.elapsedRealtime] at event
 * creation time. It is immune to wall-clock adjustments (NTP, user changes)
 * and is used internally for window filtering. The original OTel timestamps
 * on [logRecord] are preserved unchanged for export.
 *
 * Overhead: 8 bytes (one Long) per buffered event.
 */
internal data class BufferedEvent(
    val logRecord: LogRecordData,
    val monotonicMs: Long = SystemClock.elapsedRealtime()
)
