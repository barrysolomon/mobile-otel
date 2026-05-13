/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.mobile.policy

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.opentelemetry.android.mobile.config.MobileConfig
import io.opentelemetry.android.mobile.context.ContextSnapshot
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.logs.data.Body
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.resources.Resource
import io.mockk.every
import io.mockk.mockk
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.fail

/**
 * Drives every JSON fixture under `golden/dsl/` through the Android
 * [PolicyEvaluator] and asserts each case's verdict matches.
 *
 * Sister test of `otel-ios-mobile/Tests/.../GoldenFixtureTests.swift`. Same
 * fixtures, same expectations — when a contract changes on one platform but
 * not the other, this test or its iOS counterpart goes red.
 *
 * Fixture format documented in `golden/README.md`.
 *
 * Uses reflection to reach `matchesPolicy` directly so we can supply a neutral
 * [ContextSnapshot] (geo/device matchers aren't currently exercised by
 * fixtures — they require a separate parity track to land on iOS first).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GoldenFixtureTest {

    private lateinit var context: Context
    private lateinit var evaluator: PolicyEvaluator
    private lateinit var neutralSnapshot: ContextSnapshot

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val config = MobileConfig(
            serviceName = "test-app",
            serviceVersion = "1.0.0",
            collectorEndpoint = "http://localhost:8080",
            attachContextAttributes = false
        )
        evaluator = PolicyEvaluator(context, config)
        neutralSnapshot = ContextSnapshot(
            country = "US",
            region = "CA",
            timezone = "America/Los_Angeles",
            locale = "en-US",
            appVersion = "1.0.0",
            osVersion = 28,
            deviceClass = ContextSnapshot.DEVICE_CLASS_PHONE,
            networkType = ContextSnapshot.NETWORK_WIFI,
            batteryState = "normal",
            buildChannel = "prod"
        )
    }

    @Test
    fun `all golden fixtures evaluate as expected`() {
        val fixtures = GoldenFixtureLoader.load()
        check(fixtures.isNotEmpty()) {
            "no golden fixtures found — verify golden/dsl/ exists relative to repo root"
        }

        val failures = mutableListOf<String>()
        val driftedAsExpected = mutableListOf<String>()
        for (fixture in fixtures) {
            for (testCase in fixture.cases) {
                val verdict = evaluateCase(fixture.policies, testCase.attributes)
                val expected = testCase.knownDriftActual ?: testCase.expectedMatch
                if (verdict != expected) {
                    failures.add(
                        "${fixture.filePath} :: ${testCase.name}: expected " +
                            "${expected ?: "null"}, got ${verdict ?: "null"}"
                    )
                } else if (testCase.knownDriftActual != null) {
                    driftedAsExpected.add(
                        "${fixture.filePath} :: ${testCase.name} (known drift: ${testCase.knownDriftReason ?: "documented"})"
                    )
                }
            }
        }

        if (driftedAsExpected.isNotEmpty()) {
            // Visible in test stdout — surfaces known drift without failing the suite
            println("Known platform drift observed (${driftedAsExpected.size}):")
            driftedAsExpected.forEach { println("  - $it") }
        }
        if (failures.isNotEmpty()) {
            fail("Golden fixture failures (${failures.size}):\n" + failures.joinToString("\n"))
        }
    }

    private fun evaluateCase(policies: List<Policy>, attributes: Map<String, String>): String? {
        val record = buildLogRecord(attributes)
        for (policy in policies) {
            if (!policy.enabled) continue
            if (invokeMatchesPolicy(record, neutralSnapshot, policy)) {
                return policy.id
            }
        }
        return null
    }

    private fun buildLogRecord(attributes: Map<String, String>): LogRecordData {
        // Build a minimal LogRecordData mock. event.name doubles as body so the
        // Android evaluator's body fallback (getAttributeValue) sees the same
        // value the iOS port reads from attributes.
        //
        // For attributes whose value parses as a Long or Double, stamp them
        // under the typed AttributeKey too — the Android evaluator's numeric
        // comparators (gt/lt/gte/lte) cast via `(value as? Number)?.toDouble()`
        // which returns null for String values. Without this dual stamping,
        // numeric fixtures like "http.status_code: 503" would always no-match
        // even though the iOS port (which stringifies everything) handles them.
        val bodyValue = attributes["event.name"] ?: attributes["body"] ?: ""
        val attrBuilder = Attributes.builder()
        for ((k, v) in attributes) {
            val asLong = v.toLongOrNull()
            val asDouble = v.toDoubleOrNull()
            when {
                asLong != null -> attrBuilder.put(AttributeKey.longKey(k), asLong)
                asDouble != null -> attrBuilder.put(AttributeKey.doubleKey(k), asDouble)
                else -> attrBuilder.put(AttributeKey.stringKey(k), v)
            }
        }
        val record = mockk<LogRecordData>(relaxed = true)
        every { record.attributes } returns attrBuilder.build()
        every { record.body } returns Body.string(bodyValue)
        every { record.severity } returns Severity.INFO
        every { record.resource } returns Resource.empty()
        every { record.instrumentationScopeInfo } returns InstrumentationScopeInfo.create("golden")
        return record
    }

    private fun invokeMatchesPolicy(
        record: LogRecordData,
        snapshot: ContextSnapshot,
        policy: Policy
    ): Boolean {
        val method = PolicyEvaluator::class.java.getDeclaredMethod(
            "matchesPolicy",
            LogRecordData::class.java,
            ContextSnapshot::class.java,
            Policy::class.java
        )
        method.isAccessible = true
        return method.invoke(evaluator, record, snapshot, policy) as Boolean
    }
}

/**
 * Loads golden fixtures from JSON files under `golden/dsl/` (recursive) relative
 * to the repo root. Walks up from the test working directory to find the workspace.
 */
internal object GoldenFixtureLoader {

    data class Fixture(
        val filePath: String,
        val name: String,
        val policies: List<Policy>,
        val cases: List<Case>
    )

    data class Case(
        val name: String,
        val attributes: Map<String, String>,
        val expectedMatch: String?,
        val knownDriftActual: String? = null,
        val knownDriftReason: String? = null
    )

    fun load(): List<Fixture> {
        val goldenDir = locateGoldenDir() ?: return emptyList()
        val dslDir = File(goldenDir, "dsl")
        if (!dslDir.isDirectory) return emptyList()
        val fixtures = mutableListOf<Fixture>()
        dslDir.walkTopDown().filter { it.isFile && it.extension == "json" }.forEach { file ->
            parse(file)?.let { fixtures.add(it) }
        }
        return fixtures.sortedBy { it.filePath }
    }

    private fun locateGoldenDir(): File? {
        var dir = File(System.getProperty("user.dir"))
        for (i in 0..10) {
            val candidate = File(dir, "golden")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    private fun parse(file: File): Fixture? {
        val json = JSONObject(file.readText())
        val name = json.optString("name", file.name)
        val policies = parseList(json.optJSONArray("policies")) { parsePolicy(it) }
        val cases = parseList(json.optJSONArray("cases")) { parseCase(it) }
        return Fixture(filePath = file.name, name = name, policies = policies, cases = cases)
    }

    private fun parsePolicy(obj: JSONObject): Policy {
        val matchObj = obj.optJSONObject("match") ?: JSONObject()
        val attrsObj = matchObj.optJSONObject("attributes") ?: JSONObject()
        val conditions = mutableMapOf<String, Condition>()
        for (key in attrsObj.keys()) {
            conditions[key] = parseCondition(attrsObj.getJSONObject(key))
        }
        val actionsObj = obj.optJSONObject("actions") ?: JSONObject()
        return Policy(
            id = obj.getString("id"),
            enabled = obj.optBoolean("enabled", true),
            match = Match(
                logicalOperator = matchObj.optString("operator", "and"),
                attributes = conditions
            ),
            actions = Actions(flushWindowMinutes = actionsObj.optInt("flushWindowMinutes", 2))
        )
    }

    private fun parseCondition(obj: JSONObject): Condition = Condition(
        equals = obj.optStringOrNull("equals"),
        notEquals = obj.optStringOrNull("notEquals"),
        gt = obj.optDoubleOrNull("gt"),
        lt = obj.optDoubleOrNull("lt"),
        gte = obj.optDoubleOrNull("gte"),
        lte = obj.optDoubleOrNull("lte"),
        contains = obj.optStringOrNull("contains"),
        regex = obj.optStringOrNull("regex")
    )

    private fun parseCase(obj: JSONObject): Case {
        val rawAttrs = obj.optJSONObject("attributes") ?: JSONObject()
        val attrs = mutableMapOf<String, String>()
        for (key in rawAttrs.keys()) {
            attrs[key] = rawAttrs.get(key).toString()
        }
        val expected = obj.optStringOrNull("expectedMatch")
        val drift = obj.optJSONObject("knownDrift")?.optJSONObject("android")
        return Case(
            name = obj.getString("name"),
            attributes = attrs,
            expectedMatch = expected,
            knownDriftActual = drift?.optStringOrNull("actual"),
            knownDriftReason = drift?.optStringOrNull("reason")
        )
    }

    private fun <T> parseList(arr: JSONArray?, parser: (JSONObject) -> T?): List<T> {
        if (arr == null) return emptyList()
        val out = mutableListOf<T>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { parser(it)?.let(out::add) }
        }
        return out
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (this.has(key) && !this.isNull(key)) this.optString(key) else null

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (this.has(key) && !this.isNull(key)) this.optDouble(key) else null
}
