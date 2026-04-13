// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation.amplifydatastore

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AmplifyDataStoreConfigTest {

    @Test
    fun `defaults - all signals enabled`() {
        val config = AmplifyDataStoreConfig()
        assertTrue(config.enabled)
        assertTrue(config.captureOutboxEvents)
        assertTrue(config.captureSyncSpans)
        assertTrue(config.captureSyncMetrics)
        assertTrue(config.captureConflicts)
        assertTrue(config.attachNetworkState)
    }

    @Test
    fun `defaults - syncTimeoutMs is 60 seconds`() {
        val config = AmplifyDataStoreConfig()
        assertEquals(60_000L, config.syncTimeoutMs)
    }

    @Test
    fun `syncTimeoutMs must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            AmplifyDataStoreConfig(syncTimeoutMs = 0)
        }
    }

    @Test
    fun `syncTimeoutMs rejects negative`() {
        assertFailsWith<IllegalArgumentException> {
            AmplifyDataStoreConfig(syncTimeoutMs = -1)
        }
    }

    @Test
    fun `custom syncTimeoutMs accepted`() {
        val config = AmplifyDataStoreConfig(syncTimeoutMs = 120_000L)
        assertEquals(120_000L, config.syncTimeoutMs)
    }

    @Test
    fun `individual signals can be disabled`() {
        val config = AmplifyDataStoreConfig(
            captureOutboxEvents = false,
            captureSyncSpans = false,
            captureSyncMetrics = false,
            captureConflicts = false,
            attachNetworkState = false
        )
        assertTrue(!config.captureOutboxEvents)
        assertTrue(!config.captureSyncSpans)
        assertTrue(!config.captureSyncMetrics)
        assertTrue(!config.captureConflicts)
        assertTrue(!config.attachNetworkState)
    }

    @Test
    fun `data class copy preserves values`() {
        val original = AmplifyDataStoreConfig(syncTimeoutMs = 30_000L)
        val copied = original.copy(enabled = false)
        assertTrue(!copied.enabled)
        assertEquals(30_000L, copied.syncTimeoutMs)
    }
}
