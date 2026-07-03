/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.config

import android.content.Context
import android.content.pm.PackageManager

/**
 * Symbolication Phase 1 (docs/design/symbolication.md): resolves the build id
 * that keys this APK's R8/ProGuard `mapping.txt`.
 *
 * Unlike iOS (where the Mach-O `LC_UUID` is readable at runtime), Android's
 * R8 mapping id lives only in build outputs — the APK carries no runtime
 * handle to it. So build tooling stamps an id into the manifest and the SDK
 * reads the stamp:
 *
 * ```xml
 * <meta-data android:name="io.dash0.mobile.BUILD_ID" android:value="${dash0BuildId}" />
 * ```
 *
 * typically driven by a Gradle line that generates one UUID per build and
 * uses the same value to key the mapping.txt upload:
 *
 * ```kotlin
 * android.defaultConfig.manifestPlaceholders["dash0BuildId"] = UUID.randomUUID().toString()
 * ```
 *
 * Apps can also override per-init via `extraResourceAttributes["app.build.id"]`
 * (that merge happens after this stamp in [io.opentelemetry.android.mobile.MobileResource]).
 */
object BuildId {
    /** Manifest meta-data key the build stamp is read from. */
    const val METADATA_KEY: String = "io.dash0.mobile.BUILD_ID"

    /** Returns the stamped build id, or null when the app is unstamped. */
    fun resolve(context: Context): String? = try {
        context.packageManager
            .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
            .metaData
            ?.getString(METADATA_KEY)
            ?.takeIf { it.isNotBlank() }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}
