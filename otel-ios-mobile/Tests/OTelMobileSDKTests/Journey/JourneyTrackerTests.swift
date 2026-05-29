// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

import Foundation
import Testing
@testable import OTelMobileSDK
import OpenTelemetryApi
import OpenTelemetrySdk

/// Mirrors `JourneyTrackerTest.kt` — same scenarios, same assertions.
/// Bug being fixed: a journey span started via `OTelMobile.startJourney`
/// that never received `.end()` (because the user backgrounded the app
/// mid-flow) left its already-ended children pointing at a parent the
/// BatchSpanProcessor never enqueued.
@Suite("JourneyTracker")
struct JourneyTrackerTests {

    /// In-memory SpanProcessor that captures every span end.
    /// Same pattern as AppStartInstrumentationTests.SpanCapture.
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

    private func makeTracker() -> (JourneyTracker, SpanCapture) {
        let cap = SpanCapture()
        let provider = TracerProviderBuilder()
            .add(spanProcessor: cap)
            .build()
        let tracer = provider.get(instrumentationName: "test", instrumentationVersion: "0")
        return (JourneyTracker(tracer: tracer), cap)
    }

    // MARK: - Helpers

    private func attr(_ span: SpanData, _ key: String) -> String? {
        guard case let .string(v) = span.attributes[key] else { return nil }
        return v
    }

    private func attrInt(_ span: SpanData, _ key: String) -> Int? {
        guard case let .int(v) = span.attributes[key] else { return nil }
        return v
    }

    // MARK: - Tests

    @Test("startJourney returns a span with journey id, name, and episode attrs")
    func startJourneyStampsAttributes() {
        let (tracker, cap) = makeTracker()
        let span = tracker.startJourney(name: "book_appointment")
        span.end()

        #expect(cap.spans.count == 1)
        let data = cap.spans[0]
        #expect(data.name == "book_appointment")
        #expect(attr(data, "journey.name") == "book_appointment")
        #expect(attr(data, "journey.id") != nil)
        #expect(attrInt(data, "journey.episode") == 1)
    }

    @Test("onBackground ends an open journey with outcome=paused")
    func onBackgroundEndsWithPaused() {
        let (tracker, cap) = makeTracker()
        tracker.startJourney(name: "book_appointment")
        tracker.onBackground()

        #expect(cap.spans.count == 1)
        #expect(attr(cap.spans[0], "journey.outcome") == "paused")
    }

    @Test("onBackground is a no-op when no journey is open")
    func onBackgroundNoOpWhenNothingOpen() {
        let (tracker, cap) = makeTracker()
        tracker.onBackground()
        #expect(cap.spans.isEmpty)
    }

    @Test("onForeground after pause starts a new episode linked to the previous")
    func resumeStartsLinkedEpisode() {
        let (tracker, cap) = makeTracker()
        let first = tracker.startJourney(name: "book_appointment")
        let firstSpanId = first.context.spanId
        let firstTraceId = first.context.traceId

        tracker.onBackground()  // episode 1 ends, exports
        tracker.onForeground()  // episode 2 starts
        tracker.endJourney(name: "book_appointment", outcome: "success")

        #expect(cap.spans.count == 2)
        let ep1 = cap.spans.first { attrInt($0, "journey.episode") == 1 }!
        let ep2 = cap.spans.first { attrInt($0, "journey.episode") == 2 }!

        // Same journey.id across episodes
        #expect(attr(ep1, "journey.id") != nil)
        #expect(attr(ep1, "journey.id") == attr(ep2, "journey.id"))

        // Episode 2 is in a NEW trace (cross-trace link semantics)
        #expect(ep2.traceId != firstTraceId)

        // Episode 2 has exactly one Link pointing at episode 1
        #expect(ep2.links.count == 1)
        #expect(ep2.links[0].context.spanId == firstSpanId)
        #expect(ep2.links[0].context.traceId == firstTraceId)

        // Outcomes
        #expect(attr(ep1, "journey.outcome") == "paused")
        #expect(attr(ep2, "journey.outcome") == "success")
    }

    @Test("onForeground with no paused journey is a no-op")
    func resumeNoOpWhenNothingPaused() {
        let (tracker, cap) = makeTracker()
        tracker.onForeground()
        #expect(cap.spans.isEmpty)
    }

    @Test("multi-episode bg/fg/bg/fg increments episode count and chains links")
    func multiEpisodeChain() {
        let (tracker, cap) = makeTracker()
        tracker.startJourney(name: "book_appointment")

        tracker.onBackground()  // ep1 ends
        tracker.onForeground()  // ep2 starts
        tracker.onBackground()  // ep2 ends
        tracker.onForeground()  // ep3 starts
        tracker.endJourney(name: "book_appointment", outcome: "success")

        let spans = cap.spans.sorted { (attrInt($0, "journey.episode") ?? 0) < (attrInt($1, "journey.episode") ?? 0) }
        #expect(spans.count == 3)
        #expect(attrInt(spans[0], "journey.episode") == 1)
        #expect(attrInt(spans[1], "journey.episode") == 2)
        #expect(attrInt(spans[2], "journey.episode") == 3)

        // All three carry the same journey.id
        let ids = Set(spans.map { attr($0, "journey.id") })
        #expect(ids.count == 1)

        // Link chain: ep2 links to ep1, ep3 links to ep2
        #expect(spans[1].links.first?.context.spanId == spans[0].spanId)
        #expect(spans[2].links.first?.context.spanId == spans[1].spanId)

        // Outcomes: first two paused, last is success
        #expect(attr(spans[0], "journey.outcome") == "paused")
        #expect(attr(spans[1], "journey.outcome") == "paused")
        #expect(attr(spans[2], "journey.outcome") == "success")
    }

    @Test("endJourney before background closes and clears state")
    func endJourneyClearsState() {
        let (tracker, cap) = makeTracker()
        tracker.startJourney(name: "book_appointment")
        tracker.endJourney(name: "book_appointment", outcome: "success")

        // bg/fg now must NOT resurrect
        tracker.onBackground()
        tracker.onForeground()

        #expect(cap.spans.count == 1)
        #expect(attr(cap.spans[0], "journey.outcome") == "success")
    }

    @Test("multiple concurrent journeys are tracked independently by name")
    func concurrentJourneys() {
        let (tracker, cap) = makeTracker()
        tracker.startJourney(name: "onboarding")
        tracker.startJourney(name: "book_appointment")

        tracker.onBackground()  // both episode 1s end
        tracker.onForeground()  // both episode 2s start
        tracker.endJourney(name: "onboarding", outcome: "success")
        tracker.endJourney(name: "book_appointment", outcome: "success")

        // 2 paused + 2 resumed-and-ended = 4 spans
        #expect(cap.spans.count == 4)
        let names = Set(cap.spans.map { $0.name })
        #expect(names == Set(["onboarding", "book_appointment"]))
    }

    @Test("onPolicyFlush ends open journeys with outcome=flushed and does not start new episodes")
    func policyFlushEndsButDoesNotResume() {
        let (tracker, cap) = makeTracker()
        tracker.startJourney(name: "book_appointment")

        tracker.onPolicyFlush()

        #expect(cap.spans.count == 1)
        #expect(attr(cap.spans[0], "journey.outcome") == "flushed")

        // Subsequent foreground must NOT resurrect — onPolicyFlush clears paused state
        tracker.onForeground()
        #expect(cap.spans.count == 1)
    }

    @Test("onPolicyFlush during a paused journey clears state without re-emitting")
    func policyFlushDuringPausedClearsState() {
        let (tracker, cap) = makeTracker()
        tracker.startJourney(name: "book_appointment")
        tracker.onBackground()  // 1 span exported (paused)

        tracker.onPolicyFlush()  // no new export, but clears paused state

        tracker.onForeground()  // must NOT resurrect

        #expect(cap.spans.count == 1)
    }

    @Test("onPolicyFlush is a no-op when no journey is open")
    func policyFlushNoOpWhenNothingOpen() {
        let (tracker, cap) = makeTracker()
        tracker.onPolicyFlush()
        #expect(cap.spans.isEmpty)
    }

    @Test("endJourneyBySpan finds the open journey by span identity and ends it")
    func endJourneyBySpan() {
        let (tracker, cap) = makeTracker()
        let span = tracker.startJourney(name: "book_appointment")

        let ended = tracker.endJourneyBySpan(span, outcome: "success")
        #expect(ended == true)

        #expect(cap.spans.count == 1)
        #expect(attr(cap.spans[0], "journey.outcome") == "success")

        // bg/fg now is a no-op
        tracker.onBackground()
        tracker.onForeground()
        #expect(cap.spans.count == 1)
    }

    @Test("endJourneyBySpan returns false for an unknown span")
    func endJourneyBySpanUnknown() {
        let (tracker, _) = makeTracker()
        let other = TracerProviderBuilder().build()
            .get(instrumentationName: "other")
            .spanBuilder(spanName: "not_a_journey")
            .startSpan()
        defer { other.end() }

        let ended = tracker.endJourneyBySpan(other, outcome: "success")
        #expect(ended == false)
    }
}
