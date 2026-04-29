/**
 * Per-platform endpoint port rewrite — covers the trap that broke RN
 * Android Gates 2/3: the shared `otel-config.json` ships with one
 * endpoint, but the Android SDK speaks OTLP/gRPC (:4317) while the iOS
 * SDK speaks OTLP/HTTP (:4318). `endpointForPlatform` substitutes the
 * right port per platform so a single user-supplied Dash0 endpoint
 * works for both consumers.
 */

import {endpointForPlatform} from '../src/otelEndpoint';

describe('endpointForPlatform', () => {
  describe('android → :4317', () => {
    it('rewrites a :4318 endpoint to :4317', () => {
      expect(
        endpointForPlatform('https://ingress.us-west-2.aws.dash0.com:4318', 'android'),
      ).toBe('https://ingress.us-west-2.aws.dash0.com:4317');
    });

    it('keeps a :4317 endpoint as :4317', () => {
      expect(
        endpointForPlatform('https://ingress.us-west-2.aws.dash0.com:4317', 'android'),
      ).toBe('https://ingress.us-west-2.aws.dash0.com:4317');
    });

    it('adds :4317 when no port is specified', () => {
      expect(
        endpointForPlatform('https://ingress.us-west-2.aws.dash0.com', 'android'),
      ).toBe('https://ingress.us-west-2.aws.dash0.com:4317');
    });

    it('preserves path when present', () => {
      expect(
        endpointForPlatform('https://collector.example.com:4318/otlp', 'android'),
      ).toBe('https://collector.example.com:4317/otlp');
    });
  });

  describe('ios → :4318', () => {
    it('rewrites a :4317 endpoint to :4318', () => {
      expect(
        endpointForPlatform('https://ingress.us-west-2.aws.dash0.com:4317', 'ios'),
      ).toBe('https://ingress.us-west-2.aws.dash0.com:4318');
    });

    it('keeps a :4318 endpoint as :4318', () => {
      expect(
        endpointForPlatform('https://ingress.us-west-2.aws.dash0.com:4318', 'ios'),
      ).toBe('https://ingress.us-west-2.aws.dash0.com:4318');
    });
  });

  describe('edge cases', () => {
    it('returns the raw string unchanged when it does not parse as URL', () => {
      expect(endpointForPlatform('not-a-url', 'android')).toBe('not-a-url');
    });

    it('preserves http:// scheme (used by localhost dev collectors)', () => {
      expect(endpointForPlatform('http://10.0.2.2:4318', 'android')).toBe(
        'http://10.0.2.2:4317',
      );
    });

    it('handles arbitrary non-Dash0 ports', () => {
      expect(
        endpointForPlatform('https://my-collector.internal:9999', 'android'),
      ).toBe('https://my-collector.internal:4317');
    });
  });
});
