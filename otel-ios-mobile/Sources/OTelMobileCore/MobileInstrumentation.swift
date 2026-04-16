import Foundation

public protocol MobileInstrumentation: AnyObject {
    var id: String { get }
    var isAutoCapture: Bool { get }
    func install(context: InstrumentationContext)
    func uninstall()
}
