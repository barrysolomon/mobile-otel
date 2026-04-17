/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.demo.shop

import io.opentelemetry.android.demo.OtelDemoApplication
import io.opentelemetry.android.demo.shop.model.Product
import io.opentelemetry.api.common.AttributeKey.doubleKey
import io.opentelemetry.api.common.AttributeKey.longKey
import io.opentelemetry.api.common.AttributeKey.stringKey
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Single source of truth for every shop log, span, and metric this app emits.
 * Mirrors [examples/upstream-demo-app-ios/AstronomyShop/Shop/ShopTelemetry.swift]
 * on iOS — the contract is documented in docs/design/shop-telemetry-contract.md.
 *
 * Any drift between this file and its iOS twin is a cross-platform parity
 * bug and must be fixed in the same commit that introduces it.
 *
 * The class is a plain Kotlin object so any call site (UI composables,
 * view models, the auto-demo driver) can share the same instrument
 * handles. The OTel tracer / logger / meter are fetched lazily from
 * `OtelDemoApplication.openTelemetry` — callsites that fire before SDK
 * init get a silent no-op, same as the iOS side when `OTelMobile.start`
 * hasn't completed yet.
 */
object ShopTelemetry {
    private const val INSTRUMENTATION_SCOPE = "io.dash0.demo.shop"

    private val tracer: Tracer?
        get() = OtelDemoApplication.openTelemetry?.getTracer(INSTRUMENTATION_SCOPE)

    private val logger by lazy {
        OtelDemoApplication.openTelemetry?.logsBridge?.get(INSTRUMENTATION_SCOPE)
    }

    private val meter by lazy {
        OtelDemoApplication.openTelemetry?.getMeter(INSTRUMENTATION_SCOPE)
    }

    private val itemsAddedCounter: LongCounter? by lazy {
        meter?.counterBuilder("shop.cart.items_added")?.build()
    }

    private val checkoutDurationHistogram: DoubleHistogram? by lazy {
        meter?.histogramBuilder("shop.checkout.duration_ms")?.build()
    }

    private val viewProductHistogram: DoubleHistogram? by lazy {
        meter?.histogramBuilder("shop.view_product.load_ms")?.build()
    }

    // ------------------------------------------------------------------
    // Logs
    // ------------------------------------------------------------------

    fun emitAppHomeAppeared() {
        emitLog("app.home_appeared", Severity.INFO)
    }

    fun emitProductView(product: Product, iteration: Int = 0) {
        val startNanos = System.nanoTime()

        emitLog(
            "shop.view_product", Severity.INFO,
            attrs = mapOf(
                "product.id" to product.id,
                "product.name" to product.name
            )
        )

        val tr = tracer
        if (tr != null) {
            val parent = tr.spanBuilder("shop.view_product")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("product.id", product.id)
                .setAttribute("product.name", product.name)
                .setAttribute("auto_demo.iteration", iteration.toLong())
                .startSpan()
            val parentCtx = Context.current().with(parent)

            tr.spanBuilder("shop.load_reviews")
                .setParent(parentCtx)
                .setAttribute("product.id", product.id)
                .setAttribute("reviews.count", (3L + (iteration * 7 % 40)))
                .startSpan()
                .apply {
                    setStatus(StatusCode.OK)
                    end()
                }

            tr.spanBuilder("shop.load_recommendations")
                .setParent(parentCtx)
                .setAttribute("recommendations.count", 4L)
                .startSpan()
                .apply {
                    setStatus(StatusCode.OK)
                    end()
                }

            parent.setStatus(StatusCode.OK)
            parent.end()
        }

        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000.0
        viewProductHistogram?.record(
            elapsedMs,
            io.opentelemetry.api.common.Attributes.of(stringKey("product.id"), product.id)
        )
    }

    fun emitCartAdd(product: Product, quantityAdded: Int, lineQuantity: Int, itemCount: Int) {
        itemsAddedCounter?.add(
            quantityAdded.toLong(),
            io.opentelemetry.api.common.Attributes.of(
                stringKey("product.id"), product.id,
                stringKey("product.category"), product.categories.firstOrNull() ?: "uncategorized"
            )
        )
        emitLog(
            "cart.add_item", Severity.INFO,
            attrs = mapOf(
                "product.id" to product.id,
                "product.name" to product.name,
                "cart.quantity" to quantityAdded.toLong(),
                "cart.item_count" to itemCount.toLong()
            )
        )
        if (lineQuantity >= 5) {
            emitLog(
                "cart.large_quantity_warning", Severity.WARN,
                attrs = mapOf(
                    "product.id" to product.id,
                    "cart.quantity" to lineQuantity.toLong()
                )
            )
        }
    }

    fun emitCartRemove(product: Product, itemCount: Int) {
        emitLog(
            "cart.remove_item", Severity.INFO,
            attrs = mapOf(
                "product.id" to product.id,
                "cart.item_count" to itemCount.toLong()
            )
        )
    }

    fun emitCartClear() {
        emitLog("cart.cleared", Severity.INFO)
    }

    // ------------------------------------------------------------------
    // Checkout — 14-span trace + histogram sample
    // ------------------------------------------------------------------

    data class CheckoutLine(
        val productId: String,
        val productName: String,
        val quantity: Int,
        val lineTotal: Double
    )

    fun emitCheckout(
        lines: List<CheckoutLine>,
        totalUsd: Double,
        itemCount: Int,
        scope: CoroutineScope,
        onComplete: (Boolean) -> Unit = {}
    ): Job? {
        val tr = tracer ?: run { onComplete(false); return null }
        val startedMs = System.currentTimeMillis()

        val parent = tr.spanBuilder("checkout")
            .setSpanKind(SpanKind.INTERNAL)
            .setAttribute("cart.item_count", itemCount.toLong())
            .setAttribute("cart.line_count", lines.size.toLong())
            .setAttribute("cart.total_usd", totalUsd)
            .startSpan()

        return scope.launch(Dispatchers.Default) {
            runCheckoutTrace(tr, parent, lines, totalUsd)
            val durationMs = (System.currentTimeMillis() - startedMs).toDouble()
            checkoutDurationHistogram?.record(
                durationMs,
                io.opentelemetry.api.common.Attributes.of(
                    longKey("checkout.item_count"), itemCount.toLong(),
                    longKey("checkout.line_count"), lines.size.toLong()
                )
            )
            onComplete(true)
        }
    }

    private suspend fun runCheckoutTrace(
        tracer: Tracer,
        parent: Span,
        lines: List<CheckoutLine>,
        totalUsd: Double
    ) {
        val parentCtx = Context.current().with(parent)

        // 1. Validate cart
        childSpan(tracer, parentCtx, "checkout.validate_cart") { s ->
            s.setAttribute("cart.line_count", lines.size.toLong())
            delay(40)
        }

        // 2. Inventory check — parent + per-line children (cap 4)
        val inventory = tracer.spanBuilder("checkout.inventory_check")
            .setParent(parentCtx).startSpan()
        inventory.setAttribute("inventory.line_count", lines.size.toLong())
        val inventoryCtx = Context.current().with(inventory)
        for (line in lines.take(4)) {
            childSpan(tracer, inventoryCtx, "inventory.check_item") { s ->
                s.setAttribute("product.id", line.productId)
                s.setAttribute("cart.quantity", line.quantity.toLong())
                s.setAttribute("inventory.in_stock", true)
                delay(12)
            }
        }
        inventory.setStatus(StatusCode.OK); inventory.end()

        // 3. Calculate totals — parent + 3 children
        val totals = tracer.spanBuilder("checkout.calculate_totals")
            .setParent(parentCtx).startSpan()
        val totalsCtx = Context.current().with(totals)
        val taxUsd = totalUsd * 0.0825
        val shippingUsd = if (totalUsd > 50) 0.0 else 5.99

        childSpan(tracer, totalsCtx, "totals.subtotal") { s ->
            s.setAttribute("totals.subtotal_usd", totalUsd); delay(10)
        }
        childSpan(tracer, totalsCtx, "totals.tax") { s ->
            s.setAttribute("totals.tax_usd", taxUsd); delay(10)
        }
        childSpan(tracer, totalsCtx, "totals.shipping") { s ->
            s.setAttribute("totals.shipping_usd", shippingUsd)
            s.setAttribute("totals.free_shipping", totalUsd > 50)
            delay(10)
        }

        totals.setAttribute("totals.grand_total_usd", totalUsd + taxUsd + shippingUsd)
        totals.setStatus(StatusCode.OK); totals.end()

        // 4. Charge — parent + validate-card + authorize
        val charge = tracer.spanBuilder("checkout.charge")
            .setParent(parentCtx).startSpan()
        charge.setAttribute("payment.method", "card")
        val chargeCtx = Context.current().with(charge)

        childSpan(tracer, chargeCtx, "payment.validate_card") { _ -> delay(40) }
        childSpan(tracer, chargeCtx, "payment.authorize") { s ->
            s.setAttribute("payment.amount_usd", totalUsd + taxUsd + shippingUsd)
            delay(120)
        }
        charge.setStatus(StatusCode.OK); charge.end()

        // 5. Send confirmation — email render + send
        val confirm = tracer.spanBuilder("checkout.send_confirmation")
            .setParent(parentCtx).startSpan()
        val confirmCtx = Context.current().with(confirm)

        childSpan(tracer, confirmCtx, "email.render") { _ -> delay(20) }
        childSpan(tracer, confirmCtx, "email.send") { s ->
            s.setAttribute("email.provider", "demo-mailer"); delay(30)
        }
        confirm.setStatus(StatusCode.OK); confirm.end()

        // 6. Analytics report
        childSpan(tracer, parentCtx, "checkout.analytics.report") { s ->
            s.setAttribute("analytics.provider", "dash0"); delay(15)
        }

        parent.setStatus(StatusCode.OK); parent.end()
    }

    private suspend fun childSpan(
        tracer: Tracer,
        parentCtx: Context,
        name: String,
        block: suspend (Span) -> Unit
    ) {
        val span = tracer.spanBuilder(name).setParent(parentCtx).startSpan()
        try {
            block(span)
            span.setStatus(StatusCode.OK)
        } finally {
            span.end()
        }
    }

    // ------------------------------------------------------------------
    // Internal log helper — puts `event.name` both on the body and on an
    // attribute so consumers that filter either way work.
    // ------------------------------------------------------------------

    private fun emitLog(
        eventName: String,
        severity: Severity,
        attrs: Map<String, Any> = emptyMap()
    ) {
        val l = logger ?: return
        val builder = l.logRecordBuilder()
            .setBody(eventName)
            .setSeverity(severity)
            .setAttribute(stringKey("event.name"), eventName)
        for ((key, value) in attrs) {
            when (value) {
                is String -> builder.setAttribute(stringKey(key), value)
                is Long -> builder.setAttribute(longKey(key), value)
                is Int -> builder.setAttribute(longKey(key), value.toLong())
                is Double -> builder.setAttribute(doubleKey(key), value)
                is Float -> builder.setAttribute(doubleKey(key), value.toDouble())
                is Boolean -> builder.setAttribute(
                    io.opentelemetry.api.common.AttributeKey.booleanKey(key),
                    value
                )
                else -> builder.setAttribute(stringKey(key), value.toString())
            }
        }
        builder.emit()
    }
}
