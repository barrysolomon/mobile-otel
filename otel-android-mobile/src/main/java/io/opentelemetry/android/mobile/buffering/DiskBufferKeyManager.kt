/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.buffering

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persistent store for the wrapped SQLCipher passphrase blob.
 *
 * Abstracted behind an interface so the key-wrapping logic in
 * [DiskBufferKeyManager] can be exercised by plain JVM unit tests with an
 * in-memory implementation, without requiring a real Android Keystore (which
 * Robolectric cannot fully emulate). Production uses [SharedPrefsPassphraseStore].
 */
internal interface PassphraseStore {
    fun read(): String?
    fun write(value: String)
    fun clear()
}

/**
 * Default [PassphraseStore] backed by a plain SharedPreferences file.
 *
 * The value stored here is ALWAYS the Keystore-wrapped (AES/GCM encrypted)
 * passphrase blob — never the raw passphrase. The wrapping key lives in the
 * Android Keystore and never leaves secure hardware (where a TEE/StrongBox is
 * present), so this file is useless to an attacker without the device's
 * Keystore.
 */
internal class SharedPrefsPassphraseStore(context: Context) : PassphraseStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY_WRAPPED_PASSPHRASE, null)

    override fun write(value: String) {
        prefs.edit().putString(KEY_WRAPPED_PASSPHRASE, value).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_WRAPPED_PASSPHRASE).apply()
    }

    companion object {
        private const val PREFS_NAME = "otel_disk_buffer_crypto"
        private const val KEY_WRAPPED_PASSPHRASE = "wrapped_passphrase"
    }
}

/**
 * Provides the SQLCipher passphrase for the on-disk telemetry buffer, deriving
 * it from a per-install secret that is wrapped by an AES key held in the
 * Android Keystore.
 *
 * **Design**
 * 1. On first use, a 32-byte random passphrase is generated with [SecureRandom].
 * 2. A 256-bit AES Keystore key (`AES/GCM/NoPadding`) is generated in the
 *    Android Keystore. The raw key bytes never leave the Keystore.
 * 3. The passphrase is encrypted (wrapped) with the Keystore key and the
 *    resulting `[IV | ciphertext]` blob is persisted via a [PassphraseStore].
 * 4. On subsequent launches the blob is read back and unwrapped with the same
 *    Keystore key to recover the passphrase.
 *
 * The raw passphrase is therefore never written to disk in cleartext, and is
 * recoverable only on a device that still holds the (non-exportable) Keystore
 * key.
 *
 * **Failure handling (never crash the host)**
 * Every public entry point returns `null` on any failure
 * (KeyStore unavailable, key invalidated by a credential change, locked device
 * / user-not-authenticated, corrupt blob, missing hardware). A `null` result
 * signals the caller ([DiskLogBuffer]) to fall back to an unencrypted buffer
 * rather than throw. Where the failure indicates a permanently unusable key
 * (e.g. [android.security.keystore.KeyPermanentlyInvalidatedException]) the key
 * and wrapped blob are cleared so a fresh key/passphrase can be minted on the
 * next call (the existing encrypted DB will fail to open and be recreated by
 * the destructive-fallback path — consistent with `fallbackToDestructiveMigration`).
 */
internal class DiskBufferKeyManager(
    private val store: PassphraseStore,
    private val keystore: KeystoreCrypto
) {

    /**
     * Returns the SQLCipher passphrase bytes, generating and persisting a new
     * wrapped passphrase on first use. Returns `null` if encryption cannot be
     * provisioned for any reason (caller must then run unencrypted).
     *
     * The returned bytes are the raw passphrase; SQLCipher consumes them
     * directly via the Room SupportFactory (no PRAGMA key string round-trip,
     * avoiding a cleartext String in the heap dump).
     */
    fun getOrCreatePassphrase(): ByteArray? {
        return try {
            val existing = store.read()
            if (existing != null) {
                val unwrapped = unwrap(existing)
                if (unwrapped != null) return unwrapped
                // Blob present but unreadable (key invalidated / corrupt): reset
                // and recreate. The old encrypted DB becomes unreadable and will
                // be recreated by the destructive-fallback path.
                Log.w(TAG, "Wrapped passphrase unreadable; regenerating (DB will be recreated)")
                reset()
            }
            createAndPersist()
        } catch (e: Exception) {
            // Catch-all: any unforeseen failure degrades to unencrypted rather
            // than crashing the host application.
            Log.e(TAG, "Failed to provision disk-buffer passphrase; encryption disabled", e)
            null
        }
    }

    /** Clears the Keystore key and the wrapped passphrase blob. */
    fun reset() {
        try {
            keystore.deleteKey()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete Keystore key during reset (non-fatal)", e)
        }
        try {
            store.clear()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear wrapped passphrase during reset (non-fatal)", e)
        }
    }

    private fun createAndPersist(): ByteArray? {
        val passphrase = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
        val key = keystore.getOrCreateKey() ?: run {
            Log.w(TAG, "Keystore key unavailable; encryption disabled")
            return null
        }
        val wrapped = keystore.encrypt(key, passphrase) ?: run {
            Log.w(TAG, "Passphrase wrap failed; encryption disabled")
            return null
        }
        store.write(Base64.encodeToString(wrapped, Base64.NO_WRAP))
        return passphrase
    }

    private fun unwrap(encoded: String): ByteArray? {
        val key = keystore.getExistingKey() ?: return null
        val wrapped = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Corrupt wrapped passphrase encoding", e)
            return null
        }
        return keystore.decrypt(key, wrapped)
    }

    companion object {
        private const val TAG = "DiskBufferKeyManager"
        private const val PASSPHRASE_BYTES = 32 // 256-bit SQLCipher passphrase

        /** Builds the production manager wired to the Android Keystore. */
        fun create(context: Context): DiskBufferKeyManager =
            DiskBufferKeyManager(
                store = SharedPrefsPassphraseStore(context),
                keystore = AndroidKeystoreCrypto()
            )
    }
}

/**
 * Narrow abstraction over the AES-GCM wrap/unwrap primitives backed by the
 * Android Keystore. Extracted as an interface so [DiskBufferKeyManager] can be
 * unit-tested with a software implementation (Robolectric/JVM cannot provide a
 * real hardware-backed Keystore).
 *
 * All methods are total: they return `null` rather than throwing on any
 * cryptographic / Keystore failure, so encryption provisioning can degrade
 * gracefully instead of crashing.
 */
internal interface KeystoreCrypto {
    /** Returns the existing wrapping key, or generates one if absent. `null` on failure. */
    fun getOrCreateKey(): SecretKey?

    /** Returns the existing wrapping key without creating one. `null` if absent/unreadable. */
    fun getExistingKey(): SecretKey?

    /** Encrypts [plaintext] under [key], returning `[12-byte IV | ciphertext+tag]`, or `null`. */
    fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray?

    /** Decrypts a `[12-byte IV | ciphertext+tag]` [blob] under [key], or `null` on failure. */
    fun decrypt(key: SecretKey, blob: ByteArray): ByteArray?

    /** Deletes the wrapping key. No-op if absent. */
    fun deleteKey()
}

/**
 * Production [KeystoreCrypto] using the `AndroidKeyStore` provider.
 *
 * The wrapping key is a non-exportable 256-bit AES key used in GCM mode. It is
 * NOT bound to user authentication (`setUserAuthenticationRequired(false)`) so
 * the SDK can wrap/unwrap the passphrase while the device is locked — a
 * background telemetry SDK must be able to open its buffer before first unlock.
 * This matches the iOS `completeUntilFirstUserAuthentication` tradeoff.
 */
internal class AndroidKeystoreCrypto : KeystoreCrypto {

    override fun getOrCreateKey(): SecretKey? {
        return try {
            getExistingKey() ?: generateKey()
        } catch (e: Exception) {
            Log.e(TAG, "getOrCreateKey failed", e)
            null
        }
    }

    override fun getExistingKey(): SecretKey? {
        return try {
            val ks = loadKeystore() ?: return null
            val entry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            entry?.secretKey
        } catch (e: Exception) {
            // Includes KeyPermanentlyInvalidatedException, UnrecoverableKeyException,
            // and locked-keystore states. Treat as "no usable key".
            Log.w(TAG, "Existing Keystore key unusable", e)
            null
        }
    }

    private fun generateKey(): SecretKey? {
        return try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Usable while the device is locked (pre-first-unlock background writes).
                .setUserAuthenticationRequired(false)
                .build()
            generator.init(spec)
            generator.generateKey()
        } catch (e: Exception) {
            Log.e(TAG, "Keystore key generation failed", e)
            null
        }
    }

    override fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)
            // Prepend the IV (Keystore generates a fresh random IV per operation).
            ByteArray(iv.size + ciphertext.size).also {
                System.arraycopy(iv, 0, it, 0, iv.size)
                System.arraycopy(ciphertext, 0, it, iv.size, ciphertext.size)
            }
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Passphrase encryption failed", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Passphrase encryption failed (unexpected)", e)
            null
        }
    }

    override fun decrypt(key: SecretKey, blob: ByteArray): ByteArray? {
        return try {
            if (blob.size <= GCM_IV_BYTES) {
                Log.w(TAG, "Wrapped passphrase blob too short")
                return null
            }
            val iv = blob.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = blob.copyOfRange(GCM_IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            // Includes AEADBadTagException (tampered/wrong key) and
            // KeyPermanentlyInvalidatedException (credential change).
            Log.w(TAG, "Passphrase decryption failed (key invalidated or blob corrupt)", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Passphrase decryption failed (unexpected)", e)
            null
        }
    }

    override fun deleteKey() {
        try {
            val ks = loadKeystore() ?: return
            if (ks.containsAlias(KEY_ALIAS)) {
                ks.deleteEntry(KEY_ALIAS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete Keystore key", e)
        }
    }

    private fun loadKeystore(): KeyStore? {
        return try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        } catch (e: Exception) {
            Log.e(TAG, "Android Keystore unavailable", e)
            null
        }
    }

    companion object {
        private const val TAG = "AndroidKeystoreCrypto"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "otel_disk_buffer_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
