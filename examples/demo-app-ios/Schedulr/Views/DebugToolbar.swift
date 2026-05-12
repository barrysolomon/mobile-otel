import SwiftUI

/// In-app developer toolbar that surfaces controls for forcing demo
/// scenarios that would otherwise require code edits. Mirrors the
/// Android demo's `DebugToolbar` (the bar with HTTP500 / Crash / ANR
/// buttons at the top of `SchedulingActivity`).
///
/// Today it ships one button — **HTTP 500** — which arms
/// `SchedulrAPI.forceNextFetchError` so the next Appointments refresh
/// hits a real but non-existent path on the demo backend, producing a
/// real 404 through `URLSession` → `OTelURLProtocol`. This is the only
/// way to exercise the SDK's `http.error` log emission path
/// end-to-end on iOS (matches Android's `forceNextFetchError` pattern).
///
/// Always visible in the demo; we don't ship Schedulr to App Store.
struct DebugToolbar: View {
    @EnvironmentObject private var api: SchedulrAPI
    /// Callback fired after arming the flag so the host view can trigger
    /// the refresh (the `Task { await reload() }` lives there).
    let onTriggerRefresh: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Text("DEBUG")
                .font(.caption2.weight(.bold))
                .foregroundColor(.white)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(Color.orange)
                .cornerRadius(4)

            Button(action: triggerHttp500) {
                Label("HTTP 500", systemImage: "exclamationmark.triangle")
                    .font(.caption.weight(.semibold))
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.small)
            .tint(.red)
            .accessibilityIdentifier("debug.http500")

            Spacer()

            if api.forceNextFetchError {
                Text("armed → next reload fails")
                    .font(.caption2)
                    .foregroundColor(.orange)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Color(.systemBackground).opacity(0.95))
        .overlay(
            Rectangle()
                .frame(height: 1)
                .foregroundColor(Color.gray.opacity(0.3)),
            alignment: .bottom
        )
    }

    private func triggerHttp500() {
        api.forceNextFetchError = true
        onTriggerRefresh()
    }
}
