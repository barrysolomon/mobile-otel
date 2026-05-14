import SwiftUI
import OTelMobileSDK
import OTelMobileCore
import OpenTelemetryApi

/// Demo screen surfacing every flavor of error/crash the iOS SDK is supposed
/// to capture. Each button below produces a distinct telemetry signal — pair
/// the names here with the rows in
/// `mobile-otel/docs/reference/TELEMETRY_SIGNALS.md` to map button → event.
///
/// Cross-platform parity: the Android `ErrorTriggersScreen.kt` and RN
/// `ErrorTriggersScreen.tsx` provide the same buttons with the same labels
/// and behaviors. See `docs/IOS_ANDROID_PARITY.md` (Error triggers row).
struct ErrorTriggersView: View {
    @EnvironmentObject var root: RootState

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Error Triggers")
                    .font(.title)
                    .bold()
                Text("""
                Each button emits a different OTel signal. Tap one, then check Dash0 \
                (filtered by service.name = otel-ios-astronomy-shop) for the resulting telemetry.
                """)
                .font(.footnote)
                .foregroundColor(.secondary)

                sectionHeader("Handled errors — process keeps running")

                triggerButton(
                    label: "Log a handled error",
                    description: "Emits app.error log record. severity=ERROR.",
                    tag: "trigger.handled_error",
                    isDanger: false
                ) {
                    emitHandledError(message: "manual button: Log a handled error", error: nil)
                }

                triggerButton(
                    label: "Catch a divide-by-zero",
                    description: "Catches Swift trap-equivalent, records via app.error.",
                    tag: "trigger.divide_by_zero_handled",
                    isDanger: false
                ) {
                    // Swift integer division by zero traps (not catchable). Use
                    // FP division which produces .infinity / .nan, then treat
                    // those as an error condition we record manually.
                    let denominator: Double = 0
                    let result = 10.0 / denominator
                    if !result.isFinite {
                        emitHandledError(
                            message: "10 / 0 = \(result) (non-finite — divide-by-zero handled)",
                            error: nil
                        )
                    }
                }

                triggerButton(
                    label: "Trigger HTTP 500",
                    description: "GET httpbin.org/status/500. Auto-instrumentation emits http.error → matches the http-error-detector policy → flushes the buffer.",
                    tag: "trigger.http_500",
                    isDanger: false
                ) {
                    Task.detached {
                        guard let url = URL(string: "https://httpbin.org/status/500") else { return }
                        let session = URLSession(configuration: .default)
                        do {
                            let (_, response) = try await session.data(from: url)
                            if let http = response as? HTTPURLResponse {
                                print("HTTP 500 trigger returned status=\(http.statusCode)")
                            }
                        } catch {
                            await emitHandledError(message: "HTTP 500 trigger failed: \(error.localizedDescription)", error: error)
                        }
                        session.finishTasksAndInvalidate()
                    }
                }

                sectionHeader("Unhandled — kills the process")

                triggerButton(
                    label: "Crash via fatal trap",
                    description: "Out-of-bounds array access raises EXC_BAD_INSTRUCTION (Swift trap). Same mechanism as -DASH0_CRASH_NOW.",
                    tag: "trigger.crash_trap",
                    isDanger: true
                ) {
                    let empty: [Int] = []
                    _ = empty[42]
                }

                triggerButton(
                    label: "Crash via SIGABRT",
                    description: "Posix abort(). Signal handler writes the crash marker; next launch emits app.crash.",
                    tag: "trigger.crash_sigabrt",
                    isDanger: true
                ) {
                    abort()
                }

                Spacer(minLength: 24)
            }
            .padding()
        }
        .navigationTitle("Errors")
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private func sectionHeader(_ text: String) -> some View {
        Divider().padding(.top, 8)
        Text(text)
            .font(.headline)
            .padding(.bottom, 4)
    }

    @ViewBuilder
    private func triggerButton(
        label: String,
        description: String,
        tag: String,
        isDanger: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                Text(label).font(.headline)
                Text(description).font(.caption).multilineTextAlignment(.leading)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .foregroundColor(.white)
            .background(isDanger ? Color(red: 0.69, green: 0.0, blue: 0.13) : Color.accentColor)
            .cornerRadius(8)
        }
        .accessibilityIdentifier(tag)
    }

    private func emitHandledError(message: String, error: Error?) {
        guard let logger = root.logger else {
            print("OTel not initialized; skipping handled-error emit")
            return
        }
        var attrs: [String: AttributeValue] = [
            "event.name": .string("app.error"),
            "exception.message": .string(message),
        ]
        if let error = error {
            attrs["exception.type"] = .string(String(describing: type(of: error)))
        }
        logger.logRecordBuilder()
            .setBody(.string("app.error"))
            .setSeverity(.error)
            .setAttributes(attrs)
            .emit()
        print("emitted app.error: \(message)")
    }
}
