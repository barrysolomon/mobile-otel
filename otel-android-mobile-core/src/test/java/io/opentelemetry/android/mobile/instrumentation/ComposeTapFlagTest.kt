// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import org.junit.After
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ComposeTapFlagTest {

    @After fun resetFlag() {
        ComposeTapFlag.handledAtNanos = 0L
        ComposeTapFlag.expiryNanos = 500_000_000L
    }

    @Test fun `wasHandledRecently returns false when never marked`() {
        assertFalse(ComposeTapFlag.wasHandledRecently())
    }

    @Test fun `wasHandledRecently returns true immediately after markHandled`() {
        ComposeTapFlag.markHandled()
        assertTrue(ComposeTapFlag.wasHandledRecently())
    }

    @Test fun `wasHandledRecently returns false after expiry`() {
        ComposeTapFlag.expiryNanos = 1L // 1 nanosecond expiry
        ComposeTapFlag.markHandled()
        Thread.sleep(1)
        assertFalse(ComposeTapFlag.wasHandledRecently())
    }

    @Test fun `expiryNanos is configurable`() {
        ComposeTapFlag.expiryNanos = 10_000_000_000L // 10 seconds
        ComposeTapFlag.markHandled()
        Thread.sleep(50)
        assertTrue(ComposeTapFlag.wasHandledRecently())
    }

    @Test fun `default expiryNanos is 500ms`() {
        assertEquals(500_000_000L, ComposeTapFlag.expiryNanos)
    }
}
