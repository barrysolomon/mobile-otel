# Matchy-matchy — per-platform four-gate validation runbooks

One runbook per demo app / platform, all producing **comparable** Dash0
evidence so parity is observable across Android native, iOS native, RN
Android, and RN iOS. Each runbook walks the same four gates in the same
order with the same shape of query — so the output side-by-side makes
drift visible at a glance.

The gates and pass criteria are defined in
[`../epics/VALIDATION_MATRIX_EPIC.md`](../epics/VALIDATION_MATRIX_EPIC.md).
This directory is the **execution** half of that epic: the epic says
what must be true, these runbooks say exactly what to run to prove it.

## The four gates (one-line summary each)

| # | Gate | Canonical filter |
|---|---|---|
| 1 | **Lifecycle** — auto-instrumented `app.foreground` / `app.background` on bg↔fg cycles | `event.name is app.foreground` |
| 2 | **Network** — auto-captured HTTP span with `kind=CLIENT` + standard semconv | `http.request.method is GET` |
| 3 | **Crash** — fatal-severity crash/error log lands in Dash0 with exception semconv | `event.name is app.crash` or `app.error` |
| 4 | **Offline** — disk-persist on export failure, `app.recovery_start` marker on reconnect | `event.name is app.recovery_start` |

Green = verified end-to-end in Dash0 via `dash0 -X logs/spans query`
with an `--from now-5m` relative window and a timestamp *after* the
trigger. Unit tests do NOT count.

## Runbooks

| Platform | Runbook | Status |
|---|---|---|
| iOS native (AstronomyShop) | [`ios-native.md`](ios-native.md) | 🟢 all 4 gates (canonical) |
| RN iOS (AstronomyShopRN) | [`rn-ios.md`](rn-ios.md) | 🟢 Gate 2 + 3 · 🔴 Gate 1 + 4 (documented root causes) |
| Android native (demo-app, AstronomyShop Android) | [`android-native.md`](android-native.md) | 🟡 TODO |
| RN Android (AstronomyShopRN on Android host) | [`rn-android.md`](rn-android.md) | 🟡 TODO |

## Shared pre-flight (do once per session)

```bash
dash0 config profiles activate mobile-test

# Confirm the dataset is quiet before you start — any rows here will
# pollute your gate 1 count. 0 rows = clean slate.
dash0 -X logs query \
  --filter "service.name is <your-service-name>" \
  --from now-2m -o json | python3 -c 'import json,sys; print(len(json.load(sys.stdin).get("items", [])))'
```

## Template structure

Every runbook in this directory follows this shape so you can diff them:

```
0. Pre-flight — tools, env, simulator/emulator, otel-config
1. Gate 1 — Lifecycle       (trigger → expect → query → evidence)
2. Gate 2 — Network         (trigger → expect → query → evidence)
3. Gate 3 — Crash           (trigger → expect → query → evidence)
4. Gate 4 — Offline         (trigger → expect → query → evidence)
5. Known failures / architectural gaps (with commit references)
6. Session-journal pointer — which session last validated this
```

## Matchy-matchy invariants

When the same gate runs on two platforms, these must match:

- **Gate 1**: same `event.name`, same scope `io.dash0.mobile`, same interleave order
  (launch → foreground → background → foreground → ...)
- **Gate 2**: same span `name=GET`, same `kind=CLIENT`, same attribute keys
  (`http.request.method`, `server.address`, `http.response.status_code`,
  `url.full`), same scrubbing behavior
- **Gate 3**: same `severityNumber=21 (FATAL)`, same OTel `exception.*`
  semconv. RN platforms may use `app.error` where native uses
  `app.crash` — both are valid and a future alias should bridge them.
- **Gate 4**: same `app.recovery_start` marker name, same
  `dash0.recovery.event_count` attribute, same no-gap invariant
  between sequence IDs.

Anywhere a platform deviates, the runbook must say *why* with a commit
citation or link to the architectural gap.

## Why "matchy-matchy"

Parity isn't a code-review claim — it's observable or it isn't. If the
iOS native runbook lands a `GET` span with `server.address=httpbin.org`
and RN iOS lands a `GET httpbin.org` with the same `status_code=200`
from the same user action, parity is matchy-matchy. If one side is
missing an attribute, the diff tells you within seconds.
