/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation

/// Test-only helpers exposed on the public `NetworkInstrumentation` type so
/// test files (which can't import Foundation directly under Swift Testing on
/// Command Line Tools) can exercise URL scrubbing and host filtering logic
/// without building a URLProtocol end-to-end.
///
/// Mirrors the pattern used by `BufferedEventTestSupport` and
/// `MobileLogRecordProcessorTestSupport`.
public extension NetworkInstrumentation {
    /// Scrub helper: builds a URL from the string and applies the same scrub
    /// pipeline that `OTelURLProtocol` uses when populating `url.full`. The
    /// `stripQuery` arg maps onto `NetworkConfig.stripQueryStrings`; the new
    /// `PiiScrubber` route is exercised via `NetworkConfig.default`
    /// (`scrubUrls = true`).
    static func scrubForTesting(urlString: String, stripQuery: Bool) -> String {
        guard let url = URL(string: urlString) else { return urlString }
        let config = NetworkConfig(
            stripQueryStrings: stripQuery,
            scrubUrls: false
        )
        return OTelURLProtocol.scrubUrlString(url, config: config)
    }

    /// Same as `scrubForTesting(urlString:stripQuery:)` but routes through
    /// `PiiScrubber.scrubUrl` (`scrubUrls = true`). Lets tests pin the
    /// PII-scrub semantics independently of the legacy strip-only path.
    static func scrubForTesting(
        urlString: String,
        stripQuery: Bool,
        scrubPathSegments: Bool
    ) -> String {
        guard let url = URL(string: urlString) else { return urlString }
        let config = NetworkConfig(
            stripQueryStrings: stripQuery,
            scrubUrls: true,
            scrubPathSegments: scrubPathSegments
        )
        return OTelURLProtocol.scrubUrlString(url, config: config)
    }

    /// Replicates the host-filter check that `OTelURLProtocol.canInit` applies.
    /// Returns true when the host should be captured, false when it's ignored
    /// or absent from an allowlist.
    static func hostPassesFilter(host: String, config: NetworkConfig) -> Bool {
        let lower = host.lowercased()
        if !config.allowedHosts.isEmpty, !config.allowedHosts.contains(lower) {
            return false
        }
        if config.ignoredHosts.contains(lower) {
            return false
        }
        return true
    }
}
