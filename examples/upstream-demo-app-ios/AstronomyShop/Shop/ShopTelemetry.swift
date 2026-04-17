import Foundation
import OpenTelemetryApi
import OpenTelemetrySdk

/// Single source of truth for every log, span, and metric the demo emits.
///
/// Three different call sites exercise the shop:
///   - `CartViewModel` for user-driven cart actions
///   - `RootState` / `ProductDetailView` for product-view on appear
///   - `AutoDemoDriver` for the `DASH0_AUTO_DEMO=1` loop
///
/// Without this helper they would each reproduce the same 14-span checkout
/// tree, 3-span browse tree, metric instrument setup, and log schema —
/// guaranteed drift. Routing everything through `ShopTelemetry` means
/// renaming a span or adding an attribute happens in exactly one place.
///
/// The class is `@unchecked Sendable` / not actor-isolated so callers on any
/// thread/queue (MainActor UI code, the auto-demo's background
/// `DispatchSourceTimer`) can share a single instrument graph — OTel-Swift's
/// `LongCounter` / `DoubleHistogram` / `Tracer` / `Logger` are documented as
/// thread-safe.
final class ShopTelemetry: @unchecked Sendable {
    let tracer: Tracer
    let logger: Logger
    private var itemsAddedCounter: LongCounter
    private var checkoutDurationHistogram: DoubleHistogram
    private var viewProductHistogram: DoubleHistogram

    /// Build from an `OTelMobile` instance. Returns nil if the SDK isn't
    /// started or any required handle is missing — the UI still renders in
    /// that case, it just emits nothing.
    init?(tracer: Tracer?, logger: Logger?, meter: MeterSdk?) {
        guard let tracer = tracer, let logger = logger, let meter = meter else {
            return nil
        }
        self.tracer = tracer
        self.logger = logger
        self.itemsAddedCounter = meter.counterBuilder(name: "shop.cart.items_added").build()
        self.checkoutDurationHistogram = meter.histogramBuilder(name: "shop.checkout.duration_ms").build()
        self.viewProductHistogram = meter.histogramBuilder(name: "shop.view_product.load_ms").build()
    }

    // MARK: - Product view

    /// 3-span tree + INFO log + histogram sample for a single product view.
    func emitProductView(product: Product, iteration: Int = 0) {
        let startNanos = DispatchTime.now().uptimeNanoseconds

        logger.logRecordBuilder()
            .setBody(.string("shop.view_product"))
            .setSeverity(.info)
            .setAttributes([
                "event.name": .string("shop.view_product"),
                "product.id": .string(product.id),
                "product.name": .string(product.name),
            ])
            .emit()

        let parent = tracer.spanBuilder(spanName: "shop.view_product")
            .setSpanKind(spanKind: .internal).startSpan()
        parent.setAttribute(key: "product.id", value: product.id)
        parent.setAttribute(key: "product.name", value: product.name)
        parent.setAttribute(key: "auto_demo.iteration", value: iteration)

        let reviews = tracer.spanBuilder(spanName: "shop.load_reviews")
            .setParent(parent).startSpan()
        reviews.setAttribute(key: "product.id", value: product.id)
        reviews.setAttribute(key: "reviews.count", value: Int.random(in: 3...42))
        reviews.status = .ok
        reviews.end()

        let recs = tracer.spanBuilder(spanName: "shop.load_recommendations")
            .setParent(parent).startSpan()
        recs.setAttribute(key: "recommendations.count", value: 4)
        recs.status = .ok
        recs.end()

        parent.status = .ok
        parent.end()

        let elapsedMs = Double(DispatchTime.now().uptimeNanoseconds - startNanos) / 1_000_000.0
        viewProductHistogram.record(
            value: elapsedMs,
            attributes: ["product.id": .string(product.id)]
        )
    }

    // MARK: - Cart

    /// Counter + INFO log (+ WARN log when `lineQuantity >= 5`) for a single
    /// cart-add action. Caller is responsible for updating cart state.
    ///
    /// - Parameters:
    ///   - quantityAdded: how many units this add operation added (counter delta)
    ///   - lineQuantity: cumulative units for the product in the cart after
    ///     the add — used to trigger the "large quantity" WARN log
    ///   - itemCount: total cart size after the add
    func emitCartAdd(
        product: Product,
        quantityAdded: Int,
        lineQuantity: Int,
        itemCount: Int
    ) {
        itemsAddedCounter.add(
            value: quantityAdded,
            attributes: [
                "product.id": .string(product.id),
                "product.category": .string(product.categories.first ?? "uncategorized"),
            ]
        )
        emitLog(event: "cart.add_item", attrs: [
            "product.id": .string(product.id),
            "product.name": .string(product.name),
            "cart.quantity": .int(quantityAdded),
            "cart.item_count": .int(itemCount),
        ])
        // Demo a WARN log path: large cumulative line quantity surfaces a
        // natural warn without needing a dedicated "press this to see a warn
        // log" button.
        if lineQuantity >= 5 {
            emitLog(event: "cart.large_quantity_warning", severity: .warn, attrs: [
                "product.id": .string(product.id),
                "cart.quantity": .int(lineQuantity),
            ])
        }
    }

    func emitCartRemove(product: Product, itemCount: Int) {
        emitLog(event: "cart.remove_item", attrs: [
            "product.id": .string(product.id),
            "cart.item_count": .int(itemCount),
        ])
    }

    func emitCartClear() {
        emitLog(event: "cart.cleared")
    }

    // MARK: - Checkout

    struct CheckoutLine {
        let productId: String
        let productName: String
        let quantity: Int
        let lineTotal: Double
    }

    /// 14-span deep nested checkout trace + histogram sample.
    ///
    /// Runs span emission on a detached Task so sleeps between spans don't
    /// block the caller's queue. The completion handler fires on the main
    /// queue so UI state updates are safe.
    func emitCheckout(
        lines: [CheckoutLine],
        totalUsd: Double,
        itemCount: Int,
        onComplete: @escaping @Sendable (Bool) -> Void
    ) {
        let startedMs = Date().timeIntervalSince1970 * 1000
        let parent = tracer.spanBuilder(spanName: "checkout")
            .setSpanKind(spanKind: .internal).startSpan()
        parent.setAttribute(key: "cart.item_count", value: itemCount)
        parent.setAttribute(key: "cart.line_count", value: lines.count)
        parent.setAttribute(key: "cart.total_usd", value: totalUsd)

        let tracer = self.tracer
        let histogram = self.checkoutDurationHistogram
        Task.detached {
            await Self.runCheckoutTrace(
                tracer: tracer, parent: parent, lines: lines, totalUsd: totalUsd
            )
            let durationMs = Date().timeIntervalSince1970 * 1000 - startedMs
            var histo = histogram
            histo.record(
                value: durationMs,
                attributes: [
                    "checkout.item_count": .int(itemCount),
                    "checkout.line_count": .int(lines.count),
                ]
            )
            DispatchQueue.main.async { onComplete(true) }
        }
    }

    // MARK: - Internal helpers

    private func emitLog(
        event: String,
        severity: Severity = .info,
        attrs: [String: AttributeValue] = [:]
    ) {
        var a = attrs
        a["event.name"] = .string(event)
        logger.logRecordBuilder()
            .setBody(.string(event))
            .setSeverity(severity)
            .setAttributes(a)
            .emit()
    }

    /// Shared nested-span emission. Static + nonisolated so sleeps between
    /// spans reflect real work, not main-thread scheduling.
    private static func runCheckoutTrace(
        tracer: Tracer,
        parent: Span,
        lines: [CheckoutLine],
        totalUsd: Double
    ) async {
        // 1. Validate cart
        let validate = tracer.spanBuilder(spanName: "checkout.validate_cart")
            .setParent(parent).startSpan()
        validate.setAttribute(key: "cart.line_count", value: lines.count)
        try? await Task.sleep(nanoseconds: 40_000_000)
        validate.status = .ok; validate.end()

        // 2. Inventory check: parent + per-line child spans (cap at 4 for trace size)
        let inventory = tracer.spanBuilder(spanName: "checkout.inventory_check")
            .setParent(parent).startSpan()
        inventory.setAttribute(key: "inventory.line_count", value: lines.count)
        for line in lines.prefix(4) {
            let item = tracer.spanBuilder(spanName: "inventory.check_item")
                .setParent(inventory).startSpan()
            item.setAttribute(key: "product.id", value: line.productId)
            item.setAttribute(key: "cart.quantity", value: line.quantity)
            try? await Task.sleep(nanoseconds: 12_000_000)
            item.setAttribute(key: "inventory.in_stock", value: true)
            item.status = .ok; item.end()
        }
        inventory.status = .ok; inventory.end()

        // 3. Calculate totals: parent + 3 children (subtotal, tax, shipping)
        let totals = tracer.spanBuilder(spanName: "checkout.calculate_totals")
            .setParent(parent).startSpan()
        let subtotal = tracer.spanBuilder(spanName: "totals.subtotal")
            .setParent(totals).startSpan()
        subtotal.setAttribute(key: "totals.subtotal_usd", value: totalUsd)
        try? await Task.sleep(nanoseconds: 10_000_000)
        subtotal.status = .ok; subtotal.end()

        let tax = tracer.spanBuilder(spanName: "totals.tax")
            .setParent(totals).startSpan()
        let taxUsd = totalUsd * 0.0825
        tax.setAttribute(key: "totals.tax_usd", value: taxUsd)
        try? await Task.sleep(nanoseconds: 10_000_000)
        tax.status = .ok; tax.end()

        let shipping = tracer.spanBuilder(spanName: "totals.shipping")
            .setParent(totals).startSpan()
        let shippingUsd: Double = totalUsd > 50 ? 0 : 5.99
        shipping.setAttribute(key: "totals.shipping_usd", value: shippingUsd)
        shipping.setAttribute(key: "totals.free_shipping", value: totalUsd > 50)
        try? await Task.sleep(nanoseconds: 10_000_000)
        shipping.status = .ok; shipping.end()

        totals.setAttribute(key: "totals.grand_total_usd", value: totalUsd + taxUsd + shippingUsd)
        totals.status = .ok; totals.end()

        // 4. Charge: parent + validate-card + authorize
        let charge = tracer.spanBuilder(spanName: "checkout.charge")
            .setParent(parent).startSpan()
        charge.setAttribute(key: "payment.method", value: "card")
        let validateCard = tracer.spanBuilder(spanName: "payment.validate_card")
            .setParent(charge).startSpan()
        try? await Task.sleep(nanoseconds: 40_000_000)
        validateCard.status = .ok; validateCard.end()
        let authorize = tracer.spanBuilder(spanName: "payment.authorize")
            .setParent(charge).startSpan()
        authorize.setAttribute(key: "payment.amount_usd", value: totalUsd + taxUsd + shippingUsd)
        try? await Task.sleep(nanoseconds: 120_000_000)
        authorize.status = .ok; authorize.end()
        charge.status = .ok; charge.end()

        // 5. Send confirmation (email render + send)
        let confirm = tracer.spanBuilder(spanName: "checkout.send_confirmation")
            .setParent(parent).startSpan()
        let render = tracer.spanBuilder(spanName: "email.render")
            .setParent(confirm).startSpan()
        try? await Task.sleep(nanoseconds: 20_000_000)
        render.status = .ok; render.end()
        let send = tracer.spanBuilder(spanName: "email.send")
            .setParent(confirm).startSpan()
        send.setAttribute(key: "email.provider", value: "demo-mailer")
        try? await Task.sleep(nanoseconds: 30_000_000)
        send.status = .ok; send.end()
        confirm.status = .ok; confirm.end()

        // 6. Analytics report
        let analytics = tracer.spanBuilder(spanName: "checkout.analytics.report")
            .setParent(parent).startSpan()
        analytics.setAttribute(key: "analytics.provider", value: "dash0")
        try? await Task.sleep(nanoseconds: 15_000_000)
        analytics.status = .ok; analytics.end()

        parent.status = .ok; parent.end()
    }
}
