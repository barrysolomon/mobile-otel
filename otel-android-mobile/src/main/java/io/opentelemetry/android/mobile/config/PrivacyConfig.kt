/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
package io.opentelemetry.android.mobile.config

data class PrivacyConfig(
    val allowFleetFlush: Boolean = true,
    val allowFleetScreenshot: Boolean = false,
    val allowFleetSampling: Boolean = true,
    val allowCrossDeviceTrace: Boolean = false,
    val minCohortSize: Int = 10,
)
