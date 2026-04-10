// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.config

import io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation
import io.opentelemetry.android.mobile.instrumentation.OTelMobileBuilder

@MobileOtelDslMarker
class InstrumentationsDsl {
    private var discover: DiscoverMode = DiscoverMode.NONE
    private val explicit = mutableListOf<MobileInstrumentation>()

    /** Discover all MobileInstrumentation + AndroidInstrumentation via ServiceLoader. */
    fun discoverAll() { discover = DiscoverMode.ALL }

    /** Discover only MobileInstrumentation (skip upstream). */
    fun discoverOwn() { discover = DiscoverMode.OWN_ONLY }

    /** Add a specific instrumentation instance. */
    fun add(instrumentation: MobileInstrumentation) { explicit.add(instrumentation) }

    internal fun applyTo(builder: OTelMobileBuilder) {
        explicit.forEach { builder.addInstrumentation(it) }
        when (discover) {
            DiscoverMode.ALL -> builder.discoverAllInstrumentations()
            DiscoverMode.OWN_ONLY -> builder.discoverInstrumentations()
            DiscoverMode.NONE -> {}
        }
    }

    private enum class DiscoverMode { NONE, OWN_ONLY, ALL }
}
