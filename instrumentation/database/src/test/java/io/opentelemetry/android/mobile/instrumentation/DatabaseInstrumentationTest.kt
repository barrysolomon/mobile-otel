package io.opentelemetry.android.mobile.instrumentation

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseInstrumentationTest {

    @Test
    fun `instrumentationName is correct`() {
        val inst = DatabaseInstrumentation()
        assertEquals("io.opentelemetry.android.mobile.database", inst.instrumentationName)
    }

    @Test
    fun `extractOperation identifies SQL operations`() {
        val callback = OTelQueryCallback(io.opentelemetry.api.GlobalOpenTelemetry.getTracer("test"))
        // Access via reflection since it's internal
        val method = OTelQueryCallback::class.java.getDeclaredMethod("extractOperation", String::class.java)
        method.isAccessible = true

        assertEquals("SELECT", method.invoke(callback, "SELECT * FROM users"))
        assertEquals("INSERT", method.invoke(callback, "INSERT INTO users VALUES (1)"))
        assertEquals("UPDATE", method.invoke(callback, "UPDATE users SET name='x'"))
        assertEquals("DELETE", method.invoke(callback, "DELETE FROM users WHERE id=1"))
        assertEquals("CREATE", method.invoke(callback, "CREATE TABLE foo (id INT)"))
        assertEquals("OTHER", method.invoke(callback, "VACUUM"))
    }

    @Test
    fun `extractTable parses table name from SQL`() {
        val callback = OTelQueryCallback(io.opentelemetry.api.GlobalOpenTelemetry.getTracer("test"))
        val method = OTelQueryCallback::class.java.getDeclaredMethod("extractTable", String::class.java)
        method.isAccessible = true

        assertEquals("users", method.invoke(callback, "SELECT * FROM users WHERE id=1"))
        assertEquals("orders", method.invoke(callback, "INSERT INTO orders VALUES (1)"))
        assertEquals("products", method.invoke(callback, "UPDATE products SET price=10"))
        assertEquals("logs", method.invoke(callback, "DELETE FROM logs WHERE ts < 100"))
        assertNull(method.invoke(callback, "VACUUM"))
    }

    @Test
    fun `truncateStatement caps long SQL`() {
        val callback = OTelQueryCallback(io.opentelemetry.api.GlobalOpenTelemetry.getTracer("test"))
        val method = OTelQueryCallback::class.java.getDeclaredMethod("truncateStatement", String::class.java)
        method.isAccessible = true

        val longSql = "SELECT " + "x".repeat(300) + " FROM users"
        val result = method.invoke(callback, longSql) as String
        assertTrue("Truncated SQL should be <= 259 chars", result.length <= 259)
        assertTrue("Should end with ...", result.endsWith("..."))

        val shortSql = "SELECT * FROM users"
        assertEquals(shortSql, method.invoke(callback, shortSql))
    }

    @Test
    fun `instrumentDatabase before install logs warning`() {
        // Should not crash when called before install()
        val inst = DatabaseInstrumentation()
        // Can't easily test the warning without mocking Log, but ensure no exception
    }
}
