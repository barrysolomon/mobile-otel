import React, {useState} from 'react';
import {
  Alert,
  FlatList,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import {CATALOG, Product} from '../shop/Product';
import {useCartStore, CartLine} from '../shop/CartStore';

function findProduct(id: string): Product | undefined {
  return CATALOG.find(p => p.id === id);
}

export function CartScreen(): React.ReactElement {
  const lines = useCartStore(s => s.lines);
  const subtotalUsd = useCartStore(s => s.subtotalUsd);
  const checkout = useCartStore(s => s.checkout);
  const clear = useCartStore(s => s.clear);
  const [busy, setBusy] = useState(false);

  const onCheckout = async () => {
    setBusy(true);
    try {
      await checkout();
      Alert.alert('Order placed!', 'Your order has been submitted.');
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      Alert.alert('Checkout failed', msg);
    } finally {
      setBusy(false);
    }
  };

  if (lines.length === 0) {
    return (
      <View style={styles.empty}>
        <Text style={styles.emptyTitle}>Cart is empty</Text>
        <Text style={styles.emptyBody}>
          Browse products and add some items.
        </Text>
      </View>
    );
  }

  const renderLine = ({item}: {item: CartLine}) => {
    const product = findProduct(item.productId);
    if (!product) return null;
    const lineTotal = product.priceUsd * item.qty;
    return (
      <View style={styles.line}>
        <View style={styles.lineMeta}>
          <Text style={styles.lineName}>{product.name}</Text>
          <Text style={styles.lineSub}>
            ${product.priceUsd.toFixed(2)} × {item.qty}
          </Text>
        </View>
        <Text style={styles.lineTotal}>${lineTotal.toFixed(2)}</Text>
      </View>
    );
  };

  return (
    <View style={styles.container}>
      <FlatList
        data={lines}
        keyExtractor={l => l.productId}
        renderItem={renderLine}
        ListFooterComponent={() => (
          <View style={styles.totalRow}>
            <Text style={styles.totalLabel}>Total</Text>
            <Text style={styles.totalValue}>${subtotalUsd().toFixed(2)}</Text>
          </View>
        )}
      />
      <View style={styles.actions}>
        <Pressable
          accessibilityLabel="cart.checkout"
          testID="cart.checkout"
          disabled={busy}
          onPress={onCheckout}
          style={[styles.checkoutBtn, busy && styles.disabled]}>
          <Text style={styles.checkoutText}>
            {busy ? 'Processing…' : 'Checkout'}
          </Text>
        </Pressable>
        <Pressable
          accessibilityLabel="cart.clear"
          testID="cart.clear"
          disabled={busy}
          onPress={clear}
          style={styles.clearBtn}>
          <Text style={styles.clearText}>Clear cart</Text>
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {flex: 1},
  empty: {flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24},
  emptyTitle: {fontSize: 20, fontWeight: '700'},
  emptyBody: {fontSize: 14, color: '#6C6C70', marginTop: 8, textAlign: 'center'},
  line: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#C7C7CC',
  },
  lineMeta: {flex: 1},
  lineName: {fontSize: 16, fontWeight: '600'},
  lineSub: {fontSize: 13, color: '#6C6C70', marginTop: 2},
  lineTotal: {fontSize: 15, fontWeight: '700'},
  totalRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    padding: 16,
  },
  totalLabel: {fontSize: 18, fontWeight: '700'},
  totalValue: {fontSize: 18, fontWeight: '700'},
  actions: {padding: 16, gap: 12},
  checkoutBtn: {
    backgroundColor: '#007AFF',
    paddingVertical: 14,
    borderRadius: 8,
    alignItems: 'center',
  },
  checkoutText: {color: 'white', fontSize: 16, fontWeight: '600'},
  disabled: {opacity: 0.5},
  clearBtn: {alignItems: 'center', paddingVertical: 10},
  clearText: {color: '#FF3B30', fontSize: 14, fontWeight: '600'},
});
