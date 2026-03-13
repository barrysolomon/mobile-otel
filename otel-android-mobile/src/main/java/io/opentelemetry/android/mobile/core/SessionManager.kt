/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.core

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Manages user sessions and global attributes for telemetry.
 *
 * SessionManager tracks:
 * - Session lifecycle (foreground/background/termination)
 * - User identity (optional)
 * - Global attributes attached to all telemetry events
 * - Inactivity-based session expiration
 *
 * This is a singleton initialized early in the app lifecycle.
 */
class SessionManager private constructor(
    private val context: Context,
    private val config: SessionConfig,
    private val logger: Logger?
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "otel_session",
        Context.MODE_PRIVATE
    )

    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "otel_session_encrypted",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    // Session state
    @Volatile
    private var currentSessionId: String = loadOrCreateSessionId()

    @Volatile
    private var sessionStartTime: Long = System.currentTimeMillis()

    @Volatile
    private var lastActivityTime: Long = sessionStartTime

    @Volatile
    private var isInForeground = false

    @Volatile
    private var currentUserId: String? = loadUserId()

    private val globalAttributes = mutableMapOf<String, Any>()
    private var inactivityTimerTask: ScheduledFuture<*>? = null

    companion object {
        @Volatile
        private var instance: SessionManager? = null

        /**
         * Initialize the SessionManager. Must be called before getInstance().
         *
         * @param context Application context
         * @param config Session configuration
         * @param logger Optional logger for session events
         */
        fun initialize(context: Context, config: SessionConfig, logger: Logger? = null) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = SessionManager(
                            context.applicationContext,
                            config,
                            logger
                        ).apply {
                            registerLifecycleCallbacks()
                            loadGlobalAttributes()
                        }
                    }
                }
            }
        }

        /**
         * Get the singleton instance. Must call initialize() first.
         */
        fun getInstance(): SessionManager {
            return instance ?: throw IllegalStateException(
                "SessionManager not initialized. Call initialize() first."
            )
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Lifecycle Management
    // ─────────────────────────────────────────────────────────────

    private fun registerLifecycleCallbacks() {
        (context as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {}

                override fun onActivityResumed(activity: Activity) {
                    onForeground()
                }

                override fun onActivityPaused(activity: Activity) {
                    onBackground()
                }

                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            }
        )
    }

    private fun onForeground() {
        if (!config.enabled) return

        isInForeground = true
        val now = System.currentTimeMillis()
        val backgroundDuration = now - lastActivityTime

        // Check if session expired during background
        if (backgroundDuration > config.inactivityTimeoutMs) {
            terminateSession("inactivity_timeout")
            currentSessionId = generateSessionId()
            sessionStartTime = now
            saveSessionId()

            logger?.logRecordBuilder()
                ?.setBody("mobile.session.started")
                ?.setAllAttributes(
                    Attributes.builder()
                        .put("mobile.session.id", currentSessionId)
                        .put("session.start_reason", "inactivity_timeout")
                        .build()
                )
                ?.emit()
        }

        lastActivityTime = now

        // Cancel inactivity timer
        inactivityTimerTask?.cancel(false)
        inactivityTimerTask = null
    }

    private fun onBackground() {
        if (!config.enabled) return

        isInForeground = false
        lastActivityTime = System.currentTimeMillis()

        // Start inactivity timer
        inactivityTimerTask = executor.schedule(
            {
                terminateSession("inactivity_timeout")
            },
            config.inactivityTimeoutMs,
            TimeUnit.MILLISECONDS
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Session Control
    // ─────────────────────────────────────────────────────────────

    /**
     * Terminate the current session and start a new one.
     *
     * @param reason Reason for termination (e.g., "logout", "inactivity_timeout")
     */
    fun terminateSession(reason: String) {
        if (!config.enabled) return

        val duration = System.currentTimeMillis() - sessionStartTime

        // Log session end event
        logger?.logRecordBuilder()
            ?.setBody("mobile.session.terminated")
            ?.setAllAttributes(
                Attributes.builder()
                    .put("mobile.session.id", currentSessionId)
                    .put("session.duration_ms", duration)
                    .put("session.termination_reason", reason)
                    .build()
            )
            ?.emit()

        // Trigger flush if configured
        if (config.flushOnTermination) {
            // Note: This will be called via MobileOtel facade in actual usage
            // For now, just log the intent
            logger?.logRecordBuilder()
                ?.setBody("mobile.session.flush_requested")
                ?.setAllAttributes(
                    Attributes.builder()
                        .put("mobile.session.id", currentSessionId)
                        .put("reason", "session_termination")
                        .build()
                )
                ?.emit()
        }

        // Start new session
        currentSessionId = generateSessionId()
        sessionStartTime = System.currentTimeMillis()
        saveSessionId()
    }

    /**
     * Enable or disable session tracking.
     */
    fun setEnabled(enabled: Boolean) {
        // This would require modifying the config, which is immutable
        // For now, just log a warning
        logger?.logRecordBuilder()
            ?.setBody("mobile.session.enable_requested")
            ?.setAllAttributes(
                Attributes.builder()
                    .put("enabled", enabled)
                    .put("warning", "Session enable/disable not yet implemented")
                    .build()
            )
            ?.emit()
    }

    // ─────────────────────────────────────────────────────────────
    // Identity Management
    // ─────────────────────────────────────────────────────────────

    /**
     * Identify the current user. User ID will be attached to all telemetry.
     *
     * @param user User identity information
     */
    fun identify(user: UserIdentity) {
        currentUserId = user.userId

        encryptedPrefs.edit().apply {
            putString("user.id", user.userId)

            user.email?.let { email ->
                val emailValue = if (user.hashEmail) {
                    hashEmail(email)
                } else {
                    email
                }
                putString("user.email", emailValue)
            }

            user.name?.let { putString("user.name", it) }

            // Store custom attributes
            user.customAttributes.forEach { (key, value) ->
                putString("user.attr.$key", value.toString())
            }

            apply()
        }

        // Log identify event
        logger?.logRecordBuilder()
            ?.setBody("mobile.user.identified")
            ?.setAllAttributes(
                Attributes.builder()
                    .put("user.id", user.userId)
                    .put("mobile.session.id", currentSessionId)
                    .build()
            )
            ?.emit()
    }

    /**
     * Clear user identity. Future telemetry will be anonymous.
     */
    fun clearIdentity() {
        currentUserId = null
        encryptedPrefs.edit().clear().apply()

        logger?.logRecordBuilder()
            ?.setBody("mobile.user.cleared")
            ?.setAllAttributes(
                Attributes.builder()
                    .put("mobile.session.id", currentSessionId)
                    .build()
            )
            ?.emit()
    }

    // ─────────────────────────────────────────────────────────────
    // Global Attributes
    // ─────────────────────────────────────────────────────────────

    /**
     * Add a global attribute that will be attached to all telemetry events.
     *
     * @param key Attribute key
     * @param value Attribute value
     */
    fun addGlobalAttribute(key: String, value: Any) {
        synchronized(globalAttributes) {
            globalAttributes[key] = value
            saveGlobalAttributes()
        }
    }

    /**
     * Remove a global attribute.
     *
     * @param key Attribute key to remove
     */
    fun removeGlobalAttribute(key: String) {
        synchronized(globalAttributes) {
            globalAttributes.remove(key)
            saveGlobalAttributes()
        }
    }

    /**
     * Clear all global attributes.
     */
    fun clearGlobalAttributes() {
        synchronized(globalAttributes) {
            globalAttributes.clear()
            saveGlobalAttributes()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Event Enrichment
    // ─────────────────────────────────────────────────────────────

    /**
     * Enrich event attributes with session, user, and global attributes.
     * This is called for every telemetry event.
     *
     * @return Attributes to add to the event
     */
    fun getEnrichmentAttributes(): Attributes {
        if (!config.enabled) {
            return Attributes.empty()
        }

        val builder = Attributes.builder()

        // Session attributes
        builder.put("mobile.session.id", currentSessionId)
        builder.put("session.start_time", sessionStartTime)
        builder.put("session.duration_ms", System.currentTimeMillis() - sessionStartTime)
        builder.put("session.state", if (isInForeground) "active" else "background")

        // User attributes
        currentUserId?.let { builder.put("user.id", it) }

        // Global attributes
        synchronized(globalAttributes) {
            globalAttributes.forEach { (key, value) ->
                when (value) {
                    is String -> builder.put(key, value)
                    is Long -> builder.put(key, value)
                    is Int -> builder.put(key, value.toLong())
                    is Double -> builder.put(key, value)
                    is Boolean -> builder.put(key, value)
                    else -> builder.put(key, value.toString())
                }
            }
        }

        return builder.build()
    }

    // ─────────────────────────────────────────────────────────────
    // Persistence
    // ─────────────────────────────────────────────────────────────

    private fun loadOrCreateSessionId(): String {
        return if (config.persistSession) {
            prefs.getString("session.id", null) ?: generateSessionId().also { saveSessionId() }
        } else {
            generateSessionId()
        }
    }

    private fun saveSessionId() {
        if (config.persistSession) {
            prefs.edit().putString("session.id", currentSessionId).apply()
        }
    }

    private fun loadUserId(): String? {
        return encryptedPrefs.getString("user.id", null)
    }

    private fun loadGlobalAttributes() {
        val json = prefs.getString("global_attributes", "{}")
        if (!json.isNullOrEmpty() && json != "{}") {
            try {
                val attrs: Map<String, Any> = this.json.decodeFromString(json)
                synchronized(globalAttributes) {
                    globalAttributes.clear()
                    globalAttributes.putAll(attrs)
                }
            } catch (e: Exception) {
                // Ignore parse errors
            }
        }
    }

    private fun saveGlobalAttributes() {
        val json = synchronized(globalAttributes) {
            this.json.encodeToString(globalAttributes.toMap())
        }
        prefs.edit().putString("global_attributes", json).apply()
    }

    private fun generateSessionId(): String = UUID.randomUUID().toString()

    private fun hashEmail(email: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(email.lowercase().toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Shutdown the session manager and release resources.
     */
    fun shutdown() {
        inactivityTimerTask?.cancel(false)
        executor.shutdown()
    }
}
