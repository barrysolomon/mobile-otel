import React, {useEffect} from 'react';
import {
  FlatList,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import type {NativeStackScreenProps} from '@react-navigation/native-stack';
import {CATALOG, Product} from '../shop/Product';
import {useCartStore} from '../shop/CartStore';
import {ShopTelemetry} from '../shop/ShopTelemetry';
import type {RootStackParamList} from '../navigation/types';

type Props = NativeStackScreenProps<RootStackParamList, 'ProductList'>;

export function ProductListScreen({navigation}: Props): React.ReactElement {
  const itemCount = useCartStore(s =>
    s.lines.reduce((n, l) => n + l.qty, 0),
  );

  useEffect(() => {
    ShopTelemetry.emitCatalogLoadTree(CATALOG.length);
    // Gate 2 poke: delayed 2s so Dash0Mobile.start() finishes installing the
    // fetch() shim before we call fetch — if they race the first GET escapes
    // the shim. Mirrors iOS native AstronomyShop's pokeBackend() for parity.
    setTimeout(() => {
      fetch('https://httpbin.org/get').catch(() => {});
    }, 2000);
  }, []);

  useEffect(() => {
    navigation.setOptions({
      title: 'Astronomy Shop',
      headerLeft: () => (
        <Pressable
          accessibilityLabel="nav.errors"
          testID="nav.errors"
          onPress={() => navigation.navigate('ErrorTriggers')}
          style={styles.errorsButton}>
          <Text style={styles.errorsLabel}>⚠️ Errors</Text>
        </Pressable>
      ),
      headerRight: () => (
        <Pressable
          accessibilityLabel="nav.cart"
          testID="nav.cart"
          onPress={() => navigation.navigate('Cart')}
          style={styles.cartButton}>
          <Text style={styles.cartLabel}>Cart ({itemCount})</Text>
        </Pressable>
      ),
    });
  }, [navigation, itemCount]);

  const renderItem = ({item}: {item: Product}) => (
    <Pressable
      testID={`product.row.${item.id}`}
      accessibilityLabel={`product.row.${item.id}`}
      style={styles.row}
      onPress={() => navigation.navigate('ProductDetail', {productId: item.id})}>
      <View style={styles.thumb} />
      <View style={styles.meta}>
        <Text style={styles.name}>{item.name}</Text>
        <Text style={styles.price}>${item.priceUsd.toFixed(2)}</Text>
      </View>
    </Pressable>
  );

  return (
    <View style={{flex: 1}}>
      <FlatList
        data={[...CATALOG]}
        keyExtractor={p => p.id}
        renderItem={renderItem}
        contentContainerStyle={styles.list}
      />
      {/*
        Previously this screen carried a giant red "Trigger Crash (Gate 3)" button at
        the bottom. It's been moved to a dedicated `ErrorTriggers` screen reachable
        from the headerLeft button — same affordance as iOS's exclamation-triangle
        and Android's Errors bottom-nav tab. The old test hook is preserved as a
        button on that screen (testID="trigger.crash_throw").
      */}
    </View>
  );
}

const styles = StyleSheet.create({
  list: {paddingVertical: 8},
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  thumb: {
    width: 60,
    height: 60,
    borderRadius: 8,
    backgroundColor: '#E5E5EA',
    marginRight: 12,
  },
  meta: {flex: 1},
  name: {fontSize: 16, fontWeight: '600'},
  price: {fontSize: 14, color: '#6C6C70', marginTop: 4},
  cartButton: {paddingHorizontal: 8, paddingVertical: 4},
  cartLabel: {fontSize: 14, fontWeight: '600', color: '#007AFF'},
  errorsButton: {paddingHorizontal: 8, paddingVertical: 4},
  errorsLabel: {fontSize: 14, fontWeight: '600', color: '#FF6A00'},
  // Retained for backward compat; ProductListScreen no longer uses these
  // (the giant crash button moved to ErrorTriggersScreen). RN-host CI tests
  // may still reference them — safe to remove in a follow-up.
  crashButton: {
    backgroundColor: '#D32F2F',
    paddingVertical: 14,
    alignItems: 'center',
    margin: 16,
    borderRadius: 8,
  },
  crashLabel: {color: '#fff', fontSize: 15, fontWeight: '700'},
});
