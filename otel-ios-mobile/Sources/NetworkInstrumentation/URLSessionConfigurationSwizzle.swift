import Foundation
import ObjectiveC.runtime

/// Swizzles `URLSessionConfiguration.protocolClasses` getter so that sessions
/// created *after* `install()` automatically include `OTelURLProtocol` at the
/// head of their protocol-class chain — in addition to the globally registered
/// protocol class. This is best-effort: if the selector can't be found, the
/// global registration still covers `URLSession.shared`, which is what the
/// overwhelming majority of apps use.
///
/// Uninstall is a no-op by design: reliably reversing method_setImplementation
/// across runtime versions is hard, and safe to skip because
/// `NetworkInstrumentation.enabled` gates `canInit` on the protocol.
enum URLSessionConfigurationSwizzle {
    private static var installed = false

    static func install() {
        guard !installed else { return }
        installed = true

        let cls: AnyClass = URLSessionConfiguration.self
        let selector = #selector(getter: URLSessionConfiguration.protocolClasses)
        guard let original = class_getInstanceMethod(cls, selector) else { return }

        typealias Getter = @convention(c) (AnyObject, Selector) -> [AnyClass]?
        let originalImp = method_getImplementation(original)
        let origFunc = unsafeBitCast(originalImp, to: Getter.self)

        let block: @convention(block) (AnyObject) -> [AnyClass]? = { this in
            let existing = origFunc(this, selector) ?? []
            if existing.contains(where: { $0 === OTelURLProtocol.self }) {
                return existing
            }
            return [OTelURLProtocol.self] + existing
        }
        let newImp = imp_implementationWithBlock(block)
        method_setImplementation(original, newImp)
    }

    static func uninstall() {
        // No-op: reversing the swizzle is risky across runtime versions.
        // NetworkInstrumentation.enabled flips to false and canInit returns
        // false, effectively disabling capture.
        installed = false
    }
}
