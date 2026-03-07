package io.opentelemetry.android.demo

import androidx.test.platform.app.InstrumentationRegistry
import io.opentelemetry.android.mobile.MobileOtel
import io.opentelemetry.api.logs.Severity
import java.util.UUID

/**
 * Controls pacing between demo scenario steps.
 *
 * Pass --paceMs=0 to disable pauses (CI/fast runs):
 *   ./gradlew :android:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.paceMs=0
 *
 * Default is 2000ms — gives traces time to appear as separate entries in Dash0.
 */
class DemoScenarioPace {

    val runId: String = UUID.randomUUID().toString()
    private val paceMs: Long
    private var stepIndex = 0

    init {
        val args = InstrumentationRegistry.getArguments()
        paceMs = args.getString("paceMs")?.toLongOrNull() ?: 2000L
    }

    fun step(scenarioName: String, stepName: String) {
        stepIndex++
        MobileOtel.sendEvent(
            "demo.step",
            mapOf(
                "scenario.name"       to scenarioName,
                "scenario.step"       to stepName,
                "scenario.step_index" to stepIndex,
                "demo.run_id"         to runId
            ),
            Severity.INFO
        )
        if (paceMs > 0) Thread.sleep(paceMs)
    }

    fun reset() {
        stepIndex = 0
    }
}
