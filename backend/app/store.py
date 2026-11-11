"""SQLite persistence: second-level snapshots, klines, news."""
from __future__ import annotations

import sqlite3
import threading
import time
from datetime import datetime, timedelta, timezone
from typing import Any, Iterable

from .config import DB_PATH, SNAPSHOT_INTERVAL

# Market timestamps from Sina are Beijing time.
CST = timezone(timedelta(hours=8))

_lock = threading.RLock()
_conn: sqlite3.Connection | None = None


def _connect() -> sqlite3.Connection:
    global _conn
    if _conn is None:
        _conn = sqlite3.connect(DB_PATH, check_same_thread=False)
        _conn.row_factory = sqlite3.Row
        _conn.execute("PRAGMA journal_mode=WAL")
        _conn.execute("PRAGMA synchronous=NORMAL")
        _init_schema(_conn)
    return _conn


def _init_schema(c: sqlite3.Connection) -> None:
    c.executescript(
        """
        CREATE TABLE IF NOT EXISTS snapshots (
            code      TEXT NOT NULL,
            ts        INTEGER NOT NULL,   -- epoch seconds
            last      REAL,
            bid       REAL,
            ask       REAL,
            volume    REAL,
            oi        REAL,
            PRIMARY KEY (code, ts)
        );
        CREATE INDEX IF NOT EXISTS idx_snap_code_ts ON snapshots(code, ts);

        CREATE TABLE IF NOT EXISTS kline_min (
            code TEXT NOT NULL,
            ts   TEXT NOT NULL,
            o REAL, h REAL, l REAL, c REAL, v REAL,
            PRIMARY KEY (code, ts)
        );
        CREATE INDEX IF NOT EXISTS idx_kmin_code_ts ON kline_min(code, ts);

        CREATE TABLE IF NOT EXISTS kline_day (
            code TEXT NOT NULL,
            ts   TEXT NOT NULL,
            o REAL, h REAL, l REAL, c REAL, v REAL, oi REAL,
            PRIMARY KEY (code, ts)
        );

        CREATE TABLE IF NOT EXISTS news (
            id      TEXT PRIMARY KEY,
            source  TEXT,
            title   TEXT,
            url     TEXT,
            ts      TEXT,
            summary TEXT,
            codes   TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_news_ts ON news(ts DESC);
        """
    )
    c.commit()


def init() -> None:
    with _lock:
        _connect()


# ---------------------------------------------------------------- snapshots

def add_snapshot(code: str, quote: dict[str, Any], ts: int | None = None) -> None:
    ts = int(ts if ts is not None else time.time())
    with _lock:
        c = _connect()
        c.execute(
            "INSERT OR REPLACE INTO snapshots(code,ts,last,bid,ask,volume,oi)"
            " VALUES(?,?,?,?,?,?,?)",
            (code, ts, quote.get("last"), quote.get("bid"), quote.get("ask"),
             quote.get("volume"), quote.get("open_interest")),
        )
        c.commit()


def snapshot_range(code: str, limit: int = 3600) -> list[dict]:
    with _lock:
        c = _connect()
        rows = c.execute(
            "SELECT ts,last,bid,ask,volume,oi FROM snapshots"
            " WHERE code=? ORDER BY ts DESC LIMIT ?",
            (code, limit),
        ).fetchall()
    return [dict(r) for r in reversed(rows)]


def snapshot_count(code: str) -> int:
    with _lock:
        c = _connect()
        n = c.execute("SELECT COUNT(*) FROM snapshots WHERE code=?", (code,)).fetchone()[0]
    return int(n)


def tick_series(code: str, bucket: int = 0, limit: int = 600) -> list[dict]:
    """Aggregate the snapshot archive into a price series for the chart.

    bucket <= 0 returns raw capture points (one per snapshot); bucket > 0
    groups them into fixed time buckets and emits OHLC. Timestamps are epoch
    seconds truncated to the bucket start so the x-axis stays stable between
    refreshes. Only local captures exist here: public APIs expose no
    second-level history, so this series grows with service uptime.
    """
    max_pts = max(2, min(int(limit), 5000))
    step = int(bucket)
    # How many raw rows a full window needs: one capture per SNAPSHOT_INTERVAL
    # second at worst, so ceil(step / interval) rows per bucket.
    per_bucket = max(1, -(-step // max(1, SNAPSHOT_INTERVAL))) if step > 0 else 1
    rows_needed = min(max_pts * per_bucket + per_bucket, 200000)
    with _lock:
        c = _connect()
        rows = c.execute(
            "SELECT ts,last FROM (SELECT ts,last FROM snapshots WHERE code=?"
            " ORDER BY ts DESC LIMIT ?) ORDER BY ts ASC",
            (code, rows_needed),
        ).fetchall()

    if step <= 0:
        return [{"ts": int(r["ts"]), "o": r["last"], "h": r["last"],
                 "l": r["last"], "c": r["last"], "n": 1}
                for r in rows if r["last"] is not None]

    out: list[dict] = []
    cur = None
    for r in rows:
        if r["last"] is None:
            continue
        b = (int(r["ts"]) // step) * step
        if cur is None or cur["ts"] != b:
            if cur is not None:
                out.append(cur)
            cur = {"ts": b, "o": r["last"], "h": r["last"],
                   "l": r["last"], "c": r["last"], "n": 1}
        else:
            cur["h"] = max(cur["h"], r["last"])
            cur["l"] = min(cur["l"], r["last"])
            cur["c"] = r["last"]
            cur["n"] += 1
    if cur is not None:
        out.append(cur)
    return out[-max_pts:]


# ------------------------------------------------------------------ klines

def upsert_minute(code: str, bars: Iterable[dict]) -> int:
    rows = [
        (code, b.get("t") or b.get("ts"), b.get("o"), b.get("h"),
         b.get("l"), b.get("c"), b.get("v"))
        for b in bars
        if (b.get("t") or b.get("ts")) and b.get("c") is not None
    ]
    if not rows:
        return 0
    with _lock:
        c = _connect()
        c.executemany(
            "INSERT OR REPLACE INTO kline_min(code,ts,o,h,l,c,v) VALUES(?,?,?,?,?,?,?)",
            rows,
        )
        c.commit()
    return len(rows)


def upsert_daily(code: str, bars: Iterable[dict]) -> int:
    rows = [
        (code, b.get("t") or b.get("ts"), b.get("o"), b.get("h"),
         b.get("l"), b.get("c"), b.get("v"), b.get("oi"))
        for b in bars
        if (b.get("t") or b.get("ts")) and b.get("c") is not None
    ]
    if not rows:
        return 0
    with _lock:
        c = _connect()
        c.executemany(
            "INSERT OR REPLACE INTO kline_day(code,ts,o,h,l,c,v,oi)"
            " VALUES(?,?,?,?,?,?,?,?)",
            rows,
        )
        c.commit()
    return len(rows)


def get_minute(code: str, limit: int = 1000) -> list[dict]:
    with _lock:
        c = _connect()
        rows = c.execute(
            "SELECT ts,o,h,l,c,v FROM kline_min WHERE code=?"
            " ORDER BY ts DESC LIMIT ?",
            (code, limit),
        ).fetchall()
    return [dict(r) for r in reversed(rows)]


def get_daily(code: str, limit: int = 4000) -> list[dict]:
    with _lock:
        c = _connect()
        rows = c.execute(
            "SELECT ts,o,h,l,c,v,oi FROM kline_day WHERE code=?"
            " ORDER BY ts DESC LIMIT ?",
            (code, limit),
        ).fetchall()
    return [dict(r) for r in reversed(rows)]


def minute_count(code: str) -> int:
    with _lock:
        c = _connect()
        return int(c.execute(
            "SELECT COUNT(*) FROM kline_min WHERE code=?", (code,)).fetchone()[0])


def daily_count(code: str) -> int:
    with _lock:
        c = _connect()
        return int(c.execute(
            "SELECT COUNT(*) FROM kline_day WHERE code=?", (code,)).fetchone()[0])


# -------------------------------------------------------------------- news

def upsert_news(items: Iterable[dict]) -> int:
    rows = [
        (n["id"], n.get("source"), n.get("title"), n.get("url"),
         n.get("ts"), n.get("summary"), n.get("codes"))
        for n in items
        if n.get("id") and n.get("title")
    ]
    if not rows:
        return 0
    with _lock:
        c = _connect()
        c.executemany(
            "INSERT OR REPLACE INTO news(id,source,title,url,ts,summary,codes)"
            " VALUES(?,?,?,?,?,?,?)",
            rows,
        )
        c.commit()
    return len(rows)


def get_news(limit: int = 50, code: str | None = None) -> list[dict]:
    with _lock:
        c = _connect()
        if code:
            rows = c.execute(
                "SELECT * FROM news WHERE codes LIKE ? ORDER BY ts DESC LIMIT ?",
                (f"%{code}%", limit),
            ).fetchall()
        else:
            rows = c.execute(
                "SELECT * FROM news ORDER BY ts DESC LIMIT ?", (limit,)
            ).fetchall()
    return [dict(r) for r in rows]
