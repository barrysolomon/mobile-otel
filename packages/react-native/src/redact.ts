/**
 * Shared redaction + truncation helpers.
 *
 * Telemetry attributes can carry secrets (query-string tokens, bearer
 * headers echoed into error messages) and unbounded payloads (multi-MB
 * stack traces). These helpers strip the obvious leaks and cap sizes so a
 * single pathological event can't exfiltrate credentials or blow the
 * bridge/export budget.
 */

/** Max length for a sanitized URL (origin + path). */
const MAX_URL_LEN = 2048;
/** Max length for an exception message. */
export const MAX_MESSAGE_LEN = 1024; // 1KB
/** Max length for an exception stacktrace. */
export const MAX_STACKTRACE_LEN = 8192; // 8KB

function truncate(value: string, max: number): string {
  if (value.length <= max) return value;
  return `${value.slice(0, max)}…[truncated]`;
}

/**
 * Strip query string + fragment from a URL (they routinely carry
 * `?token=`, `?sig=`, session ids), keeping origin + path, and cap length.
 * Best-effort: if the input isn't a parseable URL we fall back to a manual
 * `?`/`#` split so we never leak a query string just because parsing failed.
 */
export function sanitizeUrl(url: string): string {
  if (typeof url !== 'string' || url.length === 0) return '';
  let cleaned = url;
  const queryIdx = cleaned.search(/[?#]/);
  if (queryIdx >= 0) {
    cleaned = cleaned.slice(0, queryIdx);
  }
  return truncate(cleaned, MAX_URL_LEN);
}

// Obvious secret patterns. Intentionally conservative — false positives are
// cheaper than leaked credentials, but we don't try to be a full DLP engine.
const SECRET_PATTERNS: ReadonlyArray<readonly [RegExp, string]> = [
  // token=... / access_token=... in query-string or body form
  [/([?&#]|\b)((?:access_|refresh_|id_)?token|api[_-]?key|secret|password|pwd|sig|signature)=[^&\s"']+/gi, '$1$2=[REDACTED]'],
  // Authorization: Bearer <jwt-ish>
  [/\bbearer\s+[A-Za-z0-9._\-+/]+=*/gi, 'bearer [REDACTED]'],
  // long base64-ish runs (≥32 chars) that look like raw credentials
  [/\b[A-Za-z0-9_\-+/]{32,}={0,2}\b/g, '[REDACTED]'],
];

/**
 * Run a light secret scrubber over free-text telemetry (error messages,
 * stack traces) and cap its length.
 */
export function scrubText(value: string, max: number): string {
  if (typeof value !== 'string' || value.length === 0) return value ?? '';
  let scrubbed = value;
  for (const [pattern, replacement] of SECRET_PATTERNS) {
    scrubbed = scrubbed.replace(pattern, replacement);
  }
  return truncate(scrubbed, max);
}

/** Scrub + cap an exception message (≤1KB). */
export function sanitizeMessage(message: string): string {
  return scrubText(message, MAX_MESSAGE_LEN);
}

/** Scrub + cap an exception stacktrace (≤8KB). */
export function sanitizeStacktrace(stack: string): string {
  return scrubText(stack, MAX_STACKTRACE_LEN);
}
