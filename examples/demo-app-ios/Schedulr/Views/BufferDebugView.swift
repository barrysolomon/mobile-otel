import SwiftUI
import OTelMobileSDK
import OTelMobileCore

/// In-app inspector for the dual-tier buffer + export pipeline. Reads
/// disk-buffer stats off the retained `DiskLogBuffer` and subscribes to
/// `ExportStatusManager` so every retry / success / auth-error transition
/// surfaces live. Mirrors what Android's `RingBufferActivity.kt` shows,
/// trimmed to the bits the iOS SDK currently exposes publicly.
struct BufferDebugView: View {
    @EnvironmentObject private var bootstrap: SchedulrBootstrap
    @StateObject private var statusBridge = ExportStatusBridge()

    @State private var diskRows: Int?
    @State private var diskBytes: Int?
    @State private var lastAction: String?

    var body: some View {
        NavigationStack {
            List {
                Section("SDK") {
                    LabeledContent("Bootstrap", value: bootstrap.mobile == nil ? "pending / failed" : "ready")
                    Button {
                        refreshDiskStats()
                    } label: {
                        Label("Refresh stats", systemImage: "arrow.clockwise")
                    }
                    .accessibilityIdentifier("buffer.refresh")
                }

                Section("Disk Buffer") {
                    if let buffer = bootstrap.diskBuffer {
                        LabeledContent("Status", value: "enabled")
                        LabeledContent("Rows", value: diskRows.map(String.init) ?? "—")
                        LabeledContent("Used", value: formattedBytes(diskBytes))
                        LabeledContent("Max", value: formattedBytes(buffer.maxTotalBytes))
                        LabeledContent("TTL", value: "\(Int(buffer.retentionSeconds / 3600)) h")
                        LabeledContent("Path") {
                            Text(buffer.dbPath.lastPathComponent)
                                .font(.caption.monospaced())
                                .foregroundColor(.secondary)
                        }
                    } else {
                        Text("Disk buffer disabled. The SDK is running RAM-only — events won't survive process death.")
                            .font(.footnote)
                            .foregroundColor(.secondary)
                    }
                }

                Section("Last Export Status") {
                    if let status = statusBridge.lastStatus, let when = statusBridge.lastUpdate {
                        LabeledContent("Result", value: statusDescription(status))
                        LabeledContent("When", value: timeFormatter.string(from: when))
                    } else {
                        Text("No exports observed yet")
                            .font(.footnote)
                            .foregroundColor(.secondary)
                    }
                }

                if let mobile = bootstrap.mobile {
                    Section("Actions") {
                        Button("Force flush") {
                            let result = mobile.forceFlush()
                            lastAction = "forceFlush → \(describe(result))"
                            refreshDiskStats()
                        }
                        Button("Flush last 5 min") {
                            Task {
                                let result = await mobile.flushWindow(minutes: 5)
                                lastAction = "flushWindow(5m) → \(describe(result))"
                                refreshDiskStats()
                            }
                        }
                        if bootstrap.diskBuffer != nil {
                            Button("Prune by TTL") {
                                Task {
                                    if let buf = bootstrap.diskBuffer {
                                        await buf.pruneByTTL()
                                    }
                                    lastAction = "pruneByTTL ✓"
                                    refreshDiskStats()
                                }
                            }
                        }
                        if let action = lastAction {
                            Text(action)
                                .font(.footnote)
                                .foregroundColor(.secondary)
                        }
                    }

                    Section("Crash Recovery Demo") {
                        Text("Tap a few times around the app to generate events, then hit the button. The Swift runtime trap (SIGILL/SIGTRAP) is caught by the SDK's signal handler, the crash marker is set, and the disk buffer survives termination. Relaunch — the next bootstrap drains buffered events to Dash0.")
                            .font(.footnote)
                            .foregroundColor(.secondary)
                        Button(role: .destructive) {
                            // Swift runtime trap — exercises the SDK's
                            // POSIX signal handler path. fatalError maps
                            // to SIGILL/SIGTRAP, so ErrorsInstrumentation's
                            // signal handler fires, the crash marker is
                            // written, and the disk buffer is left intact
                            // for next-launch recovery.
                            fatalError("Schedulr: crash recovery demo")
                        } label: {
                            Text("Crash app now (fatalError)")
                                .frame(maxWidth: .infinity)
                        }
                        .accessibilityIdentifier("buffer.crash")
                    }
                }
            }
            .navigationTitle("Buffer")
            .navigationBarTitleDisplayMode(.inline)
            .refreshable {
                await refreshDiskStatsAsync()
            }
            .onAppear {
                ExportStatusManager.shared.addListener(statusBridge)
                refreshDiskStats()
            }
            .onDisappear {
                ExportStatusManager.shared.removeListener(statusBridge)
            }
        }
    }

    private func refreshDiskStatsAsync() async {
        guard let buffer = bootstrap.diskBuffer else {
            diskRows = nil
            diskBytes = nil
            return
        }
        let rows = await buffer.rowCount()
        let bytes = await buffer.totalSizeBytes()
        diskRows = rows
        diskBytes = bytes
    }

    private func refreshDiskStats() {
        guard let buffer = bootstrap.diskBuffer else {
            diskRows = nil
            diskBytes = nil
            return
        }
        Task {
            let rows = await buffer.rowCount()
            let bytes = await buffer.totalSizeBytes()
            await MainActor.run {
                self.diskRows = rows
                self.diskBytes = bytes
            }
        }
    }

    private func statusDescription(_ status: ExportStatus) -> String {
        switch status {
        case .success(let n):
            return "success (\(n) events)"
        case .failed(let reason, let n, let attempt):
            return "failed #\(attempt) — \(n) lost: \(reason)"
        case .authError(let reason, let n):
            return "auth error (\(n) lost): \(reason)"
        case .retrying(let attempt, let max, let delayMs):
            return "retrying \(attempt)/\(max) in \(delayMs)ms"
        }
    }

    private func describe(_ result: BufferExportResult) -> String {
        switch result {
        case .success: return "success"
        case .failure(let reason): return "failure: \(reason)"
        }
    }

    private func formattedBytes(_ bytes: Int?) -> String {
        guard let bytes = bytes else { return "—" }
        let f = ByteCountFormatter()
        return f.string(fromByteCount: Int64(bytes))
    }

    private var timeFormatter: DateFormatter {
        let f = DateFormatter()
        f.dateStyle = .none
        f.timeStyle = .medium
        return f
    }
}

/// Bridges `ExportStatusManager` (callback-based, any thread) into a
/// SwiftUI-friendly `ObservableObject` so the debug view re-renders on
/// every status transition. Held by the view as `@StateObject`.
final class ExportStatusBridge: ObservableObject, ExportStatusListener {
    @Published var lastStatus: ExportStatus?
    @Published var lastUpdate: Date?

    func onExportStatus(_ status: ExportStatus) {
        // Notify is called from the exporter's thread; hop to main before
        // mutating @Published to avoid SwiftUI thread-safety warnings.
        DispatchQueue.main.async {
            self.lastStatus = status
            self.lastUpdate = Date()
        }
    }
}
