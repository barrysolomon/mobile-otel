// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

import OpenTelemetryApi
import Foundation

/// Survives app background → foreground transitions without orphaning a
/// journey's child spans.
///
/// Mirrors Android's `JourneyTracker` exactly — see
/// `otel-android-mobile/.../journey/JourneyTracker.kt` for the canonical
/// design rationale. In short: a journey span started via
/// `OTelMobile.startJourney` and made the active context becomes the
/// parent of every page / tap / network span that fires while it's open.
/// If the user backgrounds the app mid-flow without `endJourney`, the
/// already-ended children flush via the periodic batch processor while
/// the parent stays open forever — never reaches the exporter — and
/// Dash0 renders the children as orphans with "Missing span" where the
/// parent should be.
///
/// Fix: close any open journey on background with `journey.outcome=paused`,
/// and on foreground start a new sibling span carrying the same
/// `journey.id`, an incremented `journey.episode`, and an OTel `Link` to
/// the previous span's `SpanContext`. Each foreground episode is therefore
/// a clean, bounded trace; the link chain stitches them into a single
/// logical journey at query time
/// (`dash0 traces get <id> --follow-span-links`).
///
/// Thread-safety: `startJourney` / `endJourney` / `onBackground` /
/// `onForeground` should be called from the main thread to interact
/// safely with the active OTel context. Internal state uses an unfair
/// lock to be defensive — `MobileOtel`'s notification callbacks may
/// arrive on background threads in unusual cases.
public final class JourneyTracker: @unchecked Sendable {

    private struct OpenJourney {
        let span: Span
        let journeyId: String
        let episode: Int64
    }

    private struct PausedJourney {
        let spanContext: SpanContext
        let journeyId: String
        let episode: Int64
    }

    private let tracer: Tracer
    private let lock = NSLock()
    private var openByName: [String: OpenJourney] = [:]
    private var pausedByName: [String: PausedJourney] = [:]

    public init(tracer: Tracer) {
        self.tracer = tracer
    }

    // MARK: - Public API

    /// Starts a journey span tagged with a fresh `journey.id`. Returns the
    /// underlying `Span` so callers can `setActiveSpan` or set additional
    /// attributes — the wrapper does not change span semantics.
    ///
    /// If a journey with this name is already open, the existing one is
    /// returned without creating a new span. This protects against
    /// duplicate starts from re-entrant lifecycle callbacks.
    @discardableResult
    public func startJourney(name: String) -> Span {
        lock.lock()
        defer { lock.unlock() }
        if let existing = openByName[name] {
            return existing.span
        }
        let journeyId = UUID().uuidString
        return startEpisodeLocked(name: name, journeyId: journeyId, episode: 1, linkTo: nil)
    }

    /// Ends the named journey with `outcome` stamped as `journey.outcome`.
    /// Clears any paused state for this name so a later `onForeground` will
    /// not resurrect it.
    public func endJourney(name: String, outcome: String) {
        lock.lock()
        defer { lock.unlock() }
        endJourneyLocked(name: name, outcome: outcome)
    }

    /// Reverse-lookup variant of `endJourney` for callers that hold the
    /// `Span` returned by `startJourney` but not its name. Linear search
    /// over open journeys (typically a handful at a time) by `SpanContext`
    /// identity (matched on `spanId` since `Span` itself is not Equatable).
    ///
    /// Returns `true` if a matching open journey was found and ended;
    /// `false` if the span is not tracked (caller should `end()` it
    /// themselves to avoid a leak).
    @discardableResult
    public func endJourneyBySpan(_ span: Span, outcome: String) -> Bool {
        let targetId = span.context.spanId
        lock.lock()
        defer { lock.unlock() }
        guard let match = openByName.first(where: { $0.value.span.context.spanId == targetId }) else {
            return false
        }
        endJourneyLocked(name: match.key, outcome: outcome)
        return true
    }

    /// Closes every open journey with `journey.outcome=paused` and stashes
    /// each one's `SpanContext` so a subsequent `onForeground` can start a
    /// linked next episode.
    public func onBackground() {
        lock.lock()
        defer { lock.unlock() }
        let snapshot = openByName
        openByName.removeAll()
        for (name, open) in snapshot {
            open.span.setAttribute(key: Attr.outcome, value: AttributeValue.string(Outcome.paused))
            open.span.end()
            pausedByName[name] = PausedJourney(
                spanContext: open.span.context,
                journeyId: open.journeyId,
                episode: open.episode
            )
        }
    }

    /// Called when a policy-triggered flush fires (e.g. `http.error` policy
    /// exporting a 2-minute window). The user is still on the same screen,
    /// so we do NOT start a new linked episode — that would emit a "ghost"
    /// span that never accumulates children. Instead we just close any open
    /// journey with `journey.outcome=flushed` so the export captures a real
    /// parent for the children that are about to flush, and clear any
    /// paused state so a later foreground does not resurrect the journey.
    public func onPolicyFlush() {
        lock.lock()
        defer { lock.unlock() }
        let snapshot = openByName
        openByName.removeAll()
        pausedByName.removeAll()
        for (_, open) in snapshot {
            open.span.setAttribute(key: Attr.outcome, value: AttributeValue.string(Outcome.flushed))
            open.span.end()
        }
    }

    /// For each previously paused journey, starts a new episode span with
    /// the same `journey.id`, incremented `journey.episode`, and a Link
    /// back to the paused span's context. The new span is NOT automatically
    /// made current — callers wanting auto-parenting of subsequent UI
    /// spans must do so themselves.
    public func onForeground() {
        lock.lock()
        defer { lock.unlock() }
        let snapshot = pausedByName
        pausedByName.removeAll()
        for (name, paused) in snapshot {
            startEpisodeLocked(
                name: name,
                journeyId: paused.journeyId,
                episode: paused.episode + 1,
                linkTo: paused.spanContext
            )
        }
    }

    // MARK: - Internals (must be called with `lock` held)

    @discardableResult
    private func startEpisodeLocked(
        name: String,
        journeyId: String,
        episode: Int64,
        linkTo: SpanContext?
    ) -> Span {
        var builder = tracer.spanBuilder(spanName: name)
            .setSpanKind(spanKind: .internal)
            .setAttribute(key: Attr.name, value: AttributeValue.string(name))
            .setAttribute(key: Attr.id, value: AttributeValue.string(journeyId))
            .setAttribute(key: Attr.episode, value: AttributeValue.int(Int(episode)))
        if let ctx = linkTo {
            builder = builder.addLink(spanContext: ctx)
        }
        let span = builder.startSpan()
        openByName[name] = OpenJourney(span: span, journeyId: journeyId, episode: episode)
        return span
    }

    private func endJourneyLocked(name: String, outcome: String) {
        if let open = openByName.removeValue(forKey: name) {
            open.span.setAttribute(key: Attr.outcome, value: AttributeValue.string(outcome))
            open.span.end()
        }
        pausedByName.removeValue(forKey: name)
    }

    // MARK: - Attribute keys (mirror Android's)

    private enum Attr {
        static let name = "journey.name"
        static let id = "journey.id"
        static let episode = "journey.episode"
        static let outcome = "journey.outcome"
    }

    /// Outcome values stamped on `journey.outcome`. Matches the Android
    /// constants exactly so server-side queries are platform-agnostic.
    public enum Outcome {
        public static let paused = "paused"
        public static let flushed = "flushed"
        public static let ended = "ended"
    }
}
