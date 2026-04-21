/**
 * Touch telemetry helpers (opt-in).
 *
 * `withTapTelemetry(target, handler?, extraAttrs?)` returns a press handler
 * that emits a `ui.tap` log before forwarding the call. Attribute names
 * match Android's `TapInstrumentation` so cross-platform dashboards line up.
 */

import { Dash0Mobile } from '../index';
import type { Attributes } from '../bridge/types';

const SEVERITY_INFO = 9 as const;

type Handler<Args extends unknown[], R> = (...args: Args) => R;

export function withTapTelemetry<Args extends unknown[], R>(
  target: string,
  handler?: Handler<Args, R>,
  extraAttrs?: Attributes,
): Handler<Args, R | undefined> {
  return function wrappedOnPress(...args: Args): R | undefined {
    Dash0Mobile.log(
      'ui.tap',
      {
        'ui.target': target,
        ...(extraAttrs ?? {}),
      },
      SEVERITY_INFO,
    );
    return handler ? handler(...args) : undefined;
  };
}
