import {Platform} from 'react-native';

/**
 * Android SDK exports OTLP/gRPC (port 4317); iOS SDK exports OTLP/HTTP
 * (port 4318). The shared `otel-config.json` carries one endpoint, so
 * we rewrite the port per-platform so a single user-supplied Dash0
 * endpoint works for both. Strips any explicit port the user typed and
 * substitutes the right one for the runtime platform.
 *
 * Exported (vs. inlined in `App.tsx`) so unit tests can verify both
 * platform branches without a mounted React tree.
 */
export function endpointForPlatform(
  raw: string,
  platform: 'android' | 'ios' | (string & {}) = Platform.OS,
): string {
  const expectedPort = platform === 'android' ? 4317 : 4318;
  const m = raw.match(/^([a-z]+:\/\/[^/:]+)(?::\d+)?(\/.*)?$/i);
  if (!m) return raw;
  const [, schemeAndHost, path = ''] = m;
  return `${schemeAndHost}:${expectedPort}${path}`;
}
