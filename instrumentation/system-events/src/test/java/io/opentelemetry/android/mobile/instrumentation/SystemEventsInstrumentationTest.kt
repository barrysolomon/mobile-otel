package io.opentelemetry.android.mobile.instrumentation

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SystemEventsInstrumentationTest {

    @Test
    fun `instrumentationName is correct`() {
        val inst = SystemEventsInstrumentation()
        assertEquals("io.opentelemetry.android.mobile.system-events", inst.instrumentationName)
    }

    @Test
    fun `event name constants are correct`() {
        assertEquals("device.battery.low", SystemEventsInstrumentation.DEVICE_BATTERY_LOW)
        assertEquals("device.battery.okay", SystemEventsInstrumentation.DEVICE_BATTERY_OKAY)
        assertEquals("device.power.connected", SystemEventsInstrumentation.DEVICE_POWER_CONNECTED)
        assertEquals("device.power.disconnected", SystemEventsInstrumentation.DEVICE_POWER_DISCONNECTED)
        assertEquals("device.airplane_mode", SystemEventsInstrumentation.DEVICE_AIRPLANE_MODE)
        assertEquals("device.storage.low", SystemEventsInstrumentation.DEVICE_STORAGE_LOW)
    }

    @Test
    fun `uninstall before install is safe`() {
        val inst = SystemEventsInstrumentation()
        inst.uninstall() // Should not crash
    }
}
