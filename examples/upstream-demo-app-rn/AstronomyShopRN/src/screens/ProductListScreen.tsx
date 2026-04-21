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
  }, []);

  useEffect(() => {
    navigation.setOptions({
      title: 'Astronomy Shop',
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
    <FlatList
      data={[...CATALOG]}
      keyExtractor={p => p.id}
      renderItem={renderItem}
      contentContainerStyle={styles.list}
    />
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
});
