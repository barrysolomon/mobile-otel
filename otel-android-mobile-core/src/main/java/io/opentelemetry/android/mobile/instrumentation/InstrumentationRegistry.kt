// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application

/**
 * Holds all active [MobileInstrumentation] instances and coordinates
 * their lifecycle.
 *
 * Created by [OTelMobileBuilder.build] and owned by [OTelMobileHandle].
 * Calling [install] activates every registered instrumentation;
 * calling [uninstall] deactivates them in reverse registration order.
 */
@Incubating
class InstrumentationRegistry(
    private val instrumentations: List<MobileInstrumentation>
) {
    /**
     * Activates all registered instrumentations by calling
     * [MobileInstrumentation.install] on each in registration order.
     */
    fun install(application: Application, context: InstrumentationContext) {
        instrumentations.forEach { it.install(application, context) }
    }

    /**
     * Deactivates all registered instrumentations by calling
     * [MobileInstrumentation.uninstall] on each in reverse registration order.
     */
    fun uninstall() {
        instrumentations.asReversed().forEach { it.uninstall() }
    }
}
