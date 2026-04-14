#!/usr/bin/env python3
"""
testRainApi: desktop test client for the weewxRainApi service running on
the Raspberry Pi.

Usage:
    ./testRainApi.py <host>[:port]

Examples:
    ./testRainApi.py raspberrypi.local
    ./testRainApi.py 192.168.1.42
    ./testRainApi.py 192.168.1.42:8765
"""
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

DEFAULT_PORT = 8765


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

    # Rain totals.
    rainUrl = f"{base}/rain"
    print(f"\nGET {rainUrl}")
    t0 = time.monotonic()
    try:
        status, data = fetchJson(rainUrl)
    except (urllib.error.URLError, TimeoutError, OSError) as e:
        print(f"  FAILED: {e}", file=sys.stderr)
        return 1
    elapsedMs = (time.monotonic() - t0) * 1000.0
    print(f"  status={status}  elapsed={elapsedMs:.0f} ms")

    print("\nRaw JSON:")
    print(json.dumps(data, indent=2))

    if status != 200 or "totals" not in data:
        print("\nUnexpected response.", file=sys.stderr)
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
    for name in ("last24h", "lastWeek", "lastMonth", "lastYear"):
        v = data["totals"].get(name, {})
        mm = v.get("mm", float("nan"))
        inches = v.get("inches", float("nan"))
        print(f"  {name:<10s} {mm:>10.2f}  {inches:>10.3f}")

    if "unknownUsUnits" in data:
        print(f"\nWARNING: encountered unknown usUnits codes: "
              f"{data['unknownUsUnits']}")

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
