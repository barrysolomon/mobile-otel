import SwiftUI

struct ContentView: View {
    @EnvironmentObject var model: DemoModel

    var body: some View {
        VStack(spacing: 24) {
            Text("Dash0 iOS Demo")
                .font(.largeTitle).bold()

            Text(model.status)
                .multilineTextAlignment(.center)
                .padding()
                .background(Color.gray.opacity(0.15))
                .cornerRadius(8)

            VStack(spacing: 6) {
                Text("Events emitted: \(model.eventsEmitted)")
                    .font(.title2)
                    .bold()
                Text("Dataset: \(model.datasetName)")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            .padding()

            HStack(spacing: 16) {
                Button("Emit Event") {
                    model.emitManual()
                }
                .buttonStyle(.borderedProminent)

                Button("Force Flush") {
                    model.flushManual()
                }
                .buttonStyle(.bordered)
            }

            Spacer()
        }
        .padding()
    }
}
