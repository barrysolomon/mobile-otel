/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Unit tests for [DiskBufferKeyManager], the Keystore-wrapping passphrase
 * provider for the encrypted disk buffer.
 *
 * The real Android Keystore cannot be fully emulated under Robolectric, so
 * these tests exercise the manager's logic against a software-backed
 * [KeystoreCrypto] (a plain AES/GCM implementation with an in-process key) and
 * an in-memory [PassphraseStore]. This validates the wrap/unwrap/reset/reuse
 * contract independently of the platform Keystore — the production wiring in
 * [AndroidKeystoreCrypto] is a thin shell over the same `Cipher` primitives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiskBufferKeyManagerTest {

    /** In-memory passphrase store. */
    private class FakeStore : PassphraseStore {
        var value: String? = null
        var writes = 0
        override fun read(): String? = value
        override fun write(value: String) {
            this.value = value
            writes++
        }
        override fun clear() {
            value = null
        }
    }

    /**
     * Software AES/GCM crypto standing in for the Android Keystore. The "key"
     * is an ordinary in-process [SecretKey]; non-exportability is irrelevant to
     * the logic under test (wrap/unwrap correctness, fresh-IV-per-op, reset).
     */
    private open class SoftwareCrypto : KeystoreCrypto {
        protected var key: SecretKey? = null
        var generateCount = 0

        override fun getOrCreateKey(): SecretKey? = getExistingKey() ?: generate()
        override fun getExistingKey(): SecretKey? = key

        private fun generate(): SecretKey {
            val kg = KeyGenerator.getInstance("AES")
            kg.init(256)
            generateCount++
            return kg.generateKey().also { key = it }
        }

        override fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray? {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val ct = cipher.doFinal(plaintext)
            return iv + ct
        }

        override fun decrypt(key: SecretKey, blob: ByteArray): ByteArray? {
            if (blob.size <= 12) return null
            val iv = blob.copyOfRange(0, 12)
            val ct = blob.copyOfRange(12, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return try {
                cipher.doFinal(ct)
            } catch (e: Exception) {
                null
            }
        }

        override fun deleteKey() {
            key = null
        }
    }

    private lateinit var store: FakeStore
    private lateinit var crypto: SoftwareCrypto

    @Before
    fun setup() {
        store = FakeStore()
        crypto = SoftwareCrypto()
    }

    @Test
    fun `generates a 256-bit passphrase on first use`() {
        val manager = DiskBufferKeyManager(store, crypto)
        val pass = manager.getOrCreatePassphrase()
        assertNotNull("Passphrase should be provisioned", pass)
        assertEquals("Passphrase must be 32 bytes (256-bit)", 32, pass!!.size)
    }

    @Test
    fun `raw passphrase is never stored in cleartext`() {
        val manager = DiskBufferKeyManager(store, crypto)
        val pass = manager.getOrCreatePassphrase()!!

        val storedBlob = android.util.Base64.decode(store.value, android.util.Base64.NO_WRAP)
        // The persisted blob must NOT contain the raw passphrase bytes anywhere.
        assertFalse(
            "Stored blob must not contain the raw passphrase",
            containsSubsequence(storedBlob, pass)
        )
    }

    @Test
    fun `passphrase is generated once and reused across calls`() {
        val manager = DiskBufferKeyManager(store, crypto)
        val first = manager.getOrCreatePassphrase()!!
        val second = manager.getOrCreatePassphrase()!!

        assertArrayEquals("Same passphrase must be returned on reuse", first, second)
        assertEquals("Keystore key generated exactly once", 1, crypto.generateCount)
        assertEquals("Wrapped blob written exactly once", 1, store.writes)
    }

    @Test
    fun `passphrase survives a new manager instance with the same store and key`() {
        val first = DiskBufferKeyManager(store, crypto).getOrCreatePassphrase()!!
        // Simulate a fresh process: new manager, same persistent store + Keystore.
        val second = DiskBufferKeyManager(store, crypto).getOrCreatePassphrase()!!
        assertArrayEquals(first, second)
    }

    @Test
    fun `key invalidation regenerates passphrase without crashing`() {
        val manager = DiskBufferKeyManager(store, crypto)
        val first = manager.getOrCreatePassphrase()!!

        // Simulate KeyPermanentlyInvalidatedException: the existing key can no
        // longer decrypt the stored blob.
        crypto.deleteKey()

        val second = manager.getOrCreatePassphrase()
        assertNotNull("Must recover by minting a new passphrase", second)
        assertFalse(
            "A new passphrase must be generated after key invalidation",
            first.contentEquals(second!!)
        )
    }

    @Test
    fun `corrupt blob is recovered by regeneration`() {
        val manager = DiskBufferKeyManager(store, crypto)
        manager.getOrCreatePassphrase()!!

        // Corrupt the persisted blob.
        store.value = "not-valid-base64-or-ciphertext-@@@"

        val recovered = manager.getOrCreatePassphrase()
        assertNotNull("Corrupt blob must be recovered, not fatal", recovered)
        assertEquals(32, recovered!!.size)
    }

    @Test
    fun `unavailable keystore degrades to null instead of crashing`() {
        val brokenCrypto = object : SoftwareCrypto() {
            override fun getOrCreateKey(): SecretKey? = null
        }
        val manager = DiskBufferKeyManager(store, brokenCrypto)
        val pass = manager.getOrCreatePassphrase()
        assertNull("Absent Keystore must degrade to null (caller runs cleartext)", pass)
    }

    @Test
    fun `reset clears both the key and the stored blob`() {
        val manager = DiskBufferKeyManager(store, crypto)
        manager.getOrCreatePassphrase()!!
        assertNotNull(store.value)

        manager.reset()

        assertNull("Stored blob cleared on reset", store.value)
        assertNull("Keystore key cleared on reset", crypto.getExistingKey())
    }

    /** Returns true if [needle] appears as a contiguous subsequence of [haystack]. */
    private fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
