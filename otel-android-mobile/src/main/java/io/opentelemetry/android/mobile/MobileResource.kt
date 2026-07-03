/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile

import android.content.Context
import io.opentelemetry.android.mobile.config.BuildId
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.sdk.resources.Resource

/**
 * Builds the OTel [Resource] shared by all three providers (logger, tracer,
 * meter). Extracted from `MobileLoggerProvider.init` so resource contents —
 * notably the symbolication build id (docs/design/symbolication.md) — are
 * unit-testable without spinning up the full provider.
 */
internal object MobileResource {

    fun build(context: Context, config: MobileConfig, deviceId: String): Resource =
        Resource.getDefault().merge(
            Resource.builder()
                .put("service.name", config.serviceName)
                .put("service.version", config.serviceVersion)
                .put("device.id", deviceId)
                .put("os.name", "android")
                .put("os.version", android.os.Build.VERSION.RELEASE)
                .put("os.description", "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                .put("device.model.name", android.os.Build.MODEL)
                .put("device.manufacturer", android.os.Build.MANUFACTURER)
                // Dash0 resource-type classifier. Without this, Dash0 routes
                // by `telemetry.sdk.language` alone and can surface mobile
                // data under the wrong UI category. Setting it explicitly
                // keeps Android + iOS co-located under the Mobile view.
                .put("dash0.resource.type", "mobile")
                .apply {
                    // Symbolication Phase 1: the build-time stamp that keys
                    // this APK's mapping.txt, so crashes/errors are matchable
                    // to the mapping that deobfuscates them. Absent when the
                    // app is unstamped.
                    BuildId.resolve(context)?.let { put("app.build.id", it) }
                    // Fold in caller-provided attributes (e.g. React Native
                    // bridge sets telemetry.distro.name/version). These merge
                    // last so callers can override the stamp above.
                    config.extraResourceAttributes?.forEach { (key, value) ->
                        if (key.isNotBlank()) put(key, value)
                    }
                }
                .build()
        )
}
