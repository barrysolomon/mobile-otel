import Foundation
import Testing
@testable import VitalsInstrumentation
import OpenTelemetryApi
import OpenTelemetrySdk

/// Behavioural-parity coverage for `AppStartInstrumentation`. Mirrors
/// the intent of Android's `AppStartInstrumentationTest` — every
/// emitted span has the expected name and Android-equivalent
/// `mobile.app.start.*` attributes; cold-start is one-shot; warm-start
/// fires on every background→foreground transition.
///
/// `.serialized` because `AppStartInstrumentation.shared` is a
/// process-wide singleton — parallel tests would race on the
/// `coldStartMeasured` flag.
@Suite("AppStartInstrumentation", .serialized)
struct AppStartInstrumentationTests {

    /// In-memory SpanProcessor that captures every span end. Mirrors
    /// the LogCapture pattern in ErrorsInstrumentationTests.
    final class SpanCapture: SpanProcessor, @unchecked Sendable {
        private let lock = NSLock()
        private var captured: [SpanData] = []

        var spans: [SpanData] {
            lock.lock(); defer { lock.unlock() }
            return captured
        }

        var isStartRequired: Bool { false }
        var isEndRequired: Bool { true }

        func onStart(parentContext: SpanContext?, span: ReadableSpan) {}
        func onEnd(span: ReadableSpan) {
            lock.lock()
            captured.append(span.toSpanData())
            lock.unlock()
        }
        func shutdown(explicitTimeout: TimeInterval?) {}
        func forceFlush(timeout: TimeInterval?) {}
    }

    private func makeTracer(capture: SpanCapture) -> Tracer {
        let provider = TracerProviderBuilder()
            .add(spanProcessor: capture)
            .build()
        return provider.get(instrumentationName: "test", instrumentationVersion: "0")
    }

    /// Build a fresh capture + tracer, fully reset the singleton, and
    /// wire the new tracer in with `install`. We can't avoid the
    /// install-time deferred async emitColdStart, but we drain the
    /// main queue once after install so the test sees a deterministic
    /// snapshot of any auto-emitted spans before issuing its own
    /// explicit calls.
    @discardableResult
    private func freshSetup() -> SpanCapture {
        let cap = SpanCapture()
        let tracer = makeTracer(capture: cap)
        AppStartInstrumentation.shared.resetForTesting()
        AppStartInstrumentation.shared.tracerForTesting = tracer
        return cap
    }

    @Test("emitColdStart emits app.startup + app.start.cold spans")
    func coldStartEmitsBothSpans() async throws {
        let cap = freshSetup()
        AppStartInstrumentation.shared.emitColdStart()
        let names = cap.spans.map { $0.name }
        #expect(names.contains("app.startup"))
        #expect(names.contains("app.start.cold"))
    }

    @Test("app.startup span carries cold-start type + duration_ms")
    func startupSpanAttributes() async throws {
        let cap = freshSetup()
        AppStartInstrumentation.shared.emitColdStart()
        guard let startup = cap.spans.first(where: { $0.name == "app.startup" }) else {
            Issue.record("no app.startup span captured")
            return
        }
        #expect(startup.attributes["mobile.app.start.type"] == .string("cold"))
        if case .int(let ms)? = startup.attributes["mobile.app.start.duration_ms"] {
            #expect(ms >= 0)
        } else {
            Issue.record("duration_ms attribute missing or wrong type")
        }
    }

    @Test("app.start.cold span carries process_start_time epoch ms")
    func coldSpanCarriesProcessStartTime() async throws {
        let cap = freshSetup()
        AppStartInstrumentation.shared.emitColdStart()
        guard let cold = cap.spans.first(where: { $0.name == "app.start.cold" }) else {
            Issue.record("no app.start.cold span captured")
            return
        }
        if case .int(let epochMs)? = cold.attributes["mobile.app.start.process_start_time"] {
            // Should be a real epoch ms — orders of magnitude later
            // than the late-90s cutoff. (10^12 ≈ 2001-09-09.)
            #expect(epochMs > 1_000_000_000_000)
        } else {
            Issue.record("process_start_time attribute missing or wrong type")
        }
    }

    @Test("emitColdStart is one-shot — second call is a no-op")
    func coldStartIsOneShot() async throws {
        let cap = freshSetup()
        AppStartInstrumentation.shared.emitColdStart()
        let firstCount = cap.spans.count
        AppStartInstrumentation.shared.emitColdStart()
        #expect(cap.spans.count == firstCount, "expected no extra spans after second call, got \(cap.spans.count - firstCount)")
    }

    @Test("warm start emits app.start.warm with Android attribute names")
    func warmStartEmits() async throws {
        let cap = freshSetup()
        let bg = Date(timeIntervalSinceNow: -2.0)
        AppStartInstrumentation.shared.emitWarmStart(backgroundTime: bg)
        guard let warm = cap.spans.first(where: { $0.name == "app.start.warm" }) else {
            Issue.record("no app.start.warm span captured")
            return
        }
        #expect(warm.attributes["mobile.app.start.type"] == .string("warm"))
        if case .int(let ms)? = warm.attributes["mobile.app.start.duration_ms"] {
            // 2s background, give some slop for execution time
            #expect(ms >= 1500 && ms <= 5000)
        } else {
            Issue.record("warm duration_ms missing or wrong type")
        }
    }

    @Test("warm start with future backgroundTime is dropped (negative duration)")
    func warmStartIgnoresFuture() async throws {
        let cap = freshSetup()
        // 5 seconds in the future — duration would be negative.
        let bogus = Date(timeIntervalSinceNow: 5.0)
        AppStartInstrumentation.shared.emitWarmStart(backgroundTime: bogus)
        let warmCount = cap.spans.filter { $0.name == "app.start.warm" }.count
        #expect(warmCount == 0)
    }

    @Test("readProcessStartTime returns a non-nil Date on simulator")
    func readProcessStartTime() {
        // sysctl(KERN_PROC_PID, getpid()) should always succeed for
        // the running process. If it returns nil here, the platform
        // sandbox shifted — `install()` falls back to install-time
        // approximation in that case.
        guard let start = AppStartInstrumentation.readProcessStartTime() else {
            Issue.record("sysctl returned nil — fallback path now active")
            return
        }
        let now = Date()
        // Process started before now, but not before the test bundle
        // was built — sanity-check it's within the last hour.
        #expect(start <= now)
        #expect(now.timeIntervalSince(start) < 3600.0)
    }
}
