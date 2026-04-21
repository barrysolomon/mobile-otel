/**
 * TurboModule codegen spec for @dash0/mobile-react-native.
 *
 * This file is intentionally NOT imported by `src/index.ts` — it exists
 * solely to drive RN codegen (0.68+ / Fabric). The runtime code path goes
 * through `require('react-native').NativeModules.Dash0Mobile` so we keep
 * working on both old-arch and new-arch.
 *
 * Codegen constraints (from React Native docs):
 *   - methods must return `Promise<T>` or `void`
 *   - parameters must be primitives, `Object`, `Array`, or `{ readonly ... }`
 *   - no union types as parameters (codegen rejects)
 *
 * The shapes below are deliberately looser than `bridge/types.ts` (Object
 * instead of discriminated unions) because codegen can't narrow on `kind`.
 * The native side runtime-dispatches on the `kind` string.
 */

import type { TurboModule } from 'react-native';
import { TurboModuleRegistry } from 'react-native';

export interface Spec extends TurboModule {
  start(config: Object): Promise<void>;
  emitBatch(payloads: Object[]): Promise<void>;
  flushWindow(minutes: number): Promise<void>;
  shutdown(): Promise<void>;
}

export default TurboModuleRegistry.getEnforcing<Spec>('Dash0Mobile');
