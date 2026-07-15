/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation

/// PII scrubber — direct port of Android's `PiiScrubber` (Kotlin `object`).
///
/// Stateless utility with caller-supplied gating. Patterns + tokens
/// match Android line-for-line so a customer who tunes a Dash0
/// dashboard against `[EMAIL]` / `[PHONE]` / `[CREDIT_CARD]` / `[SSN]`
/// markers gets identical signal from both platforms.
///
/// Wire-in (mirrors Android, not generic):
/// - `ErrorsInstrumentation` calls `scrubExceptionMessage` + `scrubStackTrace`
///   when `ErrorConfig.scrubStackTraces` is true.
/// - `NetworkInstrumentation` calls `scrubUrl` on captured request URLs
///   when `NetworkConfig.scrubUrls` is true.
/// - Customer code can call `scrubText` / `scrubAttributes` directly.
///
/// **Not** wired into `MobileLogRecordProcessor` — log bodies and arbitrary
/// attributes are NOT scrubbed by default. That's the Android contract:
/// scrubbing is opt-in by call site, not blanket.
public enum PiiScrubber {

    // MARK: - Patterns + tokens (literal Android parity)
    //
    // Patterns are stored as `NSRegularExpression?` so we can use `try?`
    // instead of force-`try` (banned by the SDK safety audit — see
    // `docs/SDK_SAFETY.md`). All patterns are hardcoded literals
    // validated by `PiiScrubberTests`; nil here would mean a future
    // SwiftFoundation regression, in which case the matching helpers
    // (`applyRegex` / `containsPii`) gracefully no-op rather than
    // crashing the host app.

    private static let emailPattern: NSRegularExpression? = try? NSRegularExpression(
        pattern: #"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"#,
        options: [.caseInsensitive]
    )

    /// Permissive phone match: international (+1234567890123) or US-style
    /// (123) 456-7890 / 123-456-7890 / 1234567890.
    private static let phonePattern: NSRegularExpression? = try? NSRegularExpression(
        pattern: #"\b(?:\+?[1-9]\d{6,14}|\(?\d{3}\)?[-.\s]?\d{3}[-.\s]?\d{4})\b"#,
        options: []
    )

    /// 16-digit credit card with optional dash/space separators every 4.
    private static let creditCardPattern: NSRegularExpression? = try? NSRegularExpression(
        pattern: #"\b\d{4}[\s-]?\d{4}[\s-]?\d{4}[\s-]?\d{4}\b"#,
        options: []
    )

    private static let ssnPattern: NSRegularExpression? = try? NSRegularExpression(
        pattern: #"\b\d{3}-\d{2}-\d{4}\b"#,
        options: []
    )

    /// iOS app-container path replacement. Android scrubs `/data/user/\d+/`
    /// → `/data/user/{uid}/`; iOS apps run inside
    /// `/var/mobile/Containers/Data/Application/<UUID>/...` so we redact the
    /// container UUID to `{app-container}`.
    private static let iosContainerPattern: NSRegularExpression? = try? NSRegularExpression(
        pattern: #"/var/mobile/Containers/(Data|Bundle)/Application/[A-F0-9-]+/"#,
        options: [.caseInsensitive]
    )

    /// UUID inline (used by `scrubUrl`).
    private static let uuidInPathPattern: NSRegularExpression? = try? NSRegularExpression(
        pattern: #"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"#,
        options: [.caseInsensitive]
    )

    /// Numeric path-segment id (used by `scrubUrl`).
    private static let numericPathIdPattern: NSRegularExpression? = try? NSRegularExpression(
        pattern: #"/\d+(?=/|$)"#,
        options: []
    )

    /// Query-param keys that are always redacted regardless of `allowQueryParams`.
    /// Compared lowercased.
    private static let sensitiveQueryParams: Set<String> = [
        "token", "api_key", "apikey", "api-key",
        "session", "session_id", "sessionid", "session-id",
        "access_token", "accesstoken", "access-token",
        "auth", "authorization",
        "password", "passwd", "pwd",
        "secret", "private_key", "privatekey",
        "credit_card", "creditcard", "cc",
        "ssn", "social_security",
    ]

    // MARK: - Public API (mirrors Android signatures)

    /// Scrub a URL. By default removes ALL query params and replaces UUID +
    /// numeric path segments with `{uuid}` / `{id}` placeholders.
    /// `allowQueryParams = true` keeps non-sensitive params and redacts
    /// only the names in `sensitiveQueryParams`.
    /// `scrubPathSegments = false` leaves path UUIDs/IDs intact.
    /// Uses raw-string regex on the URL rather than `URLComponents` so the
    /// emitted placeholders (`{uuid}`, `{id}`, `[REDACTED]`) survive
    /// without percent-encoding. We only fall back to `URLComponents` to
    /// validate the structure parses at all.
    public static func scrubUrl(
        _ url: String,
        allowQueryParams: Bool = false,
        scrubPathSegments: Bool = true
    ) -> String {
        if url.isEmpty || URLComponents(string: url) == nil {
            return "[INVALID_URL]"
        }
        // Split scheme + path from query so the path-scrub pattern doesn't
        // touch query values and vice versa.
        let (lhs, queryPart) = splitOnFirst("?", in: url)
        var path = lhs
        if scrubPathSegments {
            path = applyRegex(uuidInPathPattern, to: path, replacement: "{uuid}")
            path = applyRegex(numericPathIdPattern, to: path, replacement: "/{id}")
        }

        guard let queryPart = queryPart else { return path }
        if !allowQueryParams { return path }

        // Walk `key=value&key=value`. Only the names in
        // `sensitiveQueryParams` get redacted; others pass through verbatim
        // (no percent-encoding round-trip, so `[REDACTED]` stays literal).
        let scrubbedPairs: [String] = queryPart
            .split(separator: "&", omittingEmptySubsequences: false)
            .map { pair in
                let parts = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
                guard let name = parts.first else { return String(pair) }
                let nameStr = String(name)
                if sensitiveQueryParams.contains(nameStr.lowercased()) {
                    return "\(nameStr)=[REDACTED]"
                }
                return String(pair)
            }
        return "\(path)?\(scrubbedPairs.joined(separator: "&"))"
    }

    private static func splitOnFirst(_ sep: Character, in string: String) -> (String, String?) {
        guard let idx = string.firstIndex(of: sep) else { return (string, nil) }
        let before = String(string[string.startIndex..<idx])
        let after = String(string[string.index(after: idx)...])
        return (before, after)
    }

    /// Scrub a deep-link URI. Same semantics as `scrubUrl` but accepts
    /// custom schemes (`myapp://...`) that `URLComponents` is happy with.
    public static func scrubDeepLink(_ uri: String, allowQueryParams: Bool = false) -> String {
        scrubUrl(uri, allowQueryParams: allowQueryParams, scrubPathSegments: false)
    }

    /// Scrub the message string from a thrown error. Returns `""` for nil
    /// to match Android's `String?` contract.
    public static func scrubExceptionMessage(_ message: String?) -> String {
        guard let message = message else { return "" }
        return scrubText(message)
    }

    /// Scrub an iOS stack-trace (already-formatted frames from
    /// `Thread.callStackSymbols` etc.). Caps at `maxDepth`.
    /// Android-equivalent path patterns (`/data/user/...`) are no-ops on
    /// iOS but the iOS container path pattern fires.
    public static func scrubStackTrace(_ frames: [String], maxDepth: Int = 50) -> String {
        let capped = Array(frames.prefix(max(0, maxDepth)))
        return capped.map { applyRegex(iosContainerPattern, to: $0, replacement: "{app-container}/") }
            .joined(separator: "\n")
    }

    /// Scrub free-form text. Runs every PII pattern in order. Replacement
    /// tokens for each kind are fixed (`[EMAIL]`, `[PHONE]`, etc.).
    ///
    /// Order matters. iOS app-container paths embed a UUID whose hex
    /// digits look like a phone number to the phone regex; scrub the
    /// container path FIRST so the redacted form (`{app-container}/`)
    /// never tempts a later pattern.
    public static func scrubText(_ text: String) -> String {
        guard !text.isEmpty else { return text }
        var current = text
        current = applyRegex(iosContainerPattern, to: current, replacement: "{app-container}/")
        current = applyRegex(emailPattern, to: current, replacement: "[EMAIL]")
        current = applyRegex(phonePattern, to: current, replacement: "[PHONE]")
        current = applyRegex(creditCardPattern, to: current, replacement: "[CREDIT_CARD]")
        current = applyRegex(ssnPattern, to: current, replacement: "[SSN]")
        return current
    }

    /// Detect-only check. Returns true if any PII pattern matches `text`.
    /// Used by callers that want to log a heuristic counter without
    /// mutating the message.
    public static func containsPii(_ text: String) -> Bool {
        guard !text.isEmpty else { return false }
        let range = NSRange(text.startIndex..., in: text)
        for regex in [emailPattern, phonePattern, creditCardPattern, ssnPattern] {
            if let regex = regex,
               regex.firstMatch(in: text, options: [], range: range) != nil {
                return true
            }
        }
        return false
    }

    /// Validate an OTel attribute key per the spec: lowercase ASCII letters,
    /// digits, underscores, dots, dashes; must start with a letter; non-empty.
    /// Mirrors Android's `isValidAttributeKey`. Used pre-emit by callers
    /// that build attribute maps from untrusted input.
    public static func isValidAttributeKey(_ key: String) -> Bool {
        guard !key.isEmpty else { return false }
        let pattern = #"^[a-z][a-z0-9._-]*$"#
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return false }
        let range = NSRange(key.startIndex..., in: key)
        return regex.firstMatch(in: key, options: [], range: range) != nil
    }

    /// Scrub every `String`-typed value in a `[String: Any]` attribute map.
    /// Non-string values pass through untouched. Returns a new dict; never
    /// mutates input.
    public static func scrubAttributes(_ attributes: [String: Any]) -> [String: Any] {
        var out: [String: Any] = [:]
        out.reserveCapacity(attributes.count)
        for (key, value) in attributes {
            if let s = value as? String {
                out[key] = scrubText(s)
            } else {
                out[key] = value
            }
        }
        return out
    }

    // MARK: - Helpers

    /// Applies `regex` to `input`, replacing every match with `replacement`.
    /// Returns `input` unchanged when `regex` is nil — happens only if the
    /// hardcoded pattern failed to compile (test-validated as impossible).
    private static func applyRegex(
        _ regex: NSRegularExpression?,
        to input: String,
        replacement: String
    ) -> String {
        guard let regex = regex else { return input }
        let range = NSRange(input.startIndex..., in: input)
        return regex.stringByReplacingMatches(
            in: input,
            options: [],
            range: range,
            withTemplate: NSRegularExpression.escapedTemplate(for: replacement)
        )
    }
}
