package io.opentelemetry.android.mobile.fleet

import io.mockk.*
import io.opentelemetry.android.mobile.buffering.MobileLogRecordProcessor
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.config.PrivacyConfig
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.time.Instant

class FleetAlertHandlerTest {

    private lateinit var processor: MobileLogRecordProcessor
    private lateinit var config: MobileConfig
    private lateinit var dedup: FleetAlertDeduplicator
    private lateinit var handler: FleetAlertHandler

    @Before
    fun setup() {
        processor = mockk(relaxed = true)
        config = mockk(relaxed = true)
        dedup = mockk(relaxed = true)
        every { dedup.isProcessed(any()) } returns false

        handler = FleetAlertHandler(processor, config, dedup)
    }

    private fun makeAlert(
        alertId: String = "fa-001",
        actions: List<FleetAction> = listOf(FleetAction("flush_buffer", mapOf("minutes" to "5"))),
        expiresAt: String = Instant.now().plusSeconds(300).toString(),
    ) = FleetAlert(
        alertId = alertId,
        cascadeChainId = "cc-001",
        sourceTrigger = "crash_marker",
        sourceCohort = "pixel7",
        actions = actions,
        expiresAt = expiresAt,
        signature = "valid-sig",
        issuedAt = Instant.now().toString(),
    )

    @Test
    fun testFleetAlert_ExecutesFlushAction() {
        val result = handler.onFleetAlert(makeAlert())
        assertTrue(result.executed)
        assertTrue(result.actionsExecuted.contains("flush_buffer"))
        verify { processor.flushWindow(5) }
    }

    @Test
    fun testFleetAlert_ExpiredAlert_Rejected() {
        val alert = makeAlert(expiresAt = Instant.now().minusSeconds(60).toString())
        val result = handler.onFleetAlert(alert)
        assertFalse(result.executed)
        assertEquals("expired", result.reason)
    }

    @Test
    fun testFleetAlert_DuplicateAlertId_Rejected() {
        every { dedup.isProcessed("fa-001") } returns true
        val result = handler.onFleetAlert(makeAlert())
        assertFalse(result.executed)
        assertEquals("duplicate", result.reason)
    }

    @Test
    fun testFleetAlert_RateLimited() {
        for (i in 1..5) {
            handler.onFleetAlert(makeAlert(alertId = "fa-$i"))
        }
        val result = handler.onFleetAlert(makeAlert(alertId = "fa-6"))
        assertFalse(result.executed)
        assertEquals("rate_limited", result.reason)
    }

    @Test
    fun testFleetAlert_ScreenshotBlocked_WhenPrivacyDisabled() {
        val privacyHandler = FleetAlertHandler(processor, config, dedup, PrivacyConfig(allowFleetScreenshot = false))
        val alert = makeAlert(actions = listOf(FleetAction("take_screenshot")))
        val result = privacyHandler.onFleetAlert(alert)
        assertTrue(result.executed)
        assertTrue(result.actionsSkipped.any { it.contains("take_screenshot") })
    }

    @Test
    fun testFleetAlert_UnknownActionType_Skipped() {
        val alert = makeAlert(actions = listOf(
            FleetAction("flush_buffer", mapOf("minutes" to "5")),
            FleetAction("unknown_future_action"),
        ))
        val result = handler.onFleetAlert(alert)
        assertTrue(result.executed)
        assertTrue(result.actionsExecuted.contains("flush_buffer"))
        assertTrue(result.actionsSkipped.any { it.contains("unknown_future_action") })
    }
}
