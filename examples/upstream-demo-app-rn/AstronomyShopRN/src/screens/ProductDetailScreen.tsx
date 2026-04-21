import React, {useEffect, useState} from 'react';
import {
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import type {NativeStackScreenProps} from '@react-navigation/native-stack';
import {CATALOG} from '../shop/Product';
import {useCartStore} from '../shop/CartStore';
import {ShopTelemetry} from '../shop/ShopTelemetry';
import type {RootStackParamList} from '../navigation/types';

type Props = NativeStackScreenProps<RootStackParamList, 'ProductDetail'>;

export function ProductDetailScreen({route}: Props): React.ReactElement {
  const {productId} = route.params;
  const product = CATALOG.find(p => p.id === productId);
  const addItem = useCartStore(s => s.addItem);
  const [qty, setQty] = useState(1);
  const [added, setAdded] = useState(false);

  useEffect(() => {
    if (!product) return;
    const startedAt = Date.now();
    ShopTelemetry.emitProductViewTree(product, Date.now() - startedAt);
  }, [product]);

  if (!product) {
    return (
      <View style={styles.empty}>
        <Text>Product not found.</Text>
      </View>
    );
  }

  const onAdd = () => {
    addItem(product.id, qty);
    setAdded(true);
    setTimeout(() => setAdded(false), 1500);
  };

  return (
    <ScrollView contentContainerStyle={styles.container}>
      <View style={styles.hero} />
      <Text style={styles.title}>{product.name}</Text>
      <Text style={styles.price}>${product.priceUsd.toFixed(2)}</Text>
      <Text style={styles.description}>{product.description}</Text>

      <View style={styles.qtyRow}>
        <Text style={styles.qtyLabel}>Quantity</Text>
        <View style={styles.stepper}>
          <Pressable
            accessibilityLabel="product.qty_minus"
            testID="product.qty_minus"
            onPress={() => setQty(q => Math.max(1, q - 1))}
            style={styles.stepBtn}>
            <Text style={styles.stepBtnText}>–</Text>
          </Pressable>
          <Text style={styles.qtyValue}>{qty}</Text>
          <Pressable
            accessibilityLabel="product.qty_plus"
            testID="product.qty_plus"
            onPress={() => setQty(q => Math.min(10, q + 1))}
            style={styles.stepBtn}>
            <Text style={styles.stepBtnText}>+</Text>
          </Pressable>
        </View>
      </View>

      <Pressable
        accessibilityLabel="product.add_to_cart"
        testID="product.add_to_cart"
        onPress={onAdd}
        style={[styles.addBtn, added && styles.addBtnAdded]}>
        <Text style={styles.addBtnText}>
          {added ? 'Added to cart!' : 'Add to cart'}
        </Text>
      </Pressable>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {padding: 16},
  empty: {flex: 1, alignItems: 'center', justifyContent: 'center'},
  hero: {
    width: '100%',
    height: 240,
    borderRadius: 12,
    backgroundColor: '#E5E5EA',
    marginBottom: 16,
  },
  title: {fontSize: 24, fontWeight: '700'},
  price: {fontSize: 18, color: '#34C759', marginTop: 4},
  description: {fontSize: 15, marginTop: 12, lineHeight: 21},
  qtyRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: 20,
  },
  qtyLabel: {fontSize: 16, fontWeight: '600'},
  stepper: {flexDirection: 'row', alignItems: 'center'},
  stepBtn: {
    width: 32,
    height: 32,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#E5E5EA',
    borderRadius: 16,
  },
  stepBtnText: {fontSize: 18, fontWeight: '700'},
  qtyValue: {
    fontSize: 16,
    fontWeight: '600',
    minWidth: 32,
    textAlign: 'center',
  },
  addBtn: {
    marginTop: 24,
    paddingVertical: 14,
    borderRadius: 8,
    backgroundColor: '#007AFF',
    alignItems: 'center',
  },
  addBtnAdded: {backgroundColor: '#34C759'},
  addBtnText: {color: 'white', fontSize: 16, fontWeight: '600'},
});
