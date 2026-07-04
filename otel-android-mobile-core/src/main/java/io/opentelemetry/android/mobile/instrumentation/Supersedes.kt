// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

/**
 * Declares that this [MobileInstrumentation] supersedes one or more upstream
 * [io.opentelemetry.android.instrumentation.AndroidInstrumentation] modules.
 *
 * When both a superseding module and the superseded upstream module are
 * discovered via [OTelMobileBuilder.discoverAllInstrumentations], the
 * [InstrumentationRegistry] installs the superseding module and skips
 * the upstream one.
 *
 * Values must match the upstream module's
 * [io.opentelemetry.android.instrumentation.AndroidInstrumentation.name] exactly.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Supersedes(vararg val names: String)
