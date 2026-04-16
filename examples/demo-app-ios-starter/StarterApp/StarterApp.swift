import SwiftUI

@main
struct StarterApp: App {
    @StateObject private var model = DemoModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(model)
        }
    }
}
