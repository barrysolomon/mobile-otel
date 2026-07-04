/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.config

import io.opentelemetry.android.mobile.instrumentation.Incubating
import io.opentelemetry.api.logs.Severity

/**
 * Controls what gets buffered when the device is offline.
 *
 * This is an overlay on top of ExportMode — it modifies buffering behavior
 * during offline periods regardless of the underlying export mode.
 */
@Incubating
enum class OfflinePolicy {
    /**
     * Buffer all events normally, even when offline. This is the default.
     */
    BUFFER_ALL,

    /**
     * Only buffer events with severity >= ERROR when offline.
     * Non-error events (INFO, WARN, DEBUG) are dropped to conserve disk space.
     * When connectivity is restored, normal buffering resumes.
     */
    ERROR_ONLY,

    /**
     * Only buffer events with severity >= WARN when offline.
     */
    WARN_AND_ABOVE,

    /**
     * Drop ALL events when offline. Extreme battery conservation mode.
     */
    DROP_ALL
}

/**
 * Returns the minimum severity that should be buffered under this policy.
 * Returns null for BUFFER_ALL (no filtering) and DROP_ALL (drop everything).
 */
fun OfflinePolicy.minBufferSeverity(): Severity? = when (this) {
    OfflinePolicy.BUFFER_ALL -> null
    OfflinePolicy.ERROR_ONLY -> Severity.ERROR
    OfflinePolicy.WARN_AND_ABOVE -> Severity.WARN
    OfflinePolicy.DROP_ALL -> null
}

/**
 * Returns true if this policy drops all events when offline.
 */
fun OfflinePolicy.dropsAll(): Boolean = this == OfflinePolicy.DROP_ALL
