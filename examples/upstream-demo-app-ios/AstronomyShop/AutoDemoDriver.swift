import Foundation

/// Background auto-demo driver.
///
/// A non-isolated class so a `DispatchSourceTimer` on a serial utility queue
/// can tick without hopping through MainActor. All emits are delegated to
/// `ShopTelemetry` so user-driven flows and this auto-demo loop produce
/// identical shapes in Dash0.
///
/// Task-based drivers (`Task { [weak self] in ... await runLoop() }`) from
/// the SwiftUI-hosted `@MainActor` `RootState` silently failed to execute
/// their closure in practice — likely actor-isolation starvation during
/// launch. `DispatchSourceTimer` on a dedicated serial queue sidesteps that
/// entirely: the fire is OS-scheduled and independent of any actor.
final class AutoDemoDriver: @unchecked Sendable {
    private let telemetry: ShopTelemetry
    private let products: [Product]
    private let queue: DispatchQueue
    private var timer: DispatchSourceTimer?
    /// Modified only inside `tick()`; `tick()` is only ever called by the
    /// timer which fires serially on `queue`. Must not be read or written
    /// from anywhere else — the class offers no synchronized accessor by
    /// design.
    private var step: Int = 0

    init?(telemetry: ShopTelemetry?, products: [Product]) {
        guard let telemetry = telemetry, !products.isEmpty else { return nil }
        self.telemetry = telemetry
        self.products = products
        self.queue = DispatchQueue(label: "io.dash0.demo.auto-driver", qos: .utility)
    }

    func start() {
        guard timer == nil else { return }
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + 0.5, repeating: 0.8)
        t.setEventHandler { [weak self] in self?.tick() }
        t.resume()
        timer = t
    }

    func stop() {
        timer?.cancel()
        timer = nil
    }

    /// One step of the auto-demo cycle. 5 phases per cycle:
    ///   0,1,2: view product + add to cart (spans + counter + logs)
    ///   3:     checkout (12-span trace + histogram sample)
    ///   4:     idle gap so cadence doesn't hammer the sim on tight cycles
    private func tick() {
        let current = step
        step += 1
        let phase = current % 5
        switch phase {
        case 0, 1, 2:
            let pick = products[(current / 5 + phase) % products.count]
            telemetry.emitProductView(product: pick, iteration: current / 5)
            let qty = phase == 2 ? 5 : 1  // phase 2 triggers the WARN path
            telemetry.emitCartAdd(
                product: pick,
                quantityAdded: qty,
                lineQuantity: qty,
                itemCount: qty
            )
        case 3:
            emitSyntheticCheckout(iteration: current / 5)
        default:
            break
        }
    }

    /// Build a synthetic 3-5 line cart from the catalog and emit the full
    /// 14-span checkout trace through the shared ShopTelemetry path.
    private func emitSyntheticCheckout(iteration: Int) {
        let lineCount = 3 + (iteration % 3) // 3..5
        let lines: [ShopTelemetry.CheckoutLine] = (0..<lineCount).map { i in
            let product = products[(iteration * 7 + i) % products.count]
            let quantity = 1 + (i % 3)
            return ShopTelemetry.CheckoutLine(
                productId: product.id,
                productName: product.name,
                quantity: quantity,
                lineTotal: product.priceValue * Double(quantity)
            )
        }
        let total = lines.reduce(0.0) { $0 + $1.lineTotal }
        let itemCount = lines.reduce(0) { $0 + $1.quantity }
        telemetry.emitCheckout(
            lines: lines,
            totalUsd: total,
            itemCount: itemCount,
            onComplete: { _ in }
        )
    }
}
