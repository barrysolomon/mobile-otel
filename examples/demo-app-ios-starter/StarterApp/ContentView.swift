import SwiftUI

struct ContentView: View {
    @EnvironmentObject var model: DemoModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header
                statusBanner
                resourceSection
                logsSection
                tracesSection
                metricsSection
                networkSection
                errorsSection
                deviceStatsSection
                countersSection
            }
            .padding()
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Dash0 iOS Demo")
                .font(.largeTitle).bold()
            Text("Dataset: \(model.datasetName)")
                .font(.caption)
                .foregroundColor(.secondary)
        }
    }

    private var statusBanner: some View {
        Text(model.status)
            .font(.callout)
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.gray.opacity(0.12))
            .cornerRadius(8)
    }

    private var resourceSection: some View {
        DisclosureGroup("Resource attributes (\(model.resourceAttributes.count))") {
            VStack(alignment: .leading, spacing: 4) {
                ForEach(model.resourceAttributes, id: \.key) { attr in
                    HStack(alignment: .top) {
                        Text(attr.key)
                            .font(.system(.caption, design: .monospaced))
                            .foregroundColor(.secondary)
                            .frame(width: 170, alignment: .leading)
                        Text(attr.value)
                            .font(.system(.caption, design: .monospaced))
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                }
            }
            .padding(.top, 4)
        }
        .font(.headline)
    }

    private var logsSection: some View {
        SectionCard(title: "Logs") {
            HStack(spacing: 10) {
                ActionButton("Info", color: .blue) { model.emitInfoLog() }
                ActionButton("Warn", color: .orange) { model.emitWarnLog() }
                ActionButton("Error", color: .red) { model.emitErrorLog() }
            }
        }
    }

    private var tracesSection: some View {
        SectionCard(title: "Traces") {
            HStack(spacing: 10) {
                ActionButton("Simple", color: .blue) { model.emitSimpleSpan() }
                ActionButton("Nested", color: .blue) { model.emitNestedSpan() }
                ActionButton("Error", color: .red) { model.emitErrorSpan() }
            }
        }
    }

    private var metricsSection: some View {
        SectionCard(title: "Metrics") {
            HStack(spacing: 10) {
                ActionButton("Inc Counter", color: .blue) { model.incrementCounter() }
                ActionButton("Histogram", color: .blue) { model.recordHistogram() }
            }
        }
    }

    private var networkSection: some View {
        SectionCard(title: "Network (auto-instrumented)") {
            HStack(spacing: 10) {
                ActionButton("GET /json", color: .green) { model.fetchHttpbinJson() }
                ActionButton("GET /status/500", color: .red) { model.fetchHttpbin5xx() }
            }
        }
    }

    private var errorsSection: some View {
        SectionCard(title: "Errors (auto-captured)") {
            HStack(spacing: 10) {
                ActionButton("Recorded Error", color: .orange) { model.recordCaughtError() }
                ActionButton("Crash Now", color: .red) { model.crashNow() }
            }
        }
    }

    private var deviceStatsSection: some View {
        SectionCard(title: "Device Stats") {
            HStack(spacing: 12) {
                Button(action: { model.toggleDeviceStats() }) {
                    HStack {
                        Image(systemName: model.deviceStatsOn ? "circle.fill" : "circle")
                            .foregroundColor(model.deviceStatsOn ? .green : .gray)
                        Text(model.deviceStatsOn ? "ON - collecting every 5s" : "OFF - tap to start")
                            .font(.subheadline)
                    }
                    .padding(.vertical, 8)
                    .padding(.horizontal, 12)
                    .background(Color.gray.opacity(0.1))
                    .cornerRadius(8)
                }
                .buttonStyle(.plain)
                Spacer()
            }
        }
    }

    private var countersSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Divider()
            HStack(spacing: 24) {
                Stat(label: "logs", value: model.logsEmitted)
                Stat(label: "spans", value: model.spansEmitted)
                Stat(label: "metrics", value: model.metricsEmitted)
                Stat(label: "network", value: model.networkCalls)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Button("Force Flush") { model.forceFlush() }
                .buttonStyle(.borderedProminent)
                .tint(.purple)

            if !model.lastFlushResult.isEmpty {
                Text("Last flush: \(model.lastFlushResult)")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
    }
}

private struct SectionCard<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.headline)
            content()
        }
    }
}

private struct ActionButton: View {
    let label: String
    let color: Color
    let action: () -> Void

    init(_ label: String, color: Color, action: @escaping () -> Void) {
        self.label = label
        self.color = color
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(.subheadline)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(color.opacity(0.15))
                .foregroundColor(color)
                .cornerRadius(8)
        }
        .buttonStyle(.plain)
    }
}

private struct Stat: View {
    let label: String
    let value: Int

    var body: some View {
        VStack(alignment: .leading) {
            Text("\(value)")
                .font(.title2).bold()
            Text(label)
                .font(.caption)
                .foregroundColor(.secondary)
        }
    }
}
