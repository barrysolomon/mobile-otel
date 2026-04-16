public struct BufferConfig: Sendable {
    public let ramEvents: Int
    public let diskMb: Int
    public let retentionHours: Int

    public static let `default` = BufferConfig(ramEvents: 5000, diskMb: 50, retentionHours: 24)

    public init(ramEvents: Int, diskMb: Int, retentionHours: Int) {
        self.ramEvents = ramEvents
        self.diskMb = diskMb
        self.retentionHours = retentionHours
    }
}
