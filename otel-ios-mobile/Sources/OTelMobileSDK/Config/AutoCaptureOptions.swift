public struct AutoCaptureOptions: OptionSet, Sendable {
    public let rawValue: Int
    public init(rawValue: Int) { self.rawValue = rawValue }

    public static let tap = AutoCaptureOptions(rawValue: 1 << 0)
    public static let scroll = AutoCaptureOptions(rawValue: 1 << 1)
    public static let lifecycle = AutoCaptureOptions(rawValue: 1 << 2)
    public static let screen = AutoCaptureOptions(rawValue: 1 << 3)
    public static let network = AutoCaptureOptions(rawValue: 1 << 4)
    public static let errors = AutoCaptureOptions(rawValue: 1 << 5)
    public static let freeze = AutoCaptureOptions(rawValue: 1 << 6)
    public static let vitals = AutoCaptureOptions(rawValue: 1 << 7)
    public static let textInput = AutoCaptureOptions(rawValue: 1 << 8)
    public static let screenshot = AutoCaptureOptions(rawValue: 1 << 9)
    public static let wireframe = AutoCaptureOptions(rawValue: 1 << 10)
    /// Start the `DeviceStatsCollector` gauge loop (memory / battery / thermal
    /// / storage) at SDK init time. Default cadence 15 s — see
    /// `MobileConfig.deviceStatsIntervalSeconds`.
    public static let deviceStats = AutoCaptureOptions(rawValue: 1 << 11)

    public static let all: AutoCaptureOptions = [.tap, .scroll, .lifecycle, .screen, .network, .errors, .freeze, .vitals, .textInput, .screenshot, .wireframe, .deviceStats]
    public static let none: AutoCaptureOptions = []

    /// The default auto-capture set: everything in `.all` EXCEPT the
    /// privacy-sensitive `.screenshot` and `.wireframe` modules. Per the
    /// screenshot/wireframe privacy design, those capture screen pixels /
    /// view-hierarchy content and MUST be opt-in — customers who want them
    /// pass `.all` or add the options explicitly. This is the value
    /// `MobileConfig` uses when the caller doesn't specify `autoCaptureOptions`.
    public static let `default`: AutoCaptureOptions = all.subtracting([.screenshot, .wireframe])
}
