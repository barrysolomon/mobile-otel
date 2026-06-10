/**
 * RN-010 contract test — the bridge payload shape is the cross-repo seam.
 *
 * This test is a LIVING SPEC for Android (`Dash0MobileModule.kt`) and iOS
 * (`RCTDash0MobileModule.swift`). Any change here requires matching changes
 * in both native modules. Do not add optional fields without coordinating.
 */

import type {
  BridgePayload,
  LogPayload,
  SpanEndPayload,
  SpanStartPayload,
  MetricPayload,
  StartConfig,
} from '../../src/bridge/types';

describe('bridge payload contract', () => {
  it('LogPayload has all required fields', () => {
    const payload: LogPayload = {
      kind: 'log',
      name: 'cart.add_item',
      severity: 9,
      attributes: { 'shop.item_id': 'abc', qty: 2 },
      timeUnixNano: '1713600000000000000',
    };
    expect(payload.kind).toBe('log');
  });

  it('SpanStartPayload and SpanEndPayload reference the same spanId', () => {
    const start: SpanStartPayload = {
      kind: 'spanStart',
      spanId: 'a1b2c3d4e5f60708',
      name: 'checkout',
      spanKind: 'INTERNAL',
      attributes: {},
      startTimeUnixNano: '1713600000000000000',
    };
    const end: SpanEndPayload = {
      kind: 'spanEnd',
      spanId: start.spanId,
      status: 'OK',
      attributes: {},
      endTimeUnixNano: '1713600000050000000',
    };
    expect(end.spanId).toBe(start.spanId);
  });

  it('MetricPayload supports counter/histogram/gauge', () => {
    const cases: MetricPayload['instrumentType'][] = ['counter', 'histogram', 'gauge'];
    for (const instrumentType of cases) {
      const m: MetricPayload = {
        kind: 'metric',
        name: 'shop.cart.items_added',
        instrumentType,
        value: 1,
        attributes: {},
        timeUnixNano: '1713600000000000000',
      };
      expect(m.instrumentType).toBe(instrumentType);
    }
  });

  it('StartConfig minimum shape', () => {
    const cfg: StartConfig = {
      serviceName: 'otel-rn-astronomy-shop',
      endpoint: 'https://ingress/v1/logs',
    };
    expect(cfg.serviceName).toBeDefined();
  });

  it('StartConfig.sampling accepts always_on / always_off / dynamic', () => {
    const cfgs: StartConfig[] = [
      { serviceName: 's', endpoint: 'e', sampling: { strategy: 'always_on' } },
      { serviceName: 's', endpoint: 'e', sampling: { strategy: 'always_off' } },
      {
        serviceName: 's',
        endpoint: 'e',
        sampling: { strategy: 'dynamic', normalRate: 0.1, highPriorityRate: 1.0 },
      },
    ];
    expect(cfgs.map(c => c.sampling?.strategy)).toEqual([
      'always_on',
      'always_off',
      'dynamic',
    ]);
  });

  it('BridgePayload union discriminates on `kind`', () => {
    const samples: BridgePayload[] = [
      {
        kind: 'log',
        name: 'x',
        severity: 9,
        attributes: {},
        timeUnixNano: '0',
      },
      {
        kind: 'metric',
        name: 'y',
        instrumentType: 'counter',
        value: 1,
        attributes: {},
        timeUnixNano: '0',
      },
    ];
    for (const p of samples) {
      expect(['log', 'spanStart', 'spanEnd', 'metric']).toContain(p.kind);
    }
  });
});
