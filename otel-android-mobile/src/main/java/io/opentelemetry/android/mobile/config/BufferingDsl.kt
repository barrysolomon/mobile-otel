// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.config

@MobileOtelDslMarker
class BufferingDsl {
    var ramSize: Int = 5000
    var diskMb: Int = 50
    var ttlHours: Int = 24
}
