#!/usr/bin/env python3
"""
testRainApi: desktop test client for the weewxRainApi service running on
the Raspberry Pi.

Usage:
    ./testRainApi.py <host>[:port]

Examples:
    ./testRainApi.py raspberrypi.local
    ./testRainApi.py 192.168.1.102
    ./testRainApi.py 192.168.1.102:8765
"""
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

DEFAULT_PORT = 8765

# (name, expectedBinSec, expectedBinCount) -- used to sanity-check the
# server's response against the spec.
EXPECTED_SERIES = [
    ("last24h",   900,    96),
    ("lastWeek",  3600,   168),
    ("lastMonth", 86400,  30),
    ("lastYear",  604800, 52),
]

TOLERANCE_INCHES = 1e-3


def fetchJson(url, timeoutSec=10):
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeoutSec) as resp:
        raw = resp.read()
        return resp.status, json.loads(raw.decode("utf-8"))


def fmtTs(ts):
    if ts is None:
        return "(none)"
    dt = datetime.fromtimestamp(ts, tz=timezone.utc).astimezone()
    return dt.isoformat(timespec="seconds")


def checkSeries(data):
    """Print series summaries and sanity-check each series against the
    expected shape and against totals[name].inches. Returns the number
    of problems found."""
    problems = 0
    series = data.get("series")
    totals = data.get("totals", {})
    if not isinstance(series, dict):
        print("  FAIL: no 'series' object in response")
        return 1

    print(f"  {'window':<10s} {'binSec':>8s} {'#bins':>6s} "
          f"{'min':>8s} {'max':>8s} {'sum':>10s} {'total':>10s} {'match':>6s}")
    for name, expectedBinSec, expectedBinCount in EXPECTED_SERIES:
        s = series.get(name)
        if s is None:
            print(f"  {name:<10s} MISSING")
            problems += 1
            continue
        binSec = s.get("binSec")
        bins = s.get("binsIn", [])
        if binSec != expectedBinSec:
            print(f"  {name:<10s} binSec mismatch: got {binSec}, want {expectedBinSec}")
            problems += 1
        if len(bins) != expectedBinCount:
            print(f"  {name:<10s} length mismatch: got {len(bins)}, want {expectedBinCount}")
            problems += 1

        sSum = sum(bins) if bins else 0.0
        sMin = min(bins) if bins else 0.0
        sMax = max(bins) if bins else 0.0
        tInches = totals.get(name, {}).get("inches", float("nan"))
        match = abs(sSum - tInches) <= TOLERANCE_INCHES
        if not match:
            problems += 1
        print(f"  {name:<10s} {binSec:>8d} {len(bins):>6d} "
              f"{sMin:>8.3f} {sMax:>8.3f} {sSum:>10.4f} {tInches:>10.4f} "
              f"{'ok' if match else 'FAIL':>6s}")

    return problems


def main(argv):
    if len(argv) < 2:
        print("usage: testRainApi.py <host>[:port]", file=sys.stderr)
        return 2
    target = argv[1]
    if ":" not in target:
        target = f"{target}:{DEFAULT_PORT}"
    base = f"http://{target}"

    # Health check.
    healthUrl = f"{base}/health"
    print(f"GET {healthUrl}")
    try:
        status, health = fetchJson(healthUrl)
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        print(f"  FAILED: {e}", file=sys.stderr)
        return 1
    print(f"  status={status} body={health}")

    # Rain.
    rainUrl = f"{base}/rain"
    print(f"\nGET {rainUrl}")
    t0 = time.monotonic()
    try:
        status, data = fetchJson(rainUrl)
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        print(f"  FAILED: {e}", file=sys.stderr)
        return 1
    elapsedMs = (time.monotonic() - t0) * 1000.0
    print(f"  status={status}  elapsed={elapsedMs:.0f} ms  "
          f"bytes={len(json.dumps(data))}")

    if status != 200 or "totals" not in data:
        print("\nUnexpected response:", file=sys.stderr)
        print(json.dumps(data, indent=2))
        return 1

    print("\n--- Summary ---")
    print(f"Server time:       {fmtTs(data.get('generatedAt'))}")
    print(f"Earliest archive:  {fmtTs(data.get('earliestArchive'))}")
    print(f"Latest archive:    {fmtTs(data.get('latestArchive'))}")

    latest = data.get("latestArchive")
    if latest is not None:
        ageSec = data["generatedAt"] - latest
        print(f"Latest record age: {ageSec/60:.1f} min")

    print("\nRainfall totals:")
    print(f"  {'window':<10s} {'mm':>10s}  {'inches':>10s}")
    for name, _, _ in EXPECTED_SERIES:
        v = data["totals"].get(name, {})
        mm = v.get("mm", float("nan"))
        inches = v.get("inches", float("nan"))
        print(f"  {name:<10s} {mm:>10.2f}  {inches:>10.3f}")

    print("\nSeries (sum-vs-total sanity check):")
    problems = checkSeries(data)

    if "unknownUsUnits" in data:
        print(f"\nWARNING: unknown usUnits codes in records: "
              f"{data['unknownUsUnits']}")

    if problems:
        print(f"\n{problems} series problem(s).")
        return 1
    print("\nAll series checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
