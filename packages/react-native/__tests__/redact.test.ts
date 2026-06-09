import {
  sanitizeUrl,
  scrubText,
  sanitizeMessage,
  sanitizeStacktrace,
  MAX_MESSAGE_LEN,
  MAX_STACKTRACE_LEN,
} from '../src/redact';

describe('sanitizeUrl', () => {
  it('strips the query string (keeps origin + path)', () => {
    expect(sanitizeUrl('https://api.example.com/items?token=secret&q=1')).toBe(
      'https://api.example.com/items',
    );
  });

  it('strips the fragment', () => {
    expect(sanitizeUrl('https://x.com/p#access_token=abc')).toBe(
      'https://x.com/p',
    );
  });

  it('strips at the first of ? or #', () => {
    expect(sanitizeUrl('https://x.com/p?a=1#frag')).toBe('https://x.com/p');
    expect(sanitizeUrl('https://x.com/p#frag?a=1')).toBe('https://x.com/p');
  });

  it('leaves a clean URL untouched', () => {
    expect(sanitizeUrl('https://api.example.com/v1/items')).toBe(
      'https://api.example.com/v1/items',
    );
  });

  it('handles relative URLs without leaking the query', () => {
    expect(sanitizeUrl('/reset?token=secret')).toBe('/reset');
  });

  it('is throw-safe on degenerate input', () => {
    expect(sanitizeUrl('')).toBe('');
    // @ts-expect-error — defending against non-string at runtime
    expect(sanitizeUrl(undefined)).toBe('');
    // @ts-expect-error
    expect(sanitizeUrl(null)).toBe('');
    // @ts-expect-error
    expect(sanitizeUrl(42)).toBe('');
  });

  it('caps length (origin + path) at the max', () => {
    const long = 'https://x.com/' + 'a'.repeat(5000);
    const out = sanitizeUrl(long);
    expect(out.length).toBeLessThan(long.length);
    expect(out.endsWith('…[truncated]')).toBe(true);
  });
});

describe('scrubText secret patterns', () => {
  it('redacts token / api_key / secret / password query-or-form values', () => {
    expect(scrubText('GET /x?token=abc123def', 1000)).toContain(
      'token=[REDACTED]',
    );
    expect(scrubText('api_key=ABCDEFG&next=1', 1000)).toContain(
      'api_key=[REDACTED]',
    );
    expect(scrubText('password=hunter2', 1000)).toContain(
      'password=[REDACTED]',
    );
    expect(scrubText('signature=deadbeef', 1000)).toContain(
      'signature=[REDACTED]',
    );
  });

  it('redacts bearer tokens', () => {
    const out = scrubText(
      'Error: auth failed for bearer eyJhbGciOiJIUzI1NiJ9.payload.sig',
      1000,
    );
    expect(out).toContain('bearer [REDACTED]');
    expect(out).not.toContain('eyJhbGciOiJIUzI1NiJ9');
  });

  it('redacts long base64-ish credential runs', () => {
    const secret = 'A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8'; // 36 chars
    const out = scrubText(`raw key ${secret} trailing`, 1000);
    expect(out).toContain('[REDACTED]');
    expect(out).not.toContain(secret);
  });

  it('leaves benign text alone', () => {
    const benign = 'TypeError: cannot read property foo of undefined';
    expect(scrubText(benign, 1000)).toBe(benign);
  });

  it('returns empty/degenerate input safely', () => {
    expect(scrubText('', 1000)).toBe('');
    // @ts-expect-error
    expect(scrubText(undefined, 1000)).toBe('');
  });
});

describe('sanitizeMessage / sanitizeStacktrace length caps', () => {
  it('caps a message at MAX_MESSAGE_LEN', () => {
    // Filler with spaces so no 32+ char run is scrubbed away before the cap.
    const out = sanitizeMessage('the quick brown fox '.repeat(200));
    expect(out.length).toBeLessThanOrEqual(
      MAX_MESSAGE_LEN + '…[truncated]'.length,
    );
    expect(out.endsWith('…[truncated]')).toBe(true);
  });

  it('caps a stacktrace at MAX_STACKTRACE_LEN', () => {
    const out = sanitizeStacktrace('at foo (bar.js:1:2) '.repeat(1000));
    expect(out.length).toBeLessThanOrEqual(
      MAX_STACKTRACE_LEN + '…[truncated]'.length,
    );
    expect(out.endsWith('…[truncated]')).toBe(true);
  });

  it('scrubs before/while capping (a secret near the start is redacted)', () => {
    const input = 'bearer ' + 'Z'.repeat(40) + '\n' + 'frame\n'.repeat(2000);
    const out = sanitizeStacktrace(input);
    expect(out).toContain('bearer [REDACTED]');
    expect(out).not.toContain('Z'.repeat(40));
  });
});

describe('ReDoS resistance (linear-time guarantee)', () => {
  // Each SECRET_PATTERN must run in (near) linear time. These pathological
  // inputs would hang an SDK on a crashy device if any pattern backtracked
  // catastrophically. Budget is generous (200ms) to avoid CI flakiness while
  // still catching exponential blowup, which would take seconds-to-minutes.
  const BUDGET_MS = 200;

  const adversarialInputs = [
    'A'.repeat(200_000),
    'bearer ' + 'a'.repeat(100_000) + '='.repeat(50_000),
    ('x'.repeat(31) + ' ').repeat(5_000), // many sub-threshold base64 runs
    'token=' + 'b'.repeat(100_000),
    ('a=1&'.repeat(50_000)),
    '/'.repeat(100_000) + '=',
  ];

  for (const [i, input] of adversarialInputs.entries()) {
    it(`scrubText completes under ${BUDGET_MS}ms on adversarial input #${i} (len ${input.length})`, () => {
      const start = Date.now();
      scrubText(input, MAX_STACKTRACE_LEN);
      const elapsed = Date.now() - start;
      expect(elapsed).toBeLessThan(BUDGET_MS);
    });
  }
});
