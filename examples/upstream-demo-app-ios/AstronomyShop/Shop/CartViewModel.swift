import Foundation
import SwiftUI

/// Cart state — the @MainActor @Published source of truth for the cart UI.
///
/// Delegates every emit (INFO/WARN logs, counter, 14-span checkout trace,
/// duration histogram) to `ShopTelemetry` so user-driven and auto-demo
/// flows produce identical telemetry in Dash0.
@MainActor
final class CartViewModel: ObservableObject {
    struct Line: Identifiable, Hashable {
        let product: Product
        var quantity: Int
        var id: String { product.id }
        var lineTotal: Double { product.priceValue * Double(quantity) }
    }

    @Published var lines: [Line] = []

    var itemCount: Int { lines.reduce(0) { $0 + $1.quantity } }
    var total: Double { lines.reduce(0.0) { $0 + $1.lineTotal } }

    private let telemetry: ShopTelemetry?

    init(telemetry: ShopTelemetry?) {
        self.telemetry = telemetry
    }

    func add(_ product: Product, quantity: Int = 1) {
        let lineQuantity: Int
        if let idx = lines.firstIndex(where: { $0.product.id == product.id }) {
            lines[idx].quantity += quantity
            lineQuantity = lines[idx].quantity
        } else {
            lines.append(Line(product: product, quantity: quantity))
            lineQuantity = quantity
        }
        telemetry?.emitCartAdd(
            product: product,
            quantityAdded: quantity,
            lineQuantity: lineQuantity,
            itemCount: itemCount
        )
    }

    func remove(_ product: Product) {
        lines.removeAll { $0.product.id == product.id }
        telemetry?.emitCartRemove(product: product, itemCount: itemCount)
    }

    func clear() {
        lines.removeAll()
        telemetry?.emitCartClear()
    }

    /// Simulated checkout. Delegates span/metric emission to ShopTelemetry
    /// so the user-driven path and auto-demo path produce identical traces.
    func checkout(onComplete: @escaping (Bool) -> Void) {
        guard let telemetry = telemetry else { onComplete(false); return }
        let snapshot = lines.map {
            ShopTelemetry.CheckoutLine(
                productId: $0.product.id,
                productName: $0.product.name,
                quantity: $0.quantity,
                lineTotal: $0.lineTotal
            )
        }
        telemetry.emitCheckout(
            lines: snapshot,
            totalUsd: total,
            itemCount: itemCount
        ) { [weak self] success in
            Task { @MainActor in
                if success { self?.clear() }
                onComplete(success)
            }
        }
    }
}
