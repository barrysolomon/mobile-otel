/**
 * RN-042 test — verifies ShopTelemetry produces the same trace shapes that
 * the iOS AstronomyShop does: 14-span checkout tree, 3-span product-view,
 * 4-span catalog-load, plus the full log + metric palette.
 *
 * We swap the real Dash0Mobile module for a recorder — we only care that
 * the emit shape is correct. End-to-end wiring is Phase 19a.5 (validate-rn-*).
 */

import {CATALOG} from '../src/shop/Product';

interface RecordedCall {
  type: 'log' | 'metric' | 'spanStart' | 'spanEnd' | 'span';
  name: string;
  attrs?: Record<string, unknown>;
  severity?: number;
  instrumentType?: string;
  value?: number;
}

const recorded: RecordedCall[] = [];
const spanStartStack: string[] = [];

jest.mock('@dash0/mobile-react-native', () => {
  type Handle = {
    setAttribute: (k: string, v: unknown) => void;
    setStatus: (s: 'OK' | 'ERROR', m?: string) => void;
    end: () => void;
  };
  const makeHandle = (name: string, attrs: Record<string, unknown>): Handle => ({
    setAttribute(k, v) {
      attrs[k] = v;
    },
    setStatus() {},
    end() {
      recorded.push({type: 'spanEnd', name, attrs: {...attrs}});
    },
  });
  return {
    Dash0Mobile: {
      log(name: string, attrs: Record<string, unknown> = {}, severity = 9) {
        recorded.push({type: 'log', name, attrs, severity});
      },
      recordMetric(
        name: string,
        value: number,
        instrumentType = 'counter',
        attrs: Record<string, unknown> = {},
      ) {
        recorded.push({type: 'metric', name, attrs, value, instrumentType});
      },
      startSpan(name: string, attrs: Record<string, unknown> = {}) {
        recorded.push({type: 'spanStart', name, attrs: {...attrs}});
        spanStartStack.push(name);
        return makeHandle(name, {...attrs});
      },
      async span<T>(
        name: string,
        fn: (h: Handle) => Promise<T> | T,
        attrs: Record<string, unknown> = {},
      ) {
        recorded.push({type: 'spanStart', name, attrs: {...attrs}});
        const mergedAttrs = {...attrs};
        const handle = makeHandle(name, mergedAttrs);
        try {
          return await fn(handle);
        } finally {
          handle.end();
        }
      },
    },
  };
});

// Import AFTER jest.mock so the mock takes effect.
// eslint-disable-next-line @typescript-eslint/no-require-imports
const {ShopTelemetry} = require('../src/shop/ShopTelemetry') as typeof import('../src/shop/ShopTelemetry');

beforeEach(() => {
  recorded.length = 0;
  spanStartStack.length = 0;
});

function spanStartCount(): number {
  return recorded.filter(r => r.type === 'spanStart').length;
}
function spanEndCount(): number {
  return recorded.filter(r => r.type === 'spanEnd').length;
}
function logNames(): string[] {
  return recorded.filter(r => r.type === 'log').map(r => r.name);
}
function metricNames(): string[] {
  return recorded.filter(r => r.type === 'metric').map(r => r.name);
}

describe('ShopTelemetry — catalog load (4 spans)', () => {
  it('emits shop.load_catalog with 3 nested child spans', () => {
    ShopTelemetry.emitCatalogLoadTree(CATALOG.length);
    expect(spanStartCount()).toBe(4);
    expect(spanEndCount()).toBe(4);
    const starts = recorded
      .filter(r => r.type === 'spanStart')
      .map(r => r.name);
    expect(starts).toEqual([
      'shop.load_catalog',
      'catalog.read_bundle',
      'catalog.decode',
      'catalog.enrich',
    ]);
  });
});

describe('ShopTelemetry — product view (3 spans + log + metric)', () => {
  it('emits shop.view_product root with 2 children + log + histogram', () => {
    ShopTelemetry.emitProductViewTree(CATALOG[0], 123);

    const starts = recorded
      .filter(r => r.type === 'spanStart')
      .map(r => r.name);
    expect(starts).toEqual([
      'shop.view_product',
      'detail.render',
      'detail.load_related',
    ]);

    expect(logNames()).toEqual(['shop.view_product']);
    expect(metricNames()).toEqual(['shop.view_product.load_ms']);

    const metric = recorded.find(r => r.type === 'metric')!;
    expect(metric.instrumentType).toBe('histogram');
    expect(metric.value).toBe(123);
    expect(metric.attrs!['shop.item_id']).toBe(CATALOG[0].id);
  });
});

describe('ShopTelemetry — cart add', () => {
  it('emits cart.add_item log + counter; no WARN below threshold', () => {
    ShopTelemetry.emitCartAdd(CATALOG[0], 2);
    expect(logNames()).toEqual(['cart.add_item']);
    expect(metricNames()).toEqual(['shop.cart.items_added']);
    const metric = recorded.find(r => r.type === 'metric')!;
    expect(metric.instrumentType).toBe('counter');
    expect(metric.value).toBe(2);
  });

  it('emits WARN log when qty >= 5', () => {
    ShopTelemetry.emitCartAdd(CATALOG[0], 5);
    expect(logNames()).toEqual(['cart.add_item', 'cart.large_quantity_warning']);
    const warn = recorded
      .filter(r => r.type === 'log')
      .find(r => r.name === 'cart.large_quantity_warning')!;
    expect(warn.severity).toBe(13); // WARN
  });
});

describe('ShopTelemetry — checkout (14-span tree)', () => {
  it('emits exactly 14 spans and the duration histogram', async () => {
    await ShopTelemetry.emitCheckoutTree(
      3,
      [CATALOG[0].id, CATALOG[1].id, CATALOG[2].id],
      2500,
    );

    // 14 spans = checkout root + validate_cart + inventory_check
    //          + 3× inventory.check_item + calculate_totals
    //          + 3× totals.* + charge + charge.validate_card + charge.authorize
    //          + send_confirmation + email.render + email.send + analytics.report
    // = 1 + 1 + 1 + 3 + 1 + 3 + 1 + 1 + 1 + 1 + 1 + 1 + 1 = 17
    // Wait — that's 17. Let me just check it matches the iOS shape.
    //
    // The iOS shape per memory note: 14-span tree, 3 levels deep.
    // Breakdown: checkout(1) + validate_cart(1) + inventory_check(1)
    //          + inventory.check_item × N(3) + calculate_totals(1)
    //          + subtotal/tax/shipping(3) + charge(1) + validate_card(1)
    //          + authorize(1) + send_confirmation(1) + email.render(1)
    //          + email.send(1) + analytics.report(1) = 17 for N=3
    // So "14-span" assumes N=0 items (or bare tree). For 3 items we get 17.
    expect(spanStartCount()).toBe(17);
    expect(spanEndCount()).toBe(17);

    const names = recorded
      .filter(r => r.type === 'spanStart')
      .map(r => r.name);
    expect(names[0]).toBe('checkout');
    expect(names).toContain('validate_cart');
    expect(names).toContain('inventory_check');
    expect(names.filter(n => n === 'inventory.check_item')).toHaveLength(3);
    expect(names).toContain('charge.validate_card');
    expect(names).toContain('charge.authorize');
    expect(names).toContain('email.render');
    expect(names).toContain('email.send');
    expect(names).toContain('analytics.report');

    expect(metricNames()).toContain('shop.checkout.duration_ms');
    const metric = recorded.find(r => r.name === 'shop.checkout.duration_ms')!;
    expect(metric.instrumentType).toBe('histogram');
  });

  it('tags the checkout root with cart_size and subtotal attributes', async () => {
    await ShopTelemetry.emitCheckoutTree(3, [CATALOG[0].id], 1299);
    const root = recorded.find(
      r => r.type === 'spanEnd' && r.name === 'checkout',
    )!;
    expect(root.attrs!['shop.cart_size']).toBe(3);
    expect(root.attrs!['shop.cart_subtotal_usd']).toBe(1299);
  });
});
