import Foundation

public final class InstrumentationRegistry: @unchecked Sendable {
    private let lock = NSLock()
    private var modules: [String: MobileInstrumentation] = [:]

    public init() {}

    public func register(_ module: MobileInstrumentation) {
        lock.lock(); defer { lock.unlock() }
        modules[module.id] = module
    }

    public func installAll(context: InstrumentationContext) {
        lock.lock()
        let snapshot = Array(modules.values)
        lock.unlock()
        for module in snapshot { module.install(context: context) }
    }

    public func uninstallAll() {
        lock.lock()
        let snapshot = Array(modules.values)
        lock.unlock()
        for module in snapshot { module.uninstall() }
    }

    public func module(forId id: String) -> MobileInstrumentation? {
        lock.lock(); defer { lock.unlock() }
        return modules[id]
    }

    public var allModules: [MobileInstrumentation] {
        lock.lock(); defer { lock.unlock() }
        return Array(modules.values)
    }
}
