# Security Policy

## Supported Versions

This project is pre-1.0; only the latest published release receives security
fixes.

| Version        | Supported |
| -------------- | --------- |
| latest release | ✅        |
| older releases | ❌        |

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub
issues, discussions, or pull requests.**

Instead, use one of these private channels:

1. **GitHub Private Vulnerability Reporting** (preferred): use
   ["Report a vulnerability"](https://github.com/barrysolomon/mobile-otel/security/advisories/new)
   under the repository's **Security** tab. This opens a private advisory
   visible only to you and the maintainers.
2. **Email**: barry.solomon@dash0.com — include "SECURITY" in the subject
   line.

Please include as much of the following as you can:

- Affected component (Android SDK, iOS SDK, React Native bridge, collector
  processor, demo apps) and version.
- Reproduction steps or a proof of concept.
- Impact assessment: what an attacker gains (e.g. telemetry exfiltration,
  PII exposure in captures, credential leakage).

## What to Expect

- **Acknowledgement** within 3 business days.
- **Assessment and triage** within 7 days — we will confirm the issue,
  its severity, and the affected versions.
- **Fix and disclosure**: we aim to release a fix within 30 days of
  confirmation, followed by a public advisory crediting the reporter
  (unless you prefer to remain anonymous).

## Scope Notes for Integrators

This SDK captures telemetry (logs, spans, metrics, and — when explicitly
enabled — screenshots and view-hierarchy wireframes) and exports it to the
OTLP collector endpoint the host app configures:

- Screenshot/wireframe capture is **off by default** on every platform and
  gated behind an explicit opt-in, with secure-field redaction applied.
- Cleartext (`http://`) export to non-loopback hosts is rejected unless the
  host app explicitly opts in (`allowInsecureTransport`).
- The ingest auth token is never logged by the SDK.
- The Android disk buffer is encrypted at rest (SQLCipher + Android
  Keystore).

If you find behavior contradicting any of the above, that is a security bug
— please report it via the channels above.
