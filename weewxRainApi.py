#!/usr/bin/env python3
"""
weewxRainApi: small read-only HTTP service exposing rainfall totals and
per-bin time series from a weewx SQLite database.

Endpoints:
  GET /rain    -> JSON totals + series for last 24h / 7d / ~month / ~year
  GET /health  -> {"ok": true}

Response shape:
  {
    "generatedAt":    <unix seconds when this payload was built>,
    "latestArchive":  <unix seconds of newest archive record, or null>,
    "earliestArchive": <unix seconds of oldest archive record, or null>,
    "totals": {
      "last24h":   {"mm": X, "inches": Y},
      "lastWeek":  {...},
      "lastMonth": {...},
      "lastYear":  {...}
    },
    "series": {
      "last24h":   {"binSec": 900,    "binsIn": [N floats]},   # 96 bins
      "lastWeek":  {"binSec": 3600,   "binsIn": [N floats]},   # 168 bins
      "lastMonth": {"binSec": 86400,  "binsIn": [N floats]},   # 30 bins
      "lastYear":  {"binSec": 604800, "binsIn": [N floats]}    # 52 bins
    }
  }

  Each series has binsIn[0] = oldest bin, binsIn[-1] = newest bin,
  which ends at generatedAt. Bins are rolling and anchored to "now"
  (not clock-aligned). Per-window totals are the sum of that window's
  binsIn array (so the chart title and the series match exactly).

SAFETY
------
The weewx database is opened in SQLite read-only URI mode (`mode=ro`)
with `PRAGMA query_only = ON`. This process will never write to,
truncate, or re-initialise the DB.

WAL-MODE NOTE
-------------
weewx typically runs SQLite in WAL mode. A read-only connection still
needs to touch the `-shm` / `-wal` sidecar files in the DB directory.
Run this service as the same user that weewx runs as so those files are
readable. Sample systemd unit:

    [Unit]
    Description=weewx rain API
    After=network.target

    [Service]
    User=weewx
    ExecStart=/usr/bin/python3 /opt/weewxRainApi/weewxRainApi.py
    Restart=on-failure

    [Install]
    WantedBy=multi-user.target
"""
import json
import sqlite3
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

DB_PATH = "/var/lib/weewx/weewx.sdb"
HOST = "0.0.0.0"
PORT = 8765

# weewx usUnits codes (see weewx.units)
UNITS_US = 1        # rain in inches
UNITS_METRIC = 16   # rain in cm
UNITS_METRICWX = 17 # rain in mm

# Conversion factor from each usUnits code into millimetres.
MM_PER_UNIT = {
    UNITS_US: 25.4,       # inches -> mm
    UNITS_METRIC: 10.0,   # cm     -> mm
    UNITS_METRICWX: 1.0,  # mm     -> mm
}

# One entry per chart. binSec = width of a bar in seconds;
# binCount = number of bars (also determines the chart's total span).
SERIES_SPECS = [
    ("last24h",   900,     96),   # 96 x 15 min  = 24.00 h
    ("lastWeek",  3600,    168),  # 168 x 1 h    = 7.00 d
    ("lastMonth", 86400,   30),   # 30 x 1 d     = 30 d (~month)
    ("lastYear",  604800,  52),   # 52 x 7 d     = 364 d (~year)
]


def openDbReadOnly(path):
    uri = f"file:{path}?mode=ro"
    conn = sqlite3.connect(uri, uri=True, timeout=5.0)
    conn.execute("PRAGMA query_only = ON")
    return conn


def rainSeriesInches(conn, nowTs, binSec, binCount):
    """Return a list of length `binCount` giving inches of rain per bin,
    with index 0 = oldest, index -1 = newest (ending at nowTs).

    Records whose usUnits code isn't recognised are silently skipped and
    returned as the second element, `unknownUnits`, so the caller can
    surface a diagnostic."""
    startTs = nowTs - binCount * binSec
    bins = [0.0] * binCount
    unknownUnits = set()

    # Group rows by (bin index, usUnits). One row per group reduces the
    # per-record Python work to a handful of rows even for the year chart.
    cur = conn.execute(
        """
        SELECT CAST((dateTime - ?) / ? AS INTEGER) AS bin,
               usUnits,
               SUM(rain) AS total
        FROM archive
        WHERE dateTime > ? AND dateTime <= ? AND rain IS NOT NULL
        GROUP BY bin, usUnits
        """,
        (startTs, binSec, startTs, nowTs),
    )
    for binIdx, usUnits, total in cur:
        if total is None:
            continue
        if binIdx < 0 or binIdx >= binCount:
            # Shouldn't happen given the WHERE clause, but guard anyway.
            continue
        factor = MM_PER_UNIT.get(usUnits)
        if factor is None:
            unknownUnits.add(usUnits)
            continue
        # Convert native units -> mm -> inches, then accumulate.
        bins[binIdx] += total * factor / 25.4

    return bins, unknownUnits


def buildPayload():
    nowTs = int(time.time())
    with openDbReadOnly(DB_PATH) as conn:
        latest = conn.execute("SELECT MAX(dateTime) FROM archive").fetchone()[0]
        earliest = conn.execute("SELECT MIN(dateTime) FROM archive").fetchone()[0]

        series = {}
        totals = {}
        unknownAll = set()
        for name, binSec, binCount in SERIES_SPECS:
            binsIn, unknown = rainSeriesInches(conn, nowTs, binSec, binCount)
            unknownAll.update(unknown)
            totalIn = sum(binsIn)
            # Round per-bin to 4 dp so the JSON stays compact without
            # losing meaningful precision (0.0001 in = 0.0025 mm).
            series[name] = {
                "binSec":  binSec,
                "binsIn":  [round(v, 4) for v in binsIn],
            }
            totals[name] = {
                "mm":      round(totalIn * 25.4, 3),
                "inches":  round(totalIn, 4),
            }

    payload = {
        "generatedAt":    nowTs,
        "latestArchive":  latest,
        "earliestArchive": earliest,
        "totals":         totals,
        "series":         series,
    }
    if unknownAll:
        payload["unknownUsUnits"] = sorted(unknownAll)
    return payload


class RainHandler(BaseHTTPRequestHandler):
    def sendJson(self, code, obj):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/rain":
            try:
                self.sendJson(200, buildPayload())
            except Exception as e:
                self.sendJson(500, {"error": type(e).__name__, "detail": str(e)})
        elif path == "/health":
            self.sendJson(200, {"ok": True})
        else:
            self.sendJson(404, {"error": "not found"})

    def log_message(self, fmt, *args):
        return


def main():
    server = ThreadingHTTPServer((HOST, PORT), RainHandler)
    print(f"weewxRainApi listening on {HOST}:{PORT}, db={DB_PATH}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
