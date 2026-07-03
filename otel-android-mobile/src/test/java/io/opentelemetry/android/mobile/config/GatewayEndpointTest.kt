/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.config

import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.android.mobile.policy.PolicyEvaluator
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * N7: the remote kill switch (RemoteGate) is fed by config polling against the
 * mobile-otel gateway. RN (and native) apps usually point `collectorEndpoint`
 * at Dash0 ingress — which does not serve `/config` — so the SDK needs a
 * distinct `gatewayEndpoint` for the poller. When unset, the poller keeps its
 * historical behaviour of polling the collector endpoint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GatewayEndpointTest {

    private fun config(gatewayEndpoint: String? = null) = MobileConfig(
        serviceName = "test",
        serviceVersion = "1.0.0",
        collectorEndpoint = "https://ingress.example",
        gatewayEndpoint = gatewayEndpoint,
        // Keep the evaluator from actually polling in unit tests.
        remoteConfigEnabled = false,
    )

    @Test
    fun `gatewayEndpoint defaults to null`() {
        val cfg = MobileConfig(
            serviceName = "test",
            serviceVersion = "1.0.0",
            collectorEndpoint = "https://ingress.example",
        )
        assertNull(cfg.gatewayEndpoint)
    }

    @Test
    fun `builder sets gatewayEndpoint`() {
        val cfg = MobileConfig.builder()
            .setServiceName("test")
            .setServiceVersion("1.0.0")
            .setCollectorEndpoint("https://ingress.example")
            .setGatewayEndpoint("https://gateway.example:8080")
            .build()
        assertEquals("https://gateway.example:8080", cfg.gatewayEndpoint)
    }

    @Test
    fun `PolicyEvaluator polls the gateway endpoint when configured`() {
        val evaluator = PolicyEvaluator(
            context = ApplicationProvider.getApplicationContext(),
            config = config(gatewayEndpoint = "https://gateway.example:8080"),
        )
        assertEquals("https://gateway.example:8080", evaluator.getConfigBaseUrlForTest())
    }

    @Test
    fun `PolicyEvaluator falls back to the collector endpoint when no gateway is set`() {
        val evaluator = PolicyEvaluator(
            context = ApplicationProvider.getApplicationContext(),
            config = config(gatewayEndpoint = null),
        )
        assertEquals("https://ingress.example", evaluator.getConfigBaseUrlForTest())
    }
}
