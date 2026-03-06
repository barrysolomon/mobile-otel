// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import android.app.Application
import android.util.Log
import io.opentelemetry.android.mobile.errors.ErrorConfig
import io.opentelemetry.android.mobile.errors.ErrorInstrumentation

/**
 * [MobileInstrumentation] wrapper for [ErrorInstrumentation].
 *
 * Delegates to the existing [ErrorInstrumentation] singleton via its
 * [ErrorInstrumentation.initialize] / [ErrorInstrumentation.getInstance] API.
 * If initialization fails (e.g. in a test environment without a full Android
 * runtime), the failure is logged as a warning and [install] / [uninstall]
 * complete silently so the host app does not crash.
 */
class ErrorsInstrumentation : MobileInstrumentation {

    override val instrumentationName = "io.opentelemetry.android.mobile.errors"

    private var previousUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null

    override fun install(application: Application, context: InstrumentationContext) {
        try {
            val logger = context.logger(instrumentationName)
            // Snapshot the handler that was installed before ErrorInstrumentation replaces it,
            // so we can restore it in uninstall().
            previousUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
            ErrorInstrumentation.initialize(
                config = ErrorConfig.default(),
                logger = logger,
                onFlush = null
            )
        } catch (e: Exception) {
            Log.w("ErrorsInstrumentation", "Failed to initialize ErrorInstrumentation: ${e.message}")
        }
    }

    override fun uninstall() {
        try {
            // Restore the handler that was in place before install() so successive
            // test runs don't accumulate stacked exception handlers.
            previousUncaughtExceptionHandler?.let {
                Thread.setDefaultUncaughtExceptionHandler(it)
            }
        } catch (e: Exception) {
            Log.w("ErrorsInstrumentation", "Failed to uninstall ErrorInstrumentation: ${e.message}")
        } finally {
            previousUncaughtExceptionHandler = null
        }
    }
}
