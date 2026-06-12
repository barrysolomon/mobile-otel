/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.errors

import io.opentelemetry.android.mobile.testing.MockLogRecordExporter
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.resources.Resource
import java.util.Collections
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Crash-handler chaining proof (TEST_HARDENING_PLAN P0 #2).
 *
 * Real apps run the SDK NEXT TO a crash reporter (Crashlytics, Sentry, …) that
 * also claims `Thread.defaultUncaughtExceptionHandler`. The contract under test:
 *
 *  1. **Both handlers run.** Installing the SDK must not eat a pre-existing
 *     handler, and a reporter installed after the SDK (chaining to us, as
 *     Crashlytics does) must not eat ours.
 *  2. **Ordering is preserved.** The SDK captures + persists BEFORE delegating
 *     to the downstream handler — Crashlytics typically ends by killing the
 *     process, so anything we do after delegation would be lost.
 *  3. **The crash is reported exactly once** — one `app.crash` log per crash,
 *     no matter how many handlers participate in the chain.
 *
 * Uses a stub `UncaughtExceptionHandler` standing in for Crashlytics: the JVM's
 * dispatch (`Thread.getDefaultUncaughtExceptionHandler().uncaughtException(...)`)
 * is invoked directly, which is exactly what the runtime does on an uncaught
 * throw — minus the process death, which a JVM test cannot survive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CrashHandlerChainingTest {

    private lateinit var exporter: MockLogRecordExporter
    private lateinit var logger: Logger
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    /** Ordered record of who ran, appended by every participant. */
    private val callOrder: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private class StubReporter(
        private val name: String,
        private val order: MutableList<String>,
        private val chainTo: Thread.UncaughtExceptionHandler? = null,
    ) : Thread.UncaughtExceptionHandler {
        var invocations = 0
        var lastThread: Thread? = null
        var lastThrowable: Throwable? = null

        override fun uncaughtException(t: Thread, e: Throwable) {
            invocations++
            lastThread = t
            lastThrowable = e
            order.add(name)
            chainTo?.uncaughtException(t, e)
        }
    }

    @Before
    fun setUp() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        resetSingleton()
        callOrder.clear()
        exporter = MockLogRecordExporter()
        logger = SdkLoggerProvider.builder()
            .setResource(Resource.getDefault())
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
            .build()
            .get("crash-chaining-test")
    }

    @After
    fun tearDown() {
        resetSingleton()
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
    }

    private fun initSdkErrorInstrumentation(): ErrorInstrumentation =
        ErrorInstrumentation.initialize(
            config = ErrorConfig(captureUncaughtExceptions = true),
            logger = logger,
            onCrashPersist = { callOrder.add("sdk.persist") },
        )

    private fun dispatchCrash(throwable: Throwable) {
        val jvmDefault = Thread.getDefaultUncaughtExceptionHandler()
        checkNotNull(jvmDefault) { "no default handler installed" }
        jvmDefault.uncaughtException(Thread.currentThread(), throwable)
    }

    private fun crashLogCount(): Int =
        exporter.exportedLogs.count { it.bodyValue?.asString() == "app.crash" }

    @Test
    fun `reporter installed BEFORE the SDK still runs, after the SDK captures`() {
        // Crashlytics-style: Firebase installs via ContentProvider before SDK start.
        val crashlytics = StubReporter("crashlytics", callOrder)
        Thread.setDefaultUncaughtExceptionHandler(crashlytics)

        initSdkErrorInstrumentation()

        val boom = RuntimeException("pre-installed reporter chain")
        dispatchCrash(boom)

        // Both ran; the downstream reporter saw the SAME thread/throwable.
        assertEquals(1, crashlytics.invocations, "pre-existing handler must run exactly once")
        assertSame(boom, crashlytics.lastThrowable)
        assertSame(Thread.currentThread(), crashlytics.lastThread)

        // Exactly one app.crash.
        assertEquals(1, crashLogCount(), "crash must be reported exactly once")

        // Ordering: SDK persisted BEFORE delegating downstream — Crashlytics may
        // kill the process, so persist-after-delegate would silently lose data.
        assertEquals(
            listOf("sdk.persist", "crashlytics"),
            callOrder,
            "SDK must capture+persist before the downstream handler runs",
        )
    }

    @Test
    fun `reporter installed AFTER the SDK chains back and the SDK still captures`() {
        // Sentry/Crashlytics installed after SDK start: they save OUR handler
        // as their "previous" and chain to it, the mirror of scenario 1.
        initSdkErrorInstrumentation()

        val sdkHandler = Thread.getDefaultUncaughtExceptionHandler()
        val crashlytics = StubReporter("crashlytics", callOrder, chainTo = sdkHandler)
        Thread.setDefaultUncaughtExceptionHandler(crashlytics)

        val boom = IllegalStateException("post-installed reporter chain")
        dispatchCrash(boom)

        assertEquals(1, crashlytics.invocations, "late-installed reporter must run exactly once")
        assertEquals(1, crashLogCount(), "crash must be reported exactly once")
        assertEquals(
            listOf("crashlytics", "sdk.persist"),
            callOrder,
            "late-installed reporter runs first, then the SDK captures via its chain",
        )
    }

    @Test
    fun `three-deep chain reports the crash exactly once end to end`() {
        // Pre-existing reporter + SDK + late reporter chaining to the SDK:
        // the worst realistic pile-up. Every layer must run once; the SDK must
        // emit exactly one app.crash.
        val preExisting = StubReporter("pre-existing", callOrder)
        Thread.setDefaultUncaughtExceptionHandler(preExisting)

        initSdkErrorInstrumentation()

        val sdkHandler = Thread.getDefaultUncaughtExceptionHandler()
        val late = StubReporter("late", callOrder, chainTo = sdkHandler)
        Thread.setDefaultUncaughtExceptionHandler(late)

        dispatchCrash(RuntimeException("three-deep chain"))

        assertEquals(1, late.invocations)
        assertEquals(1, preExisting.invocations)
        assertEquals(1, crashLogCount(), "one crash, one report — regardless of chain depth")
        assertEquals(
            listOf("late", "sdk.persist", "pre-existing"),
            callOrder,
            "chain order: late reporter → SDK capture+persist → pre-existing handler",
        )
    }

    @Test
    fun `downstream handler throwing does not lose the SDK capture`() {
        // A hostile/broken downstream handler must not corrupt our capture —
        // the SDK already captured + persisted before delegating.
        val broken = object : Thread.UncaughtExceptionHandler {
            override fun uncaughtException(t: Thread, e: Throwable) {
                callOrder.add("broken")
                throw IllegalStateException("downstream handler exploded")
            }
        }
        Thread.setDefaultUncaughtExceptionHandler(broken)

        initSdkErrorInstrumentation()

        var downstreamFailure: Throwable? = null
        try {
            dispatchCrash(RuntimeException("hostile downstream"))
        } catch (t: Throwable) {
            downstreamFailure = t // propagation is acceptable; data loss is not
        }

        assertEquals(1, crashLogCount(), "SDK capture must complete before the downstream explosion")
        assertTrue(callOrder.first() == "sdk.persist", "persist happens before downstream runs")
        // (downstreamFailure may or may not propagate depending on the JVM's
        // dispatch; either way the assertion above proves no data loss.)
        @Suppress("UNUSED_EXPRESSION")
        downstreamFailure
    }

    private fun resetSingleton() {
        try {
            val field = ErrorInstrumentation::class.java.getDeclaredField("instance")
            field.isAccessible = true
            field.set(null, null)
        } catch (_: Exception) {
            // Ignore if field doesn't exist
        }
    }
}
