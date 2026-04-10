// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.config

@MobileOtelDslMarker
class ExportDsl {
    var endpoint: String? = null
    var mode: ExportMode = ExportMode.CONDITIONAL
    var headers: Map<String, String>? = null
    var timeoutSeconds: Long = 30
    var maxRetries: Int = 3
    var traceIntervalSeconds: Long = 30
    var metricIntervalSeconds: Long = 60
}
