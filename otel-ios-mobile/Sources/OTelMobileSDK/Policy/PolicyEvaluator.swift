/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation

// MARK: - PolicyEvaluator
//
// iOS port of the matcher-evaluation engine in
// `otel-android-mobile/src/main/java/io/opentelemetry/android/mobile/policy/
// PolicyEvaluator.kt` (lines 152-295). The fetch/poll and geo/device matchers
// remain Android-only for now; here we ship just the pure `evaluate(...)`
// engine that the future iOS `MobileLogRecordProcessor` integration will call.
//
// Safety (per docs/SDK_SAFETY.md):
//   * No force-unwraps, no `fatalError`.
//   * Regex compilation errors are swallowed and cached as a "broken" marker
//     so we never re-attempt a broken pattern on every event.
//   * Numeric parse failures return no-match (never crash).
//   * Regex cache is LRU-bounded to 32 entries to cap memory from pathological
//     remote configs.

/// Thread-safe policy matcher. The underlying `PolicyConfig` is swappable via
/// `updatePolicies(_:)`; every `evaluate(...)` call reads whatever config was
/// installed at that moment. Lock-protected (NSLock) so config swaps and
/// regex-cache mutations cannot race with concurrent evaluations — and so
/// the synchronous `onEmit` hot path can evaluate policies without needing
/// a cooperative-executor slot (issue #66).
public final class PolicyEvaluator: @unchecked Sendable {

    // MARK: Constants

    /// LRU cap on compiled regex patterns. Mirrors Android's MAX_REGEX_CACHE.
    private static let maxRegexCacheEntries: Int = 32

    /// Maximum regex pattern length allowed from remote config. Mirrors
    /// Android's ReDoS defence (MAX_REGEX_LENGTH = 200).
    private static let maxRegexPatternLength: Int = 200

    // MARK: State

    private let lock = NSLock()

    private var policies: [Policy]

    /// Regex cache. A `nil` value means "we tried to compile this pattern and
    /// it was invalid — don't retry". This is critical for SDK stability:
    /// repeatedly recompiling a malformed pattern on every event would be a
    /// real performance footgun with a hostile remote config.
    private var regexCache: [String: NSRegularExpression?] = [:]

    /// Parallel LRU queue — oldest pattern at index 0. We only mutate this
    /// under `lock`, so a plain array is race-free here.
    private var regexLruOrder: [String] = []

    // MARK: Init

    public init(policies: [Policy] = []) {
        self.policies = policies
    }

    // MARK: Public API

    /// Atomically replace the current policy set. Also invalidates any
    /// cached compiled regexes whose pattern strings no longer appear in the
    /// new config — prevents stale compilations from lingering after a
    /// config refresh.
    public func updatePolicies(_ policies: [Policy]) {
        lock.lock(); defer { lock.unlock() }
        self.policies = policies
        pruneRegexCacheAgainstCurrentPolicies()
    }

    /// Number of policies currently loaded. Useful for tests and diagnostics.
    public func currentPolicyCount() -> Int {
        lock.lock(); defer { lock.unlock() }
        return policies.count
    }

    /// Evaluate an event's attributes against every enabled policy in order.
    /// Returns the first matching policy's flush action, or `nil` if none
    /// match (or if `policies` is empty).
    ///
    /// `contextSnapshot` is accepted for future geo/device matching but
    /// currently ignored — the iOS Match model doesn't yet carry geo/device
    /// constraints (that's a follow-up port).
    public func evaluate(
        attributes: [String: String],
        contextSnapshot: ContextSnapshot? = nil
    ) -> PolicyMatchResult? {
        lock.lock(); defer { lock.unlock() }
        _ = contextSnapshot  // reserved; suppress unused warning
        for policy in policies {
            if !policy.enabled { continue }
            if matches(policy: policy, attributes: attributes) {
                return PolicyMatchResult(
                    policyId: policy.id,
                    flushWindowMinutes: policy.actions.flushWindowMinutes
                )
            }
        }
        return nil
    }

    // MARK: - Matching

    /// Android parity note: Android refuses to match a policy with zero
    /// constraints to avoid flushing on every event. The iOS `Match` model
    /// only has attributes today, so the equivalent guard is "attributes
    /// must be non-empty".
    private func matches(policy: Policy, attributes: [String: String]) -> Bool {
        let conditions = policy.match.attributes
        if conditions.isEmpty { return false }

        switch policy.match.logicalOperator.lowercased() {
        case "or":
            for (key, condition) in conditions {
                if evaluateCondition(condition, value: attributes[key]) { return true }
            }
            return false
        case "and":
            // "and" is the default. Falls through.
            fallthrough
        default:
            for (key, condition) in conditions {
                if !evaluateCondition(condition, value: attributes[key]) { return false }
            }
            return true
        }
    }

    /// Apply a `Condition` to a (possibly missing) attribute value. All
    /// non-nil operator fields on a condition must hold (AND).
    ///
    /// Operators:
    ///   * `equals`:     exact string match
    ///   * `notEquals`:  exact string mismatch (missing attr => no match)
    ///   * `gt/lt/gte/lte`: numeric compare after Double parse; parse failure
    ///     or missing attr => no match
    ///   * `contains`:   substring match (missing attr => no match)
    ///   * `regex`:      NSRegularExpression full-string match
    ///
    /// If *every* operator field is nil the condition is effectively empty
    /// and we treat it as no-match — this mirrors Android's else-branch.
    private func evaluateCondition(_ condition: Condition, value: String?) -> Bool {
        var anyOperatorChecked = false

        if let expected = condition.equals {
            anyOperatorChecked = true
            guard let v = value else { return false }
            if v != expected { return false }
        }
        if let expected = condition.notEquals {
            anyOperatorChecked = true
            // Missing attribute => no match (Android: getAttributeValue returns null,
            // matchesCondition returns false at the top-of-function null check).
            guard let v = value else { return false }
            if v == expected { return false }
        }
        if let threshold = condition.gt {
            anyOperatorChecked = true
            guard let numeric = parseDouble(value) else { return false }
            if !(numeric > threshold) { return false }
        }
        if let threshold = condition.lt {
            anyOperatorChecked = true
            guard let numeric = parseDouble(value) else { return false }
            if !(numeric < threshold) { return false }
        }
        if let threshold = condition.gte {
            anyOperatorChecked = true
            guard let numeric = parseDouble(value) else { return false }
            if !(numeric >= threshold) { return false }
        }
        if let threshold = condition.lte {
            anyOperatorChecked = true
            guard let numeric = parseDouble(value) else { return false }
            if !(numeric <= threshold) { return false }
        }
        if let needle = condition.contains {
            anyOperatorChecked = true
            guard let v = value else { return false }
            if !v.contains(needle) { return false }
        }
        if let pattern = condition.regex {
            anyOperatorChecked = true
            guard let v = value else { return false }
            if !matchesRegex(v, pattern: pattern) { return false }
        }

        return anyOperatorChecked
    }

    // MARK: - Helpers

    /// Parse a string to Double without throwing. Returns nil on any failure
    /// (missing, empty, non-numeric). Centralising this keeps the safety
    /// contract explicit.
    private func parseDouble(_ value: String?) -> Double? {
        guard let v = value, !v.isEmpty else { return nil }
        return Double(v)
    }

    /// Compile-once regex match. Failed compilations are cached as `nil` so a
    /// malformed pattern doesn't re-trigger NSRegularExpression's throwing
    /// init path on every event.
    private func matchesRegex(_ value: String, pattern: String) -> Bool {
        if pattern.count > Self.maxRegexPatternLength { return false }

        // Look up or compile.
        let regex: NSRegularExpression?
        if let cached = regexCache[pattern] {
            regex = cached
            touchLru(pattern)
        } else {
            regex = try? NSRegularExpression(pattern: pattern, options: [])
            insertIntoCache(pattern: pattern, regex: regex)
        }

        guard let compiled = regex else { return false }

        // Mirror Kotlin's `String.matches(Regex)` — must match the *whole*
        // string, not just a substring. NSRegularExpression's default is
        // substring; we enforce a full-range match instead.
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        guard let first = compiled.firstMatch(in: value, options: [], range: range) else {
            return false
        }
        return first.range == range
    }

    private func insertIntoCache(pattern: String, regex: NSRegularExpression?) {
        regexCache[pattern] = regex
        regexLruOrder.append(pattern)
        // Evict oldest if we've exceeded the cap. Guard against the
        // pathological case where `pattern` was already in the cache (it
        // shouldn't be — we only insert on cache miss — but belt & braces).
        while regexLruOrder.count > Self.maxRegexCacheEntries {
            let evicted = regexLruOrder.removeFirst()
            regexCache.removeValue(forKey: evicted)
        }
    }

    private func touchLru(_ pattern: String) {
        // Move to end (most-recently-used). Linear scan is fine at cap=32.
        if let idx = regexLruOrder.firstIndex(of: pattern) {
            regexLruOrder.remove(at: idx)
            regexLruOrder.append(pattern)
        }
    }

    /// After a policy swap, drop compiled regexes that don't appear in the
    /// new config. Keeps the cache from retaining patterns that can never
    /// match again.
    private func pruneRegexCacheAgainstCurrentPolicies() {
        let livePatterns: Set<String> = Set(
            policies.flatMap { policy in
                policy.match.attributes.values.compactMap { $0.regex }
            }
        )
        for pattern in regexCache.keys where !livePatterns.contains(pattern) {
            regexCache.removeValue(forKey: pattern)
            if let idx = regexLruOrder.firstIndex(of: pattern) {
                regexLruOrder.remove(at: idx)
            }
        }
    }
}
