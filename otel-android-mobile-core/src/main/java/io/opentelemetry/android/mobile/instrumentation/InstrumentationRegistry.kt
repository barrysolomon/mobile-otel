// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.util.Log

/**
 * Holds all active [MobileInstrumentation] instances and coordinates
 * their lifecycle.
 *
 * Created by [OTelMobileBuilder.build] and owned by [OTelMobileHandle].
 * Calling [install] activates every registered instrumentation;
 * calling [uninstall] deactivates them in reverse registration order.
 *
 * Conflict resolution: if a [MobileInstrumentation] is annotated with
 * [@Supersedes][Supersedes], any [UpstreamInstrumentationAdapter] whose
 * [instrumentationName] appears in the annotation's [names][Supersedes.names]
 * list is skipped during install.
 */
@Incubating
class InstrumentationRegistry(
    private val instrumentations: List<MobileInstrumentation>
) {
    private val installed = mutableListOf<MobileInstrumentation>()

    var sessionProvider: MobileSessionProvider? = null
        private set

    /**
     * Activates registered instrumentations by calling
     * [MobileInstrumentation.install] on each in registration order.
     *
     * Upstream instrumentations whose names are superseded by a native
     * [MobileInstrumentation] (via [@Supersedes][Supersedes]) are skipped.
     */
    fun install(application: Application, context: InstrumentationContext) {
        sessionProvider = context.sessionProvider
        val supersededNames = mutableSetOf<String>()
        for (inst in instrumentations) {
            val ann = inst::class.java.getAnnotation(Supersedes::class.java)
            if (ann != null) {
                supersededNames.addAll(ann.names)
            }
        }

        for (inst in instrumentations) {
            if (inst.instrumentationName in supersededNames
                && inst::class.java.getAnnotation(Supersedes::class.java) == null
            ) {
                Log.i(TAG, "Skipping ${inst.instrumentationName} -- superseded by a MobileInstrumentation module")
                continue
            }
            // Fault-isolate each module: a single failing install() must not
            // abort the whole SDK init nor crash the host. Skip + log, continue.
            try {
                inst.install(application, context)
                installed.add(inst)
            } catch (t: Throwable) {
                Log.w(TAG, "Skipping ${inst.instrumentationName} -- install() failed", t)
            }
        }
    }

    /**
     * Deactivates all installed instrumentations by calling
     * [MobileInstrumentation.uninstall] on each in reverse installation order.
     */
    fun uninstall() {
        installed.asReversed().forEach { it.uninstall() }
        installed.clear()
    }

    /**
     * Looks up a registered instrumentation by its [MobileInstrumentation.instrumentationName].
     * Returns `null` if no instrumentation with that name is registered.
     *
     * Used by journey-aware capture wiring (UJ-001/UJ-002) to invoke
     * specific instrumentations from the public [OTelMobile] entry points
     * without baking module-specific dependencies into the core API.
     */
    fun findByName(name: String): MobileInstrumentation? =
        instrumentations.firstOrNull { it.instrumentationName == name }

    companion object {
        private const val TAG = "InstrumentationRegistry"
    }
}
