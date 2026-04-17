# Cross-Platform Shop Telemetry Contract

**Status:** Active — both Android and iOS Astronomy Shop demos must implement this contract verbatim.

Pins the exact logs, spans, metrics, and resource attributes emitted by the Astronomy Shop demo app on both platforms so a single Dash0 dashboard can bracket iPhone + Android traffic under identical shapes. Any divergence between platforms becomes a visible gap in the dashboard and a bug to fix.

## Principles

1. **One canonical name per signal.** If iOS emits `cart.add_item`, Android emits `cart.add_item` — not `cart.added` or `cart.item_added`.
2. **Same span tree shape.** Checkout on both platforms produces the 14-span 3-level-deep tree described in § 3. Deviating means the Dash0 trace viewer shows different shapes side by side.
3. **Resource-type uniform.** Both platforms set `dash0.resource.type=mobile`. Without this, Dash0 classifies one side as website/browser and the cross-platform filter breaks.
4. **Custom emissions live on top of auto-capture.** Android has more auto-instrumentation (tap / screen_view / jank) than iOS today. The contract covers only the custom shop emissions — both platforms additionally emit whatever their auto-capture modules produce. iOS closing the auto-capture gap is tracked in [IOS_ANDROID_PARITY.md](../IOS_ANDROID_PARITY.md).

## 1. Resource attributes (every signal)

| Key | Value | Notes |
|---|---|---|
| `service.name` | `otel-ios-astronomy-shop` / `otel-android-astronomy-shop` | Per-platform suffix; query by `service.name =~ "otel-.*-astronomy-shop"` to span both |
| `service.version` | `0.1.0` | In sync across platforms; bump together |
| `dash0.resource.type` | `mobile` | Required for Dash0 to group under Mobile (not Website) |
| `telemetry.sdk.name` | `io.dash0.mobile` (iOS) / `opentelemetry` (Android) | SDK identity; differs by design |
| `telemetry.sdk.language` | `swift` / `kotlin` | Platform identity |
| `os.name` | `iOS` / `android` | Use for the primary split |
| `device.manufacturer` | `Apple` / `<Build.MANUFACTURER>` | |
| `device.model.name` | `iPhone` / `<Build.MODEL>` | |
| `device.model.identifier` | `iPhone17,3 (Simulator)` etc. | iOS-only until we port the Android equivalent |
| `device.id` | UUID | Stable per install |

## 2. Log events

Every row is emitted by both platforms. Log `body` string equals the value in the `event.name` column unless otherwise noted.

| event.name | severity | trigger | attributes |
|---|---|---|---|
| `app.home_appeared` | INFO | Home / product-list view appears | `event.name` |
| `shop.view_product` | INFO | User taps a product card (detail view appears) | `event.name`, `product.id`, `product.name` |
| `cart.add_item` | INFO | Add-to-cart button tap | `event.name`, `product.id`, `product.name`, `cart.quantity` (delta), `cart.item_count` (post-add total) |
| `cart.large_quantity_warning` | WARN | Cumulative line quantity ≥ 5 after an add | `event.name`, `product.id`, `cart.quantity` (line total) |
| `cart.remove_item` | INFO | Remove-from-cart | `event.name`, `product.id`, `cart.item_count` |
| `cart.cleared` | INFO | Empty-cart action | `event.name` |

`event.name` is set as both the log body and a top-level attribute so consumers that filter by body or by attribute key both work.

## 3. Spans

### 3.1 Catalog load (on app launch — 4 spans)

```
shop.load_catalog                     (parent, INTERNAL)
├── catalog.read_bundle               (bundle.resource, bundle.bytes)
├── catalog.decode                    (shop.catalog.count)
└── catalog.enrich                    (shop.catalog.min_price_usd, shop.catalog.max_price_usd)
```

`shop.load_catalog` status: `OK` on success, `ERROR(description)` on read/decode failure. Parent carries `shop.catalog.count` as an attribute and status propagates from children.

### 3.2 Product view (on product-detail onAppear — 3 spans)

```
shop.view_product                     (parent, INTERNAL)
├── shop.load_reviews                 (product.id, reviews.count)
└── shop.load_recommendations         (recommendations.count)
```

Parent attributes: `product.id`, `product.name`, `auto_demo.iteration` (only when driven by the auto-demo driver).

### 3.3 Checkout (on place-order — 14 spans, 3 levels)

```
checkout                              (parent, INTERNAL — cart.item_count, cart.line_count, cart.total_usd)
├── checkout.validate_cart            (cart.line_count)
├── checkout.inventory_check          (inventory.line_count)
│   └── inventory.check_item × N      (cap N=4: product.id, cart.quantity, inventory.in_stock)
├── checkout.calculate_totals         (totals.grand_total_usd)
│   ├── totals.subtotal               (totals.subtotal_usd)
│   ├── totals.tax                    (totals.tax_usd)
│   └── totals.shipping               (totals.shipping_usd, totals.free_shipping)
├── checkout.charge                   (payment.method)
│   ├── payment.validate_card
│   └── payment.authorize             (payment.amount_usd)
├── checkout.send_confirmation
│   ├── email.render
│   └── email.send                    (email.provider)
└── checkout.analytics.report         (analytics.provider)
```

All spans: `INTERNAL` kind, `OK` status on success.

## 4. Metrics

| Metric | Type | Unit | Attributes on record |
|---|---|---|---|
| `shop.cart.items_added` | counter (long sum) | items | `product.id`, `product.category` |
| `shop.checkout.duration_ms` | histogram (double) | ms | `checkout.item_count`, `checkout.line_count` |
| `shop.view_product.load_ms` | histogram (double) | ms | `product.id` |

Emission sites:
- `shop.cart.items_added` — on every add-to-cart action; value = quantity added (delta).
- `shop.checkout.duration_ms` — one sample per completed checkout; value = wall-clock ms from checkout start to last child span end.
- `shop.view_product.load_ms` — one sample per product-detail view; value = wall-clock ms for the entire 3-span product-view sub-tree.

## 5. Auto-driver (DASH0_AUTO_DEMO=1)

Both platforms ship an auto-driver that, when enabled, loops through the full journey without user input. Cadence and phase shape are identical so the two streams look aligned in Dash0 when running side by side.

Five-phase cycle, 0.8 s tick (5 emissions per cycle, one checkout per cycle):

| Phase | Action |
|---|---|
| 0 | View product A, add 1 to cart |
| 1 | View product B, add 1 to cart |
| 2 | View product C, add 5 to cart (triggers `cart.large_quantity_warning`) |
| 3 | Checkout (14-span trace + histogram sample) |
| 4 | Idle gap |

## 6. What's explicitly NOT in the contract

- **Auto-instrumentation auto-emissions** — Android's `ui.tap`, `ui.screen_view`, `page.<ScreenName>` spans, `mobile.ui.jank.*` metrics, etc. emit alongside this contract on Android and will on iOS once parity closes (`instrumentation/tap`, etc. not ported yet). Dashboards should treat these as platform bonuses, not contract requirements.
- **Error logs from `ErrorsInstrumentation`** — both platforms auto-emit `app.crash` on recovery. That's OTel-standard, not a shop-specific contract item.

## 7. How to change this contract

Touching any name, attribute, or tree shape in this doc means both platforms update in the **same commit**. Cross-platform divergence is a bug; shipping one side ahead of the other makes it a paying bug. Reviewers should reject PRs that modify emission shapes on one platform without the matching update on the other.
