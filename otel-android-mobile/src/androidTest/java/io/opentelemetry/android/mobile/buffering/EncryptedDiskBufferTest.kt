/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.logs.data.Body
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.resources.Resource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests for at-rest encryption of the disk buffer (SQLCipher +
 * Android Keystore). These run on a real device/emulator because SQLCipher's
 * native library and the Android Keystore are not available under Robolectric.
 *
 * Run with:
 *   cd examples/demo-app && ./gradlew :otel-android-mobile:connectedDebugAndroidTest
 *
 * Verifies:
 * - The on-disk database file is genuinely encrypted (no `SQLite format 3`
 *   header, no plaintext markers).
 * - Events round-trip correctly through the encrypted buffer.
 * - The Keystore-derived passphrase is reused across buffer instances.
 * - A corrupt/foreign-key DB is recreated rather than crashing.
 * - TTL / size-cap behavior still works with encryption on.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDiskBufferTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        DiskLogBuffer.resetForTesting()
        context = ApplicationProvider.getApplicationContext()
        // Start from a clean Keystore-wrapped passphrase so each run is deterministic.
        DiskBufferKeyManager.create(context).reset()
        deleteDbFiles()
    }

    @After
    fun tearDown() {
        DiskLogBuffer.resetForTesting()
        DiskBufferKeyManager.create(context).reset()
        deleteDbFiles()
    }

    private fun dbFile(): File = context.getDatabasePath(DiskLogBuffer.DB_NAME)

    private fun deleteDbFiles() {
        val f = dbFile()
        f.delete()
        File(f.absolutePath + "-wal").delete()
        File(f.absolutePath + "-shm").delete()
    }

    private fun record(body: String, tsMs: Long = System.currentTimeMillis()): LogRecordData =
        object : LogRecordData {
            override fun getResource(): Resource = Resource.empty()
            override fun getInstrumentationScopeInfo(): InstrumentationScopeInfo = InstrumentationScopeInfo.empty()
            override fun getTimestampEpochNanos(): Long = tsMs * 1_000_000
            override fun getObservedTimestampEpochNanos(): Long = tsMs * 1_000_000
            override fun getSpanContext(): SpanContext = SpanContext.getInvalid()
            override fun getSeverity(): Severity = Severity.INFO
            override fun getSeverityText(): String = "INFO"
            override fun getBody(): Body = object : Body {
                override fun asString() = body
                override fun getType() = Body.Type.STRING
            }
            override fun getAttributes(): Attributes = Attributes.empty()
            override fun getTotalAttributeCount(): Int = 0
        }

    @Test
    fun encryptedDatabaseHasNoSqliteHeaderOrPlaintext() = runBlocking {
        val marker = "SUPER_SECRET_PII_MARKER_42"
        val buffer = DiskLogBuffer.getInstance(context, maxSizeMb = 10, ttlHours = 24, encryptAtRest = true)
        assertTrue("Encryption should be active on a device with SQLCipher", buffer.encryptionActive)

        buffer.persistEvents(listOf(record(marker)))
        Thread.sleep(400)
        // Force a checkpoint so content is flushed from WAL to the main file.
        buffer.vacuum()
        Thread.sleep(300)

        val bytes = dbFile().readBytes()
        assertTrue("DB file should exist and be non-empty", bytes.isNotEmpty())

        // 1. No cleartext SQLite header.
        val header = String(bytes.copyOfRange(0, minOf(16, bytes.size)), Charsets.US_ASCII)
        assertFalse("Encrypted DB must not start with the SQLite header", header.startsWith("SQLite format 3"))

        // 2. The known plaintext marker must not appear anywhere in the file.
        assertFalse(
            "Plaintext PII marker must not be present in the encrypted file",
            bytes.toString(Charsets.ISO_8859_1).contains(marker)
        )
    }

    @Test
    fun eventsRoundTripThroughEncryptedBuffer() = runBlocking {
        val buffer = DiskLogBuffer.getInstance(context, maxSizeMb = 10, ttlHours = 24, encryptAtRest = true)
        val bodies = (1..25).map { "encrypted.event.$it" }
        buffer.persistEvents(bodies.map { record(it) })
        Thread.sleep(500)

        val retrieved = buffer.getAllEvents()
        assertEquals(25, retrieved.size)
        assertEquals(bodies.toSet(), retrieved.map { it.body.asString() }.toSet())
    }

    @Test
    fun passphraseIsReusedAcrossBufferInstances() = runBlocking {
        val first = DiskLogBuffer.getInstance(context, maxSizeMb = 10, ttlHours = 24, encryptAtRest = true)
        first.persistEvents(listOf(record("persisted.before.restart")))
        Thread.sleep(400)
        first.close()
        DiskLogBuffer.resetForTesting() // closes + nulls the singleton, but does NOT reset the Keystore key

        // A new buffer instance must decrypt the same file with the reused passphrase.
        val second = DiskLogBuffer.getInstance(context, maxSizeMb = 10, ttlHours = 24, encryptAtRest = true)
        val retrieved = second.getAllEvents()
        assertEquals("Reused passphrase must decrypt the existing DB", 1, retrieved.size)
        assertEquals("persisted.before.restart", retrieved[0].body.asString())
    }

    @Test
    fun corruptEncryptedDatabaseIsRecreatedNotFatal() = runBlocking {
        // Create an encrypted DB with content.
        val buffer = DiskLogBuffer.getInstance(context, maxSizeMb = 10, ttlHours = 24, encryptAtRest = true)
        buffer.persistEvents(listOf(record("doomed")))
        Thread.sleep(400)
        buffer.close()
        DiskLogBuffer.resetForTesting()

        // Simulate a key invalidation: drop the Keystore-wrapped passphrase so the
        // existing encrypted file can no longer be opened with a matching key.
        DiskBufferKeyManager.create(context).reset()

        // Opening must NOT crash; it recreates the DB with a fresh key.
        val recreated = DiskLogBuffer.getInstance(context, maxSizeMb = 10, ttlHours = 24, encryptAtRest = true)
        assertEquals("Unreadable DB must be recreated empty", 0, recreated.getAllEvents().size)

        // And the fresh buffer is fully functional.
        recreated.persistEvents(listOf(record("after.recovery")))
        Thread.sleep(400)
        assertEquals(1, recreated.getAllEvents().size)
    }

    @Test
    fun ttlEvictionWorksWithEncryptionOn() = runBlocking {
        val buffer = DiskLogBuffer.getInstance(context, maxSizeMb = 10, ttlHours = 1, encryptAtRest = true)
        val now = System.currentTimeMillis()
        buffer.persistEvents(
            listOf(
                record("expired", now - (3 * 60 * 60 * 1000)),
                record("valid", now - (10 * 60 * 1000))
            )
        )
        Thread.sleep(400)

        buffer.cleanupExpired()
        Thread.sleep(400)

        val remaining = buffer.getAllEvents()
        assertEquals(1, remaining.size)
        assertEquals("valid", remaining[0].body.asString())
    }
}
