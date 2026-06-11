#!/usr/bin/env python3
"""
dash0_assert.py — Assert that expected telemetry actually arrived in Dash0.

The gate behind "tests are not green unless telemetry is in Dash0". Unlike the
Dash0-CLI-based validators, this talks to the Dash0 REST API directly (curl-
equivalent via urllib), so it runs in any environment with just Python 3 + a
read-capable DASH0_AUTH_TOKEN — no `dash0` binary required.

It queries logs/spans for a time window, groups by service.name client-side
(robust, no dependence on server filter operators), and asserts that each
required log body / span name / metric is present at >= a minimum count for the
target service. Exits 0 if every requirement is met, 1 otherwise — so it drops
straight into a CI step or an E2E script as a pass/fail gate.

Env:
  DASH0_AUTH_TOKEN   (required) read-capable token
  DASH0_API_HOST     default api.us-west-2.aws.dash0.com
  DASH0_DATASET      default otel-mobile

Examples:
  # Android demo: normal signals present in the last 15 min
  dash0_assert.py --service otel-mobile-demo --window-min 15 \
      --log app.start --log ui.tap --span screen.render

  # Crash landed
  dash0_assert.py --service otel-mobile-demo --window-min 10 --log app.crash

  # Offline events flushed (>= 5 ui.tap)
  dash0_assert.py --service otel-mobile-demo --window-min 10 --log ui.tap:5

  # A device metric has recent datapoints
  dash0_assert.py --service otel-mobile-demo --metric device_battery_level_percent
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import quote
from collections import Counter

API_HOST = os.environ.get("DASH0_API_HOST", "api.us-west-2.aws.dash0.com")
DATASET = os.environ.get("DASH0_DATASET", "otel-mobile")
TOKEN = os.environ.get("DASH0_AUTH_TOKEN", "")


def _post(path, body):
    req = urllib.request.Request(
        f"https://{API_HOST}{path}",
        data=json.dumps(body).encode(),
        headers={
            "Authorization": f"Bearer {TOKEN}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode())


def _get(path):
    req = urllib.request.Request(
        f"https://{API_HOST}{path}",
        headers={"Authorization": f"Bearer {TOKEN}"},
        method="GET",
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode())


def _iso(ts):
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(ts))


def _service_of(resource):
    for a in resource.get("attributes", []):
        if a.get("key") == "service.name":
            return next(iter(a.get("value", {}).values()), None)
    return None


def _body_of(lr):
    b = lr.get("body", {})
    if isinstance(b, dict):
        v = b.get("stringValue")
        if v is not None:
            return v
    # fall back to event.name attribute
    for a in lr.get("attributes", []):
        if a.get("key") == "event.name":
            return next(iter(a.get("value", {}).values()), None)
    return None


def count_logs(service, from_ts, to_ts):
    data = _post("/api/logs", {"dataset": DATASET,
                               "timeRange": {"from": _iso(from_ts), "to": _iso(to_ts)}})
    c = Counter()
    for rl in data.get("resourceLogs", []):
        if service and _service_of(rl.get("resource", {})) != service:
            continue
        for sl in rl.get("scopeLogs", []):
            for lr in sl.get("logRecords", []):
                c[_body_of(lr)] += 1
    return c


def count_spans(service, from_ts, to_ts):
    data = _post("/api/spans", {"dataset": DATASET,
                                "timeRange": {"from": _iso(from_ts), "to": _iso(to_ts)}})
    c = Counter()
    for rs in data.get("resourceSpans", []):
        if service and _service_of(rs.get("resource", {})) != service:
            continue
        for ss in rs.get("scopeSpans", []):
            for sp in ss.get("spans", []):
                c[sp.get("name")] += 1
    return c


def metric_has_data(name):
    path = f"/api/prometheus/api/v1/query?query={quote(name)}&dataset={quote(DATASET)}"
    data = _get(path)
    return bool(data.get("data", {}).get("result"))


def _parse_reqs(items):
    """'ui.tap:5' -> ('ui.tap', 5);  'app.start' -> ('app.start', 1)"""
    out = []
    for it in items or []:
        name, _, n = it.partition(":")
        out.append((name, int(n) if n else 1))
    return out


def main():
    p = argparse.ArgumentParser(description="Assert telemetry is present in Dash0.")
    p.add_argument("--service", help="service.name to scope to (exact match)")
    p.add_argument("--window-min", type=float, default=15, help="look-back window in minutes")
    p.add_argument("--log", action="append", default=[], metavar="BODY[:MIN]",
                   help="required log body, optional :minCount (default 1). Repeatable.")
    p.add_argument("--span", action="append", default=[], metavar="NAME[:MIN]",
                   help="required span name, optional :minCount. Repeatable.")
    p.add_argument("--metric", action="append", default=[], metavar="NAME",
                   help="required metric name (must have recent datapoints). Repeatable.")
    p.add_argument("--label", default="", help="prefix label for output")
    args = p.parse_args()

    if not TOKEN:
        print("ERROR: DASH0_AUTH_TOKEN not set", file=sys.stderr)
        return 2

    now = time.time()
    frm = now - args.window_min * 60
    pre = f"[{args.label}] " if args.label else ""
    print(f"{pre}Dash0 check — service={args.service or '*'} dataset={DATASET} "
          f"window={args.window_min:g}m")

    failures = 0
    try:
        if args.log:
            logs = count_logs(args.service, frm, now)
            for name, need in _parse_reqs(args.log):
                got = logs.get(name, 0)
                ok = got >= need
                failures += not ok
                print(f"  {'PASS' if ok else 'FAIL'}: log  {name} >= {need}  (got {got})")
        if args.span:
            spans = count_spans(args.service, frm, now)
            for name, need in _parse_reqs(args.span):
                got = spans.get(name, 0)
                ok = got >= need
                failures += not ok
                print(f"  {'PASS' if ok else 'FAIL'}: span {name} >= {need}  (got {got})")
        for name in args.metric:
            ok = metric_has_data(name)
            failures += not ok
            print(f"  {'PASS' if ok else 'FAIL'}: metric {name} has datapoints")
    except urllib.error.HTTPError as e:
        body = e.read().decode()[:200]
        print(f"ERROR: Dash0 API {e.code}: {body}", file=sys.stderr)
        return 2
    except Exception as e:  # noqa: BLE001 - surface any query failure as a hard error
        print(f"ERROR: {type(e).__name__}: {e}", file=sys.stderr)
        return 2

    if failures:
        print(f"{pre}RESULT: FAIL ({failures} missing) — telemetry not confirmed in Dash0")
        return 1
    print(f"{pre}RESULT: PASS — all expected telemetry present in Dash0")
    return 0


if __name__ == "__main__":
    sys.exit(main())
