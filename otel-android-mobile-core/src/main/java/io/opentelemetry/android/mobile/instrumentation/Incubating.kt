// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

/**
 * Marks an API as incubating: it may change or be removed in future releases
 * without a deprecation cycle.
 *
 * Consumers using `@Incubating` APIs must explicitly opt in with
 * `@OptIn(Incubating::class)` or suppress the warning.
 *
 * Following OpenTelemetry contrib convention for experimental APIs.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "This API is incubating and may change in future releases without notice."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class Incubating
