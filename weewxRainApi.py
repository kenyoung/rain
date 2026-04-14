#!/usr/bin/env python3
"""
weewxRainApi: small read-only HTTP service exposing rainfall totals from a
weewx SQLite database.

Endpoints:
  GET /rain    -> JSON totals for last 24h, 7d, 30.43685d, 365.25d
  GET /health  -> {"ok": true}

SAFETY
------
The weewx database is opened in SQLite read-only URI mode (`mode=ro`).
This process will never write to, truncate, or re-initialise the DB.

WAL-MODE NOTE
-------------
weewx typically runs SQLite in WAL mode. A read-only connection still needs
to touch the `-shm` / `-wal` sidecar files in the DB directory. The safest
way to run this service is as the same user that weewx runs as (often
`weewx` or `root` depending on install), so the sidecar files already exist
and are readable. If you see "unable to open database file" errors under
WAL mode, run this service as the weewx user, e.g. via a systemd unit:

    [Unit]
    Description=weewx rain API
    After=network.target

    [Service]
    User=weewx
    ExecStart=/usr/bin/python3 /opt/weewxRainApi/weewxRainApi.py
    Restart=on-failure

    [Install]
    WantedBy=multi-user.target

Edit DB_PATH below if your weewx database lives somewhere other than the
Debian-package default.
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

SECONDS_PER_DAY = 86400
WINDOWS = {
    "last24h":    1.0        * SECONDS_PER_DAY,
    "lastWeek":   7.0        * SECONDS_PER_DAY,
    "lastMonth":  30.43685   * SECONDS_PER_DAY,
    "lastYear":   365.25     * SECONDS_PER_DAY,
}


def openDbReadOnly(path):
    # The file: URI plus mode=ro guarantees the connection is read-only;
    # any attempted write raises SQLITE_READONLY.
    uri = f"file:{path}?mode=ro"
    conn = sqlite3.connect(uri, uri=True, timeout=5.0)
    # Extra belt-and-braces: set a read-only PRAGMA too. (mode=ro already
    # enforces this, but this makes intent obvious.)
    conn.execute("PRAGMA query_only = ON")
    return conn


def rainTotalMm(conn, sinceTs, untilTs):
    """Sum `rain` in the archive table between sinceTs and untilTs,
    converting per-record to millimetres based on that record's usUnits."""
    cur = conn.execute(
        """
        SELECT usUnits, SUM(rain)
        FROM archive
        WHERE dateTime > ? AND dateTime <= ? AND rain IS NOT NULL
        GROUP BY usUnits
        """,
        (sinceTs, untilTs),
    )
    totalMm = 0.0
    unknownUnits = []
    for usUnits, s in cur:
        if s is None:
            continue
        if usUnits == UNITS_US:
            totalMm += s * 25.4       # inches -> mm
        elif usUnits == UNITS_METRIC:
            totalMm += s * 10.0       # cm -> mm
        elif usUnits == UNITS_METRICWX:
            totalMm += s              # already mm
        else:
            unknownUnits.append(usUnits)
    return totalMm, unknownUnits


def buildPayload():
    nowTs = int(time.time())
    with openDbReadOnly(DB_PATH) as conn:
        latest = conn.execute("SELECT MAX(dateTime) FROM archive").fetchone()[0]
        earliest = conn.execute("SELECT MIN(dateTime) FROM archive").fetchone()[0]
        totals = {}
        warnings = set()
        for name, span in WINDOWS.items():
            mm, unknown = rainTotalMm(conn, nowTs - span, nowTs)
            totals[name] = {
                "mm":     round(mm, 3),
                "inches": round(mm / 25.4, 4),
            }
            warnings.update(unknown)
    payload = {
        "generatedAt":    nowTs,
        "latestArchive":  latest,
        "earliestArchive": earliest,
        "totals":         totals,
    }
    if warnings:
        payload["unknownUsUnits"] = sorted(warnings)
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
        # Keep stdout quiet; uncomment for debugging:
        # super().log_message(fmt, *args)
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
