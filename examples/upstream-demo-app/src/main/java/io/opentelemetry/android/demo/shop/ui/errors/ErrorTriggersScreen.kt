/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.demo.shop.ui.errors

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.opentelemetry.android.demo.OtelDemoApplication
import io.opentelemetry.android.demo.shop.ui.products.appFreezing
import io.opentelemetry.android.demo.shop.ui.products.multiThreadCrashing
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URL

/**
 * Demo screen surfacing every flavor of error/crash the SDK is supposed to capture.
 *
 * Each button below produces a distinct telemetry signal — pair the names here
 * with the rows in `docs/reference/TELEMETRY_SIGNALS.md` to map button → event.
 *
 * Cross-platform parity: the iOS `ErrorTriggersView` and RN `ErrorTriggersScreen`
 * provide the same buttons with the same labels and behavior. See
 * `docs/IOS_ANDROID_PARITY.md` (Error triggers row).
 */
@Composable
fun ErrorTriggersScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Error Triggers", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Each button emits a different OTel signal. Tap one, then check Dash0 " +
                "(filtered by service.name = otel-android-astronomy-shop) for the " +
                "resulting telemetry.",
            style = MaterialTheme.typography.bodySmall
        )

        SectionHeader("Handled errors — process keeps running")

        TriggerButton(
            label = "Log a handled error",
            description = "Emits app.error log record. severity=ERROR.",
            testTag = "trigger.handled_error"
        ) {
            emitHandledError("manual button: Log a handled error")
        }

        TriggerButton(
            label = "Catch a divide-by-zero",
            description = "Catches ArithmeticException, records via app.error.",
            testTag = "trigger.divide_by_zero_handled"
        ) {
            try {
                // Kotlin computes integer division at runtime when one operand
                // is a non-const expression — System.currentTimeMillis() ensures
                // the compiler can't constant-fold this to a warning.
                val zero = (System.currentTimeMillis() - System.currentTimeMillis()).toInt()
                val ignored = 10 / zero
                Log.i(TAG, "divide-by-zero somehow returned $ignored")
            } catch (e: ArithmeticException) {
                emitHandledError("ArithmeticException: ${e.message}", e)
            }
        }

        TriggerButton(
            label = "Trigger HTTP 500",
            description = "POST to httpbin.org/status/500. Auto-instrumentation " +
                "emits http.error → matches the http-error-detector policy → flushes the buffer.",
            testTag = "trigger.http_500"
        ) {
            GlobalScope.launch {
                try {
                    val client = OkHttpClient()
                    val req = Request.Builder()
                        .url("https://httpbin.org/status/500")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        Log.i(TAG, "HTTP 500 returned status=${resp.code}")
                    }
                } catch (t: Throwable) {
                    emitHandledError("HTTP 500 trigger failed: ${t.message}", t)
                }
            }
        }

        SectionHeader("Unhandled — kills the process")

        TriggerButton(
            label = "Crash on main thread",
            description = "Throws RuntimeException on main. Verifies app.crash " +
                "persistence + recovery flush on next launch.",
            testTag = "trigger.crash_main",
            isDanger = true
        ) {
            throw RuntimeException(
                "Demo Error Triggers: main-thread crash (deterministic)"
            )
        }

        TriggerButton(
            label = "Crash on background thread (multi-thread)",
            description = "Same mechanism as gate3_crash: 4 threads throw simultaneously. " +
                "Stress-tests the crash race with KillApplicationHandler.",
            testTag = "trigger.crash_multithread",
            isDanger = true
        ) {
            multiThreadCrashing()
        }

        TriggerButton(
            label = "Trigger ANR (freeze main thread 10s)",
            description = "Sleeps the main thread to fire ui.freeze and the " +
                "ui-freeze-detector policy.",
            testTag = "trigger.anr",
            isDanger = true
        ) {
            appFreezing()
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Spacer(modifier = Modifier.height(4.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
    HorizontalDivider()
}

@Composable
private fun TriggerButton(
    label: String,
    description: String,
    testTag: String,
    isDanger: Boolean = false,
    onClick: () -> Unit
) {
    val colors = if (isDanger) {
        ButtonDefaults.buttonColors(containerColor = Color(0xFFB00020))
    } else {
        ButtonDefaults.buttonColors()
    }
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        colors = colors,
        contentPadding = PaddingValues(12.dp)
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun emitHandledError(message: String, throwable: Throwable? = null) {
    val logger = OtelDemoApplication.logger(TAG) ?: run {
        Log.w(TAG, "OTel not initialized; skipping handled-error emit")
        return
    }
    val attrs = Attributes.builder()
        .put(AttributeKey.stringKey("event.name"), "app.error")
        .put(AttributeKey.stringKey("exception.message"), message)
        .also { b ->
            if (throwable != null) {
                b.put(AttributeKey.stringKey("exception.type"), throwable.javaClass.name)
                b.put(
                    AttributeKey.stringKey("exception.stacktrace"),
                    throwable.stackTraceToString()
                )
            }
        }
        .build()
    logger.logRecordBuilder()
        .setBody("app.error")
        .setSeverity(Severity.ERROR)
        .setAllAttributes(attrs)
        .emit()
    Log.i(TAG, "emitted app.error: $message")
}

private const val TAG = "ErrorTriggers"
