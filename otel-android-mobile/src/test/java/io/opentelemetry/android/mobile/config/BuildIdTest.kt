/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.config

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [BuildId] — symbolication Phase 1 (docs/design/symbolication.md).
 *
 * Android's R8 mapping id is not runtime-readable from the APK, so build
 * tooling stamps it into the manifest as `io.dash0.mobile.BUILD_ID`
 * meta-data; the SDK reads that stamp so every crash/error carries the id
 * that keys the uploaded mapping.txt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BuildIdTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    /** Re-install the app package with (or without) the BUILD_ID meta-data stamp. */
    private fun installMetaData(value: String?) {
        val packageInfo = PackageInfo().apply {
            packageName = context.packageName
            applicationInfo = ApplicationInfo().apply {
                packageName = context.packageName
                metaData = Bundle().apply {
                    if (value != null) putString(BuildId.METADATA_KEY, value)
                }
            }
        }
        Shadows.shadowOf(context.packageManager).installPackage(packageInfo)
    }

    @Test
    fun `resolve returns manifest meta-data value`() {
        installMetaData("4f9c2c30-9d2e-4c8b-8f1a-2b7d6e5a1c00")
        assertEquals("4f9c2c30-9d2e-4c8b-8f1a-2b7d6e5a1c00", BuildId.resolve(context))
    }

    @Test
    fun `resolve returns null when meta-data absent`() {
        installMetaData(null)
        assertNull(BuildId.resolve(context))
    }

    @Test
    fun `resolve returns null for blank meta-data`() {
        installMetaData("   ")
        assertNull(BuildId.resolve(context))
    }
}
