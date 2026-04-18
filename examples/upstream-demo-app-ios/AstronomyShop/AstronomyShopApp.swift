import SwiftUI
import OTelMobileSDK
import OTelMobileCore
import OpenTelemetryApi

@main
struct AstronomyShopApp: App {
    @StateObject private var root = RootState()

    var body: some Scene {
        WindowGroup {
            rootView
                // Explicit root-level accessibility identifier. Without
                // this, iOS 26 simulator's XCUITest occasionally returns
                // an empty accessibility tree for SwiftUI-only apps even
                // after the UI has rendered. A top-level identifier
                // forces the accessibility graph to materialise.
                .accessibilityIdentifier("app.root")
        }
    }

    @ViewBuilder
    private var rootView: some View {
        if let cart = root.cart, !root.products.isEmpty {
            ProductListView(products: root.products)
                .environmentObject(cart)
                .environmentObject(root)
                .onAppear {
                    root.logger?.logRecordBuilder()
                        .setBody(.string("app.home_appeared"))
                        .setSeverity(.info)
                        .setAttributes(["event.name": .string("app.home_appeared")])
                        .emit()
                    root.startAutoDemoIfRequested()
                }
        } else {
            VStack(spacing: 12) {
                Image(systemName: "moon.stars").font(.system(size: 48))
                Text("Astronomy Shop").font(.largeTitle).bold()
                Text(root.status)
                    .multilineTextAlignment(.center)
                    .foregroundColor(.secondary)
                Text("products=\(root.products.count) cart=\(root.cart == nil ? "nil" : "set")")
                    .font(.caption.monospaced())
                    .foregroundColor(.secondary)
            }
            .padding()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

@MainActor
final class RootState: ObservableObject {
    @Published var products: [Product] = []
    @Published var cart: CartViewModel?
    @Published var status: String = "Starting..."
    let logger: Logger?
    let telemetry: ShopTelemetry?
    private let mobile: OTelMobile?
    private var autoDriver: AutoDemoDriver?

    init() {
        let boot = ShopBootstrap.start()
        self.mobile = boot.mobile
        self.logger = boot.mobile?.logger
        self.telemetry = ShopTelemetry(
            tracer: boot.mobile?.tracer,
            logger: boot.mobile?.logger,
            meter: boot.mobile?.meter
        )
        self.status = boot.status

        // OTelMobile.start auto-installed Network/Lifecycle/Errors/Screen
        // instrumentation based on config.autoCaptureOptions (default: all).
        if let mobile = boot.mobile {
            let catalog = ProductCatalogClient(tracer: mobile.tracer)
            self.products = catalog.loadProducts()
        } else {
            // SDK not started — still load catalog so the UI works offline.
            let catalog = ProductCatalogClient(tracer: nil)
            self.products = catalog.loadProducts()
        }
        self.cart = CartViewModel(telemetry: telemetry)
    }

    /// Called from the root view's `onAppear`. When launched with env
    /// `DASH0_AUTO_DEMO=1` (set via `SIMCTL_CHILD_DASH0_AUTO_DEMO=1` on the
    /// simctl parent shell — simctl has no `--env` flag; extra tokens become
    /// argv), run the full user journey on a loop: browse → add → checkout.
    /// Drives deterministic signal for `validate-ios-end-to-end.sh` and
    /// `run-dual-platform-demo.sh`.
    func startAutoDemoIfRequested() {
        guard autoDriver == nil else { return }
        let envSet = ProcessInfo.processInfo.environment["DASH0_AUTO_DEMO"] == "1"
        let argSet = CommandLine.arguments.contains("--auto-demo")
        guard envSet || argSet else { return }
        autoDriver = AutoDemoDriver(telemetry: telemetry, products: products)
        autoDriver?.start()
    }

    /// Emit the browse telemetry for a product view. Used by
    /// `ProductDetailView.onAppear` so user-driven browses produce the same
    /// 3-span tree + histogram as the auto-demo.
    func emitProductViewed(product: Product) {
        telemetry?.emitProductView(product: product)
    }

    deinit {
        autoDriver?.stop()
    }
}
