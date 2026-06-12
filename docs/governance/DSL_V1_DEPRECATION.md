# DSL v1 Deprecation Timeline

**Status:** Deprecated — v2 is the default  
**Created:** 2026-05-07  

## Background

DSL v1 was the original export policy format. DSL v2 added 21 matcher types, 10 action types, and `extraResourceAttributes`. The SDK auto-detects the format version and supports both.

## Timeline

| Milestone | Target Date | Action |
|-----------|-------------|--------|
| v2 default | 2026-04-14 | **DONE** — SDK auto-detects v1/v2, negotiates v2 with gateway |
| v1 deprecation warning | 0.3.1-alpha | SDK logs warning when v1 config is loaded |
| Control plane v1 compiler removal | 0.4.0-alpha | `graphToDSLv1.ts` removed from control plane UI |
| SDK v1 parser removal | 1.0.0 | v1 parsing code removed from Android + iOS SDKs |

## Migration Guide

**For gateway operators:** No action needed. The gateway stores configs in their original version and the SDK handles auto-detection.

**For direct config authors:** Convert v1 JSON to v2 by:
1. Wrapping matchers in the `matchers` array format
2. Moving actions to the `actions` array format
3. Adding `"version": 2` to the root object

See `docs/DSL_V2_SCHEMA.md` in `mobile-otel-control-plane` for the full v2 schema reference.

## What Stays

- v1 configs already stored in gateway SQLite will continue to work until SDK 1.0.0
- The auto-detection mechanism (`PolicyParser.detectVersion()`) is zero-cost for v2 configs
- No customer action required during the deprecation period
