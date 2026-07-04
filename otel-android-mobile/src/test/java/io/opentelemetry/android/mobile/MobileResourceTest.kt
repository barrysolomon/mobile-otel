/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Bundle
import io.opentelemetry.android.mobile.config.BuildId
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.api.common.AttributeKey.stringKey
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [MobileResource] — the resource construction extracted from
 * `MobileLoggerProvider.init` so symbolication Phase 1 (`app.build.id`,
 * docs/design/symbolication.md) is testable without spinning up the full
 * provider (whose flush timing is a known flaky surface under Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MobileResourceTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private fun config(extras: Map<String, String>? = null) = MobileConfig(
        serviceName = "svc",
        serviceVersion = "1.0.0",
        collectorEndpoint = "https://ingress.example.com",
        extraResourceAttributes = extras,
    )

    private fun installBuildIdStamp(value: String?) {
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
    fun `resource keeps service identity and mobile classifier`() {
        val resource = MobileResource.build(context, config(), deviceId = "dev-1")
        assertEquals("svc", resource.getAttribute(stringKey("service.name")))
        assertEquals("1.0.0", resource.getAttribute(stringKey("service.version")))
        assertEquals("mobile", resource.getAttribute(stringKey("dash0.resource.type")))
        assertEquals("dev-1", resource.getAttribute(stringKey("device.id")))
    }

    @Test
    fun `resource includes app build id from manifest stamp`() {
        installBuildIdStamp("4f9c2c30-9d2e-4c8b-8f1a-2b7d6e5a1c00")
        val resource = MobileResource.build(context, config(), deviceId = "dev-1")
        assertEquals(
            "4f9c2c30-9d2e-4c8b-8f1a-2b7d6e5a1c00",
            resource.getAttribute(stringKey("app.build.id")),
        )
    }

    @Test
    fun `resource omits app build id when unstamped`() {
        installBuildIdStamp(null)
        val resource = MobileResource.build(context, config(), deviceId = "dev-1")
        assertNull(resource.getAttribute(stringKey("app.build.id")))
    }

    @Test
    fun `extraResourceAttributes override the manifest stamp`() {
        installBuildIdStamp("stamped-id")
        val resource = MobileResource.build(
            context,
            config(extras = mapOf("app.build.id" to "caller-id")),
            deviceId = "dev-1",
        )
        assertEquals("caller-id", resource.getAttribute(stringKey("app.build.id")))
    }
}
