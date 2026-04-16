import Foundation
import OpenTelemetryApi

public struct InstrumentationContext {
    public let tracer: Tracer
    public let logger: Logger
    public let meter: Meter
    public let sessionProvider: SessionProvider
    public let eventHub: TouchEventHub
    public let privacyConfig: PrivacyConfig

    public init(
        tracer: Tracer,
        logger: Logger,
        meter: Meter,
        sessionProvider: SessionProvider,
        eventHub: TouchEventHub,
        privacyConfig: PrivacyConfig
    ) {
        self.tracer = tracer
        self.logger = logger
        self.meter = meter
        self.sessionProvider = sessionProvider
        self.eventHub = eventHub
        self.privacyConfig = privacyConfig
    }
}
