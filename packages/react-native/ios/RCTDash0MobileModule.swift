// Swift half of the RN native module. The ObjC side (RCTDash0MobileModule.m)
// declares the RN-visible interface; this file implements the methods.
//
// Deliberately thin — all dispatch logic is in Dash0MobileBridgeDispatcher.

import Foundation

@objc(Dash0Mobile)
public final class Dash0MobileModule: NSObject {
    private let dispatcher: Dash0MobileBridgeDispatcher

    /// Host-injected sink factory. When the RN bridge pod can't see the
    /// native SDK at compile time (common in hybrid CocoaPods+SwiftPM
    /// setups — the pod builds without the app's Swift Package graph),
    /// the host app calls `Dash0MobileModule.installSink(...)` from its
    /// AppDelegate to wire the real `OTelMobileCallSink`. Without that,
    /// we fall back to a no-op sink so the JS surface stays functional
    /// in tests / SSR / apps that haven't opted in yet.
    ///
    /// Not `@objc` — the closure's parameter type is a Swift-only protocol.
    /// App-side callers must `import Dash0Mobile` and invoke it from Swift.
    public static var sinkFactory: () -> BridgeCallSink = { NoopSink() }

    public static func installSink(_ factory: @escaping () -> BridgeCallSink) {
        NSLog("[Dash0Mobile] installSink called")
        sinkFactory = factory
    }

    override init() {
        let sink = Dash0MobileModule.sinkFactory()
        NSLog("[Dash0Mobile] Module init with sink type: \(type(of: sink))")
        self.dispatcher = Dash0MobileBridgeDispatcher(sink: sink)
        super.init()
    }

    // MARK: - For tests

    init(dispatcher: Dash0MobileBridgeDispatcher) {
        self.dispatcher = dispatcher
        super.init()
    }

    // MARK: - RN-visible methods

    @objc(start:resolver:rejecter:)
    func start(
        config: [String: Any],
        resolver: @escaping (Any?) -> Void,
        rejecter: @escaping (String, String?, Error?) -> Void
    ) {
        do {
            try dispatcher.start(config: config)
            resolver(nil)
        } catch {
            rejecter("Dash0Mobile.start", error.localizedDescription, error)
        }
    }

    @objc(emitBatch:resolver:rejecter:)
    func emitBatch(
        payloads: [[String: Any]],
        resolver: @escaping (Any?) -> Void,
        rejecter: @escaping (String, String?, Error?) -> Void
    ) {
        dispatcher.emitBatch(payloads)
        resolver(nil)
    }

    @objc(flushWindow:resolver:rejecter:)
    func flushWindow(
        minutes: Double,
        resolver: @escaping (Any?) -> Void,
        rejecter: @escaping (String, String?, Error?) -> Void
    ) {
        dispatcher.flushWindow(minutes: minutes)
        resolver(nil)
    }

    @objc(shutdown:rejecter:)
    func shutdown(
        resolver: @escaping (Any?) -> Void,
        rejecter: @escaping (String, String?, Error?) -> Void
    ) {
        dispatcher.shutdown()
        resolver(nil)
    }
}

// Fallback used when no host-provided sink has been installed. Swallows
// calls so the module can be instantiated in CI builds that only verify
// the RN surface. Apps opt in to real telemetry by calling
// `Dash0MobileModule.installSink { OTelMobileCallSink() }` from their
// AppDelegate (OTelMobileCallSink lives in the app target so it can see
// the app's SwiftPM-attached OTelMobileSDK).
public final class NoopSink: BridgeCallSink {
    public init() {}
    public func start(_ config: BridgeStartConfig) {}
    public func emitLog(name: String, severity: Int, attributes: [String: Any], timeUnixNano: UInt64) {}
    public func startSpan(spanId: String, name: String, spanKind: String, attributes: [String: Any], startTimeUnixNano: UInt64) {}
    public func endSpan(spanId: String, status: String, statusMessage: String?, attributes: [String: Any], endTimeUnixNano: UInt64) {}
    public func recordMetric(name: String, instrumentType: String, value: Double, attributes: [String: Any], timeUnixNano: UInt64) {}
    public func flushWindow(minutes: Int) {}
    public func shutdown() {}
}
