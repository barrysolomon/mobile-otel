/**
 * ShopTelemetry — higher-level semantic helpers on top of Dash0Mobile.
 *
 * Mirrors `examples/upstream-demo-app-ios/AstronomyShop/Shop/ShopTelemetry.swift`
 * so the Dash0 data from both platforms looks identical — same attribute
 * names, same log/span/metric shapes. This is the promise the battle cards
 * depend on.
 */

import {Dash0Mobile} from '@dash0/mobile-react-native';
import type {Product} from './Product';

const WARN_QTY_THRESHOLD = 5;

export const ShopTelemetry = {
  /** Called from ProductListView.onAppear-equivalent (initial mount). */
  emitCatalogLoadTree(itemCount: number): void {
    const root = Dash0Mobile.startSpan('shop.load_catalog');
    root.setAttribute('shop.catalog_size', itemCount);

    const readBundle = Dash0Mobile.startSpan('catalog.read_bundle');
    readBundle.setAttribute('shop.catalog_size', itemCount);
    readBundle.end();

    const decode = Dash0Mobile.startSpan('catalog.decode');
    decode.setAttribute('shop.catalog_size', itemCount);
    decode.end();

    const enrich = Dash0Mobile.startSpan('catalog.enrich');
    enrich.setAttribute('shop.catalog_size', itemCount);
    enrich.end();

    root.end();
  },

  /** 3-span product-view tree: matches iOS ProductDetailView.onAppear. */
  emitProductViewTree(product: Product, loadMs: number): void {
    const root = Dash0Mobile.startSpan('shop.view_product');
    root.setAttribute('shop.item_id', product.id);
    root.setAttribute('shop.item_name', product.name);

    const render = Dash0Mobile.startSpan('detail.render');
    render.setAttribute('shop.item_id', product.id);
    render.end();

    const related = Dash0Mobile.startSpan('detail.load_related');
    related.setAttribute('shop.item_id', product.id);
    related.end();

    root.end();
    Dash0Mobile.log('shop.view_product', {
      'shop.item_id': product.id,
      'shop.item_name': product.name,
    });
    Dash0Mobile.recordMetric('shop.view_product.load_ms', loadMs, 'histogram', {
      'shop.item_id': product.id,
    });
  },

  emitCartAdd(product: Product, qty: number): void {
    Dash0Mobile.log('cart.add_item', {
      'shop.item_id': product.id,
      'shop.item_name': product.name,
      qty,
    });
    Dash0Mobile.recordMetric('shop.cart.items_added', qty, 'counter', {
      'shop.item_id': product.id,
    });
    if (qty >= WARN_QTY_THRESHOLD) {
      Dash0Mobile.log(
        'cart.large_quantity_warning',
        {'shop.item_id': product.id, qty},
        13, // WARN
      );
    }
  },

  /** 14-span checkout tree mirroring CartViewModel.swift. */
  async emitCheckoutTree(
    cartSize: number,
    itemIds: readonly string[],
    subtotalUsd: number,
  ): Promise<void> {
    const start = Date.now();
    await Dash0Mobile.span('checkout', async handle => {
      handle.setAttribute('shop.cart_size', cartSize);
      handle.setAttribute('shop.cart_subtotal_usd', subtotalUsd);

      // validate_cart
      const validate = Dash0Mobile.startSpan('validate_cart');
      validate.setAttribute('shop.cart_size', cartSize);
      validate.end();

      // inventory_check with per-item children
      const inv = Dash0Mobile.startSpan('inventory_check');
      inv.setAttribute('shop.cart_size', cartSize);
      for (const id of itemIds) {
        const child = Dash0Mobile.startSpan('inventory.check_item');
        child.setAttribute('shop.item_id', id);
        child.end();
      }
      inv.end();

      // calculate_totals + 3 children
      const totals = Dash0Mobile.startSpan('calculate_totals');
      for (const name of ['subtotal', 'tax', 'shipping']) {
        const c = Dash0Mobile.startSpan(`totals.${name}`);
        c.end();
      }
      totals.end();

      // charge + 2 children
      const charge = Dash0Mobile.startSpan('charge');
      charge.setAttribute('shop.cart_subtotal_usd', subtotalUsd);
      const validateCard = Dash0Mobile.startSpan('charge.validate_card');
      validateCard.end();
      const authorize = Dash0Mobile.startSpan('charge.authorize');
      authorize.end();
      charge.end();

      // send_confirmation + 2 children
      const conf = Dash0Mobile.startSpan('send_confirmation');
      const render = Dash0Mobile.startSpan('email.render');
      render.end();
      const send = Dash0Mobile.startSpan('email.send');
      send.end();
      conf.end();

      // analytics.report
      const analytics = Dash0Mobile.startSpan('analytics.report');
      analytics.end();
    });
    const durationMs = Date.now() - start;
    Dash0Mobile.recordMetric(
      'shop.checkout.duration_ms',
      durationMs,
      'histogram',
      {'shop.cart_size': cartSize},
    );
  },
};
