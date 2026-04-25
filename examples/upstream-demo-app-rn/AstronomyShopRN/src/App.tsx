import React, {useEffect} from 'react';
import {NavigationContainer} from '@react-navigation/native';
import {createNativeStackNavigator} from '@react-navigation/native-stack';
import {SafeAreaProvider} from 'react-native-safe-area-context';
import {Dash0Mobile} from '@dash0/mobile-react-native';
import {ProductListScreen} from './screens/ProductListScreen';
import {ProductDetailScreen} from './screens/ProductDetailScreen';
import {CartScreen} from './screens/CartScreen';
import {AutoDemoDriver} from './shop/AutoDemoDriver';
import {useCartStore} from './shop/CartStore';
import {ShopTelemetry} from './shop/ShopTelemetry';
import {CATALOG} from './shop/Product';
import type {RootStackParamList} from './navigation/types';
// Loaded from a .gitignored JSON at repo root — matches iOS + Android demo
// convention. Template (`otel-config.json.template`) is committed.
// eslint-disable-next-line @typescript-eslint/no-var-requires
const otelConfig: {
  serviceName: string;
  serviceVersion?: string;
  endpoint: string;
  authToken?: string;
  dataset?: string;
} = require('../otel-config.json');

const Stack = createNativeStackNavigator<RootStackParamList>();

export default function App(): React.ReactElement {
  useEffect(() => {
    Dash0Mobile.start({
      serviceName: otelConfig.serviceName,
      serviceVersion: otelConfig.serviceVersion,
      endpoint: otelConfig.endpoint,
      authToken: otelConfig.authToken,
      dataset: otelConfig.dataset,
      // RN 0.85 new-arch TurboModule init order makes `AppState` unreliable
      // inside the first useEffect. Disable lifecycle auto-capture until
      // the init-order story stabilizes upstream.
      //
      // 2026-04-24 re-investigated: tried bumping the JS-side defer to
      // 1500ms. Still redboxes — the `Invariant Violation` from
      // `TurboModuleRegistry.getEnforcing('PlatformConstants')` fires
      // BEFORE the defer's setTimeout callback runs, and RN's `RCTFatal`
      // converts the JS throw to a native fatal that bypasses our
      // try/catch. This is upstream RN, not a defer-tuning problem.
      // Leave `lifecycle: false` in place. Gate 1 stays 🔴 with this
      // documented architectural cause.
      autoCapture: { lifecycle: false },
    }).catch(() => {
      // Non-RN runtime (tests, SSR) — safe to ignore
    });

    // Force a periodic flush every 10s so telemetry lands quickly in demo
    // runs. Production apps that rely on CONDITIONAL export can remove this.
    const flushTimer = setInterval(() => {
      Dash0Mobile.flushWindow(5).catch(() => {
        // ignore
      });
    }, 10000);

    const auto = process.env.DASH0_AUTO_DEMO === '1';
    if (!auto) {
      return () => {
        clearInterval(flushTimer);
      };
    }

    const addItem = useCartStore.getState().addItem;
    const doCheckout = useCartStore.getState().checkout;
    const driver = new AutoDemoDriver({
      viewProduct(id) {
        const product = CATALOG.find(p => p.id === id);
        if (product) ShopTelemetry.emitProductViewTree(product, 50);
      },
      addToCart(id, qty) {
        addItem(id, qty);
      },
      async checkout() {
        await doCheckout();
      },
    });
    driver.start();
    return () => {
      clearInterval(flushTimer);
      driver.stop();
    };
  }, []);

  return (
    <SafeAreaProvider>
      <NavigationContainer>
        <Stack.Navigator initialRouteName="ProductList">
          <Stack.Screen
            name="ProductList"
            component={ProductListScreen}
            options={{title: 'Astronomy Shop'}}
          />
          <Stack.Screen
            name="ProductDetail"
            component={ProductDetailScreen}
            options={{title: 'Details'}}
          />
          <Stack.Screen
            name="Cart"
            component={CartScreen}
            options={{title: 'Cart'}}
          />
        </Stack.Navigator>
      </NavigationContainer>
    </SafeAreaProvider>
  );
}
