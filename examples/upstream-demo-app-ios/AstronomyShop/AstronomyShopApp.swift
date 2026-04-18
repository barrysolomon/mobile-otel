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
                // Explicit root-level accessibility identifier so
                // XCUITest's snapshot has at least one materialised
                // identifier to anchor on. Required for the
                // AstronomyShopUITests journey driver.
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

    /// Emit the browse telemetry for a product view. Used by
    /// `ProductDetailView.onAppear` so every product-detail render
    /// produces the canonical 3-span tree + histogram.
    func emitProductViewed(product: Product) {
        telemetry?.emitProductView(product: product)
    }
}
