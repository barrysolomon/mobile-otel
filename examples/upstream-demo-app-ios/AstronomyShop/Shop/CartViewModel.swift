import Foundation
import OpenTelemetryApi
import SwiftUI

/// Cart state + checkout actions. Emits telemetry for add/remove/checkout
/// so each user step is observable.
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

    private let tracer: Tracer?
    private let logger: Logger?

    init(tracer: Tracer?, logger: Logger?) {
        self.tracer = tracer
        self.logger = logger
    }

    func add(_ product: Product, quantity: Int = 1) {
        if let idx = lines.firstIndex(where: { $0.product.id == product.id }) {
            lines[idx].quantity += quantity
        } else {
            lines.append(Line(product: product, quantity: quantity))
        }
        emitLog(event: "cart.add_item", attrs: [
            "product.id": .string(product.id),
            "product.name": .string(product.name),
            "cart.quantity": .int(quantity),
            "cart.item_count": .int(itemCount),
        ])
    }

    func remove(_ product: Product) {
        lines.removeAll { $0.product.id == product.id }
        emitLog(event: "cart.remove_item", attrs: [
            "product.id": .string(product.id),
            "cart.item_count": .int(itemCount),
        ])
    }

    func clear() {
        lines.removeAll()
        emitLog(event: "cart.cleared")
    }

    /// Simulated checkout — emits a parent span with the whole flow and
    /// child spans for validate/charge/confirm. Clears the cart on success.
    func checkout(onComplete: @escaping (Bool) -> Void) {
        guard let tracer = tracer else { onComplete(false); return }

        let parent = tracer.spanBuilder(spanName: "checkout")
            .setSpanKind(spanKind: .internal)
            .startSpan()
        parent.setAttribute(key: "cart.item_count", value: itemCount)
        parent.setAttribute(key: "cart.total_usd", value: total)

        Task.detached { [weak self] in
            // Validate
            let validate = tracer.spanBuilder(spanName: "checkout.validate")
                .setParent(parent).startSpan()
            try? await Task.sleep(nanoseconds: 80_000_000)
            validate.end()

            // Charge
            let charge = tracer.spanBuilder(spanName: "checkout.charge")
                .setParent(parent).startSpan()
            charge.setAttribute(key: "payment.method", value: "card")
            try? await Task.sleep(nanoseconds: 150_000_000)
            charge.end()

            // Confirm
            let confirm = tracer.spanBuilder(spanName: "checkout.confirm")
                .setParent(parent).startSpan()
            try? await Task.sleep(nanoseconds: 50_000_000)
            confirm.end()

            parent.status = .ok
            parent.end()

            await MainActor.run {
                self?.clear()
                onComplete(true)
            }
        }
    }

    private func emitLog(event: String, attrs: [String: AttributeValue] = [:]) {
        guard let logger = logger else { return }
        var a = attrs
        a["event.name"] = .string(event)
        logger.logRecordBuilder()
            .setBody(AttributeValue.string(event))
            .setSeverity(.info)
            .setAttributes(a)
            .emit()
    }
}
