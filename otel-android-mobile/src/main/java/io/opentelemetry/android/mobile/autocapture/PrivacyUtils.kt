package io.opentelemetry.android.mobile.autocapture

import java.security.MessageDigest

object PrivacyUtils {
    fun maybeHash(value: CharSequence?, options: AutoCaptureOptions): String? {
        if (value == null) return null
        val text = value.toString().trim()
        if (text.isEmpty()) return null
        return if (options.privacyMode == PrivacyMode.RELAXED) {
            text
        } else {
            hash(text, options.hashSalt)
        }
    }

    private fun hash(value: String, salt: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val salted = if (salt.isNullOrEmpty()) value else "$salt:$value"
        val bytes = digest.digest(salted.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
