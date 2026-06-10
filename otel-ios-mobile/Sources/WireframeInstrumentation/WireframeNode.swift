import Foundation
#if canImport(CoreGraphics)
import CoreGraphics
#endif

public struct WireframeNode: Sendable, Equatable {
    public let type: String
    public let bounds: [Int]
    public let accessibilityIdentifier: String?
    public let hint: String?
    public let accessibilityLabel: String?
    public let isInteractive: Bool?
    public let isEnabled: Bool?
    public let truncated: Bool
    /// `true` when this node covers a sensitive region (a secure UIKit field or
    /// a view tagged via `Dash0.redact(_:)` / `.dash0Redacted()`). Redacted
    /// nodes carry NO text-bearing fields — no `hint`, `label`, or `id` — so
    /// the wireframe JSON can never leak sensitive content. The region's
    /// `type` and `bounds` are still emitted so layout is preserved.
    public let redacted: Bool
    public let children: [WireframeNode]

    public init(
        type: String,
        bounds: [Int],
        accessibilityIdentifier: String? = nil,
        hint: String? = nil,
        accessibilityLabel: String? = nil,
        isInteractive: Bool? = nil,
        isEnabled: Bool? = nil,
        truncated: Bool = false,
        redacted: Bool = false,
        children: [WireframeNode] = []
    ) {
        self.type = type
        self.bounds = bounds
        // Hard invariant: a redacted node never carries text-bearing fields,
        // regardless of what the caller passed. This makes leaking
        // structurally impossible at the data-model layer, not just at the
        // walk layer.
        self.accessibilityIdentifier = redacted ? nil : accessibilityIdentifier
        self.hint = redacted ? nil : hint
        self.accessibilityLabel = redacted ? nil : accessibilityLabel
        self.isInteractive = isInteractive
        self.isEnabled = isEnabled
        self.truncated = truncated
        self.redacted = redacted
        self.children = children
    }

    public func toJson() -> String {
        var parts: [String] = []
        parts.append("\"type\":\"\(escapeJson(type))\"")
        parts.append("\"bounds\":[\(bounds.map { String($0) }.joined(separator: ","))]")

        if let aid = accessibilityIdentifier {
            parts.append("\"id\":\"\(escapeJson(aid))\"")
        }
        if let h = hint {
            parts.append("\"hint\":\"\(escapeJson(h))\"")
        }
        if let al = accessibilityLabel {
            parts.append("\"label\":\"\(escapeJson(al))\"")
        }
        if let interactive = isInteractive {
            parts.append("\"interactive\":\(interactive)")
        }
        if let enabled = isEnabled {
            parts.append("\"enabled\":\(enabled)")
        }
        if truncated {
            parts.append("\"truncated\":true")
        }
        if redacted {
            parts.append("\"redacted\":true")
        }
        if !children.isEmpty {
            let childJson = children.map { $0.toJson() }.joined(separator: ",")
            parts.append("\"children\":[\(childJson)]")
        }

        return "{\(parts.joined(separator: ","))}"
    }

    public func nodeCount() -> Int {
        1 + children.reduce(0) { $0 + $1.nodeCount() }
    }

    private func escapeJson(_ s: String) -> String {
        s.replacingOccurrences(of: "\\", with: "\\\\")
         .replacingOccurrences(of: "\"", with: "\\\"")
         .replacingOccurrences(of: "\n", with: "\\n")
         .replacingOccurrences(of: "\r", with: "\\r")
         .replacingOccurrences(of: "\t", with: "\\t")
    }
}
