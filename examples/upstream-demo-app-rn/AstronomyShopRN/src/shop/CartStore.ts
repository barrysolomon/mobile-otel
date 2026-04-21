import {create} from 'zustand';
import {CATALOG, Product} from './Product';
import {ShopTelemetry} from './ShopTelemetry';

export interface CartLine {
  productId: string;
  qty: number;
}

interface CartState {
  lines: CartLine[];
  addItem: (productId: string, qty?: number) => void;
  removeItem: (productId: string) => void;
  clear: () => void;
  checkout: () => Promise<void>;
  subtotalUsd: () => number;
}

const findProduct = (id: string): Product | undefined =>
  CATALOG.find(p => p.id === id);

export const useCartStore = create<CartState>((set, get) => ({
  lines: [],
  addItem: (productId, qty = 1) => {
    const product = findProduct(productId);
    if (!product) return;
    set(state => {
      const existing = state.lines.find(l => l.productId === productId);
      if (existing) {
        return {
          lines: state.lines.map(l =>
            l.productId === productId ? {...l, qty: l.qty + qty} : l,
          ),
        };
      }
      return {lines: [...state.lines, {productId, qty}]};
    });
    ShopTelemetry.emitCartAdd(product, qty);
  },
  removeItem: productId =>
    set(state => ({lines: state.lines.filter(l => l.productId !== productId)})),
  clear: () => set({lines: []}),
  subtotalUsd: () => {
    const {lines} = get();
    return lines.reduce((sum, l) => {
      const p = findProduct(l.productId);
      return p ? sum + p.priceUsd * l.qty : sum;
    }, 0);
  },
  checkout: async () => {
    const {lines, subtotalUsd, clear} = get();
    if (lines.length === 0) return;
    const ids = lines.map(l => l.productId);
    const cartSize = lines.reduce((n, l) => n + l.qty, 0);
    await ShopTelemetry.emitCheckoutTree(cartSize, ids, subtotalUsd());
    clear();
  },
}));
