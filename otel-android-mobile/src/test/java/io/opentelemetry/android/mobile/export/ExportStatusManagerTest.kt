/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.export
import org.junit.After
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [ExportStatusManager] — validates listener lifecycle and notification.
 */
class ExportStatusManagerTest {

    @After
    fun tearDown() {
        ExportStatusManager.clearListeners()
    }

    // ── Listener registration ───────────────────────────────────────────────

    @Test
    fun `listener receives notifications`() {
        val received = AtomicReference<ExportStatus?>(null)
        val listener = ExportStatusListener { received.set(it) }
        ExportStatusManager.addListener(listener)

        ExportStatusManager.notify(ExportStatus.Success(5))

        assertEquals(5, (received.get() as ExportStatus.Success).eventCount)
    }

    @Test
    fun `removed listener does not receive notifications`() {
        val count = AtomicInteger(0)
        val listener = ExportStatusListener { count.incrementAndGet() }
        ExportStatusManager.addListener(listener)
        ExportStatusManager.removeListener(listener)

        ExportStatusManager.notify(ExportStatus.Success(1))

        assertEquals(0, count.get())
    }

    @Test
    fun `clearListeners removes all listeners`() {
        val count = AtomicInteger(0)
        ExportStatusManager.addListener { count.incrementAndGet() }
        ExportStatusManager.addListener { count.incrementAndGet() }
        ExportStatusManager.clearListeners()

        ExportStatusManager.notify(ExportStatus.Success(1))

        assertEquals(0, count.get())
    }

    // ── Multiple listeners ──────────────────────────────────────────────────

    @Test
    fun `multiple listeners all receive notifications`() {
        val count = AtomicInteger(0)
        ExportStatusManager.addListener { count.incrementAndGet() }
        ExportStatusManager.addListener { count.incrementAndGet() }
        ExportStatusManager.addListener { count.incrementAndGet() }

        ExportStatusManager.notify(ExportStatus.Success(1))

        assertEquals(3, count.get())
    }

    // ── Listener exception safety ───────────────────────────────────────────

    @Test
    fun `throwing listener does not prevent other listeners from receiving`() {
        val received = AtomicReference<ExportStatus?>(null)
        ExportStatusManager.addListener { throw RuntimeException("boom") }
        ExportStatusManager.addListener { received.set(it) }

        ExportStatusManager.notify(ExportStatus.Failed("err", 1, 1))

        assertEquals("err", (received.get() as ExportStatus.Failed).reason)
    }

    // ── Status types ────────────────────────────────────────────────────────

    @Test
    fun `all status types are delivered correctly`() {
        val statuses = mutableListOf<ExportStatus>()
        ExportStatusManager.addListener { statuses.add(it) }

        ExportStatusManager.notify(ExportStatus.Success(10))
        ExportStatusManager.notify(ExportStatus.Failed("timeout", 5, 3))
        ExportStatusManager.notify(ExportStatus.AuthError("bad token", 2))
        ExportStatusManager.notify(ExportStatus.Retrying(1, 3, 500))

        assertEquals(4, statuses.size)
        assert(statuses[0] is ExportStatus.Success)
        assert(statuses[1] is ExportStatus.Failed)
        assert(statuses[2] is ExportStatus.AuthError)
        assert(statuses[3] is ExportStatus.Retrying)
    }

    // ── No listeners ────────────────────────────────────────────────────────

    @Test
    fun `notify with no listeners does not crash`() {
        ExportStatusManager.notify(ExportStatus.Success(1))
        // No exception = pass
    }
}
