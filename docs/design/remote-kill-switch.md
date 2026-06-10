# Design: Remote Kill Switch & Global Sampling

**Status:** Implemented (feat/remote-kill-switch)
**Platforms:** Android (native), iOS (native). React Native is covered transitively — see §6.

## Problem

A telemetry SDK that misbehaves in production (excessive capture, export storms, a bad instrumentation interaction) currently has no remote off-switch — the only lever is an app redeploy. SecOps/SRE adopters require the ability to remotely **disable** or **throttle** the SDK fleet-wide without shipping a new app build. Today the remote config DSL only tunes flush policies; there is no master enable flag or global sampling override delivered over the wire.

## Wire contract

Additive, backward-compatible extension to the existing `GET <endpoint>/config?dsl_version=2` response root:

```json
{
  "version": 2,
  "sdk": { "enabled": true, "sample_rate": 1.0 },
  "workflows": [ ... ]
}
```

- `sdk` — **optional** object at the JSON root, sibling of `workflows`/`version`.
- `sdk.enabled` — boolean, default **true**. The master kill switch.
- `sdk.sample_rate` — number, default **1.0**, **clamped to [0.0, 1.0]**. Global head-sampling fraction.

## Semantics

| Field | Effect |
|---|---|
| `enabled = false` | Drop **all** new telemetry at the emission choke points — **logs (`onEmit`) AND spans (sampler)**. Skip export scheduling for new data. Polling continues so the SDK can be re-enabled remotely. Pre-existing buffered data is left to age out by its normal TTL — we do **not** force-flush or wipe it. |
| `sample_rate < 1.0` | Probabilistic per-event drop applied **uniformly to logs and spans**. For spans, fold into the existing sampler rate; for logs, drop in `onEmit` before buffering. |
| `enabled = true`, `sample_rate = 1.0` | Normal operation (the default). |

### Fail-open & precedence rules (critical — get these exactly right)

1. **Never-fetched / fetch failure / network error** → keep the **last applied** value. Do not reset to default on a transient poll failure. (If previously disabled, stay disabled; if never set, default = enabled/1.0.)
2. **Config parses but `sdk` block is ABSENT** → treat as **enabled = true, sample_rate = 1.0** (absence = "no restriction"). Rationale: an operator re-enables the fleet by *removing* the `sdk` directive or sending `enabled:true`. Absence must not mean "keep disabled forever."
3. **Malformed `sdk` block** (wrong types) → ignore the malformed field, apply defaults for it, never crash.
4. The kill switch must **never** crash, block, or throw into the host. A failure to apply config leaves the SDK in its prior state.

## Polling defaults (so the switch works out of the box)

- **Android:** `remoteConfigEnabled` already defaults `true` — no change.
- **iOS:** flip `enablePolicyPolling` default `false → true`. This is an intentional behavior change: the SDK now polls config by default so the kill switch is functional without opt-in. Documented as such. Poll interval stays 300s; ephemeral session; 15s timeout; existing exponential backoff on failure.

## Self-telemetry (so operators can SEE the state)

Both platforms emit, alongside the existing gauges:

- `sdk.enabled` — long gauge, `1` when enabled, `0` when remotely disabled.
- `sdk.sample_rate` — double gauge, the currently-applied global sample rate.

On iOS these are the first SDK-state gauges (iOS previously emitted only `device.*`). They are emitted even while `enabled=false` (the gauge path is exempt from the kill switch — operators must be able to observe a disabled SDK; this is a deliberate, bounded exception and the only telemetry that flows when disabled).

## Implementation shape

A single small thread-safe holder per platform — call it `RemoteGate` — stores `(enabled, sampleRate)` and is consulted at:

1. **Log choke point** — `MobileLogRecordProcessor.onEmit` (both platforms): early-return drop when `!enabled`; probabilistic drop when `sampleRate < 1`. Placed *before* buffering/coalescing work so a disabled SDK does no work.
2. **Span choke point** — the dynamic sampler (`DynamicSampler.shouldSample` on Android; the equivalent sampler/trace path on iOS): `!enabled` ⇒ `DROP`; otherwise fold `sampleRate` into the effective rate.
3. **Config apply** — wherever fetched config is applied (`PolicyEvaluator.fetchConfig`/`applyConfig` on Android; `ConfigPoller.applyConfig` on iOS): parse the `sdk` block and push into `RemoteGate`.

The gate is read once per event with a cheap volatile/lock-free read; per-event sampling uses a non-biased RNG. No allocation on the hot path.

## React Native (§6)

RN-originated telemetry (fetch/XHR spans, error/tap/screen logs) is emitted via `OTelMobileCallSink` → the **same** native OTel logger/tracer → the **same** `MobileLogRecordProcessor.onEmit` and sampler. Therefore the native gate covers RN with **no RN-side code change**. A regression test asserts that a bridge-emitted log is dropped when the gate is disabled.
