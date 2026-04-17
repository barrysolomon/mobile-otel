import Foundation

/// Test-only helpers exposed on the public `NetworkInstrumentation` type so
/// test files (which can't import Foundation directly under Swift Testing on
/// Command Line Tools) can exercise URL scrubbing and host filtering logic
/// without building a URLProtocol end-to-end.
///
/// Mirrors the pattern used by `BufferedEventTestSupport` and
/// `MobileLogRecordProcessorTestSupport`.
public extension NetworkInstrumentation {
    /// Scrub helper: builds a URL from the string and applies the same
    /// query-stripping that `OTelURLProtocol` uses when populating `url.full`.
    static func scrubForTesting(urlString: String, stripQuery: Bool) -> String {
        guard let url = URL(string: urlString) else { return urlString }
        return OTelURLProtocol.scrubUrlString(url, stripQuery: stripQuery)
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
