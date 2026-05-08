import Foundation
import OpenTelemetryApi

extension PredictiveExportPolicy {
    /// Test factory that creates a policy with a no-op logger. Lives in the
    /// SDK module so test files don't need to import OpenTelemetryApi directly.
    static func makeForTesting(
        config: Config = .default,
        flushWindow: @escaping FlushWindowClosure
    ) -> PredictiveExportPolicy {
        let logger = DefaultLoggerProvider.instance
            .loggerBuilder(instrumentationScopeName: "test")
            .build()
        return PredictiveExportPolicy(
            config: config,
            logger: logger,
            flushWindow: flushWindow
        )
    }
}
