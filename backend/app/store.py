"""SQLite persistence: second-level snapshots, klines, news."""
from __future__ import annotations

import sqlite3
import threading
import time
from datetime import datetime, timedelta, timezone
from typing import Any, Iterable

from .config import DB_PATH, MULTI_PERIODS, PRODUCT_BY_CODE

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

        -- 统一的多周期表：ts 为「自然时间桶起点」的 epoch 毫秒整数。
        -- 1m 原始点仍存 kline_min、日线仍存 kline_day（既有接口不变），
        -- 本表只存从 1m 按时间桶聚合出的 5/15/30/60m 派生周期。
        CREATE TABLE IF NOT EXISTS kline_multi (
            code   TEXT NOT NULL,
            period TEXT NOT NULL,
            ts     INTEGER NOT NULL,   -- epoch milliseconds (bucket start)
            o REAL, h REAL, l REAL, c REAL, v REAL,
            PRIMARY KEY (code, period, ts)
        );
        CREATE INDEX IF NOT EXISTS idx_kmulti_code_period_ts
            ON kline_multi(code, period, ts);

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

        -- 手机上传的「源站段末口径」多周期分表（双向同步专用）。
        -- 只由 /api/db/import 写入；5m/15m/30m/60m 的 /api/db/export 只读本表，
        -- 与 kline_multi（服务端自然桶聚合口径）物理隔离，绝不混入既有接口。
        -- ts 统一 UTC 毫秒整数；主键 (code,period,ts) 保证幂等。
        CREATE TABLE IF NOT EXISTS kline_app (
            code   TEXT NOT NULL,
            period TEXT NOT NULL,
            ts     INTEGER NOT NULL,   -- epoch milliseconds
            o REAL, h REAL, l REAL, c REAL, v REAL,
            source TEXT,
            PRIMARY KEY (code, period, ts)
        );
        CREATE INDEX IF NOT EXISTS idx_kapp_code_period_ts
            ON kline_app(code, period, ts);

        -- 预测历史日志：每轮把七档量化预测快照落库，供命中率统计。
        -- dir: +1 上涨 / -1 下跌 / 0 震荡或数据不足；主键 (code,ts,horizon) 幂等。
        CREATE TABLE IF NOT EXISTS forecast_log (
            code      TEXT NOT NULL,
            ts        INTEGER NOT NULL,   -- epoch milliseconds（预测时刻）
            horizon  TEXT NOT NULL,      -- 1m/5m/15m/30m/60m/1d
            hmin     INTEGER NOT NULL,   -- 该档分钟数
            dir      INTEGER NOT NULL,
            score    REAL,
            price    REAL,               -- 预测时刻收盘价
            PRIMARY KEY (code, ts, horizon)
        );
        CREATE INDEX IF NOT EXISTS idx_flog_code_ts
            ON forecast_log(code, ts);
        CREATE INDEX IF NOT EXISTS idx_flog_code_h
            ON forecast_log(code, horizon, ts);
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


def _ms_to_str(ms: int) -> str:
    """毫秒 epoch → "YYYY-MM-DD HH:MM:SS"（CST），供 kline_min 字符串 ts 比较。"""
    return datetime.fromtimestamp(ms / 1000, CST).strftime("%Y-%m-%d %H:%M:%S")


def minute_latest_ts(code: str) -> int:
    """该品种 kline_min 最新 bar 的毫秒 epoch；空表返回 0。"""
    with _lock:
        c = _connect()
        r = c.execute(
            "SELECT MAX(ts) FROM kline_min WHERE code=?", (code,)).fetchone()
    return int(bar_ts_ms(r[0])) if r and r[0] is not None else 0


def minute_count_in_range(code: str, start_ms: int, end_ms: int) -> int:
    """统计 [start_ms, end_ms]（闭区间，毫秒）内已有 1m K 线根数。

    kline_min.ts 为 "YYYY-MM-DD HH:MM:SS" 文本，该格式零填充、字典序即时间序，
    可直接用字符串比较做范围过滤，无需逐行解析。
    """
    with _lock:
        c = _connect()
        return int(c.execute(
            "SELECT COUNT(*) FROM kline_min WHERE code=? AND ts>=? AND ts<=?",
            (code, _ms_to_str(start_ms), _ms_to_str(end_ms)),
        ).fetchone()[0])


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


# ------------------------------------------------------- 时间桶（唯一口径）
# backend 内部唯一的「自然时间桶」口径：桶起点 = ts_ms - (ts_ms % step_ms)，
# 与 Android Forecaster.aggregateByTime 的 Math.floorMod 对齐（ts 恒为正）。
# 所有新增多周期落库 / app 接口都走这里，避免与既有 /api/kline 的秒级字符串口径分叉。

def bar_ts_ms(value) -> int | None:
    """把任意 bar/snapshots 时间戳解析为 epoch 毫秒整数。

    兼容 "YYYY-MM-DD HH:MM:SS"、"YYYY-MM-DD"、ISO 8601(T/时区/Z)，
    以及已是秒(int)/毫秒(int)的数值；naive 时间按北京时间(CST)处理。
    """
    if value is None:
        return None
    if isinstance(value, (int, float)):
        v = int(value)
        # 秒级 epoch(约 1e9)补成毫秒；毫秒级(约 1e12)原样返回。
        return v * 1000 if v < 10_000_000_000 else v
    s = str(value).strip()
    if not s:
        return None
    if s.endswith("Z"):
        s = s[:-1] + "+00:00"
    try:
        dt = datetime.fromisoformat(s)
    except ValueError:
        try:
            dt = datetime.strptime(s, "%Y-%m-%d %H:%M:%S")
        except ValueError:
            return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=CST)
    return int(dt.timestamp() * 1000)


def aggregate_bars(bars: Iterable[dict], step_ms: int) -> list[dict]:
    """按自然时间桶把 bar 序列聚合成 OHLCV，返回 [{ts(ms int),o,h,l,c,v}] 升序。

    桶起点 = ts - floorMod(ts, step_ms)；首 open / 末 close / max high / min low / sum vol。
    bar 的 ts 可为字符串或毫秒/秒数值；ts 无法解析或 c 缺失的 bar 跳过。
    """
    bucket = int(step_ms)
    out: dict[int, dict] = {}
    for b in bars:
        ts = bar_ts_ms(b.get("ts") if b.get("ts") is not None else b.get("t"))
        c = b.get("c")
        if ts is None or c is None:
            continue
        start = ts - ts % bucket if bucket > 0 else ts
        o = b.get("o") if b.get("o") is not None else c
        h = b.get("h") if b.get("h") is not None else c
        l = b.get("l") if b.get("l") is not None else c
        v = b.get("v") if b.get("v") is not None else 0.0
        cur = out.get(start)
        if cur is None:
            out[start] = {"ts": start, "o": o, "h": h, "l": l, "c": c, "v": v}
        else:
            cur["h"] = max(cur["h"], h)
            cur["l"] = min(cur["l"], l)
            cur["c"] = c
            cur["v"] = cur["v"] + v
    return [out[k] for k in sorted(out)]


# ------------------------------------------------------------- kline_multi

def upsert_multi(code: str, period: str, bars: Iterable[dict],
                 only_new: bool = False) -> int:
    """把聚合 bars 写入 kline_multi，返回行数。

    only_new=True 用 INSERT OR IGNORE（已存在主键跳过，用 total_changes 统计真实
    新增，供 /api/sync/repair 幂等回补）；False 用 INSERT OR REPLACE（collector
    增量时覆盖「进行中」的最后一个桶），返回写入根数。bars.ts 一律毫秒整数。
    """
    rows = [
        (code, period, int(b["ts"]), b.get("o"), b.get("h"), b.get("l"),
         b.get("c"), b.get("v"))
        for b in bars
        if b.get("ts") is not None and b.get("c") is not None
    ]
    if not rows:
        return 0
    verb = "INSERT OR IGNORE" if only_new else "INSERT OR REPLACE"
    with _lock:
        c = _connect()
        before = c.total_changes
        c.executemany(
            f"{verb} INTO kline_multi(code,period,ts,o,h,l,c,v)"
            " VALUES(?,?,?,?,?,?,?,?)",
            rows,
        )
        c.commit()
        added = c.total_changes - before
    return added if only_new else len(rows)


def get_multi(code: str, period: str, limit: int = 4000) -> list[dict]:
    with _lock:
        c = _connect()
        rows = c.execute(
            "SELECT ts,o,h,l,c,v FROM kline_multi WHERE code=? AND period=?"
            " ORDER BY ts DESC LIMIT ?",
            (code, period, limit),
        ).fetchall()
    return [dict(r) for r in reversed(rows)]


def multi_count(code: str, period: str) -> int:
    with _lock:
        c = _connect()
        return int(c.execute(
            "SELECT COUNT(*) FROM kline_multi WHERE code=? AND period=?",
            (code, period)).fetchone()[0])


def multi_latest_ts(code: str, period: str) -> int:
    """该 (code,period) 最新桶起点毫秒；空表返回 0。"""
    with _lock:
        c = _connect()
        r = c.execute(
            "SELECT MAX(ts) FROM kline_multi WHERE code=? AND period=?",
            (code, period)).fetchone()
    return int(r[0]) if r and r[0] is not None else 0


def multi_first_ts(code: str, period: str) -> int:
    """该 (code,period) 真实覆盖起点毫秒；空表返回 0。"""
    with _lock:
        c = _connect()
        r = c.execute(
            "SELECT MIN(ts) FROM kline_multi WHERE code=? AND period=?",
            (code, period)).fetchone()
    return int(r[0]) if r and r[0] is not None else 0


def multi_ts_set(code: str, period: str, since_ms: int = 0) -> set[int]:
    """近窗口内已有桶 ts 集合（体检逐根比对用）。"""
    with _lock:
        c = _connect()
        rows = c.execute(
            "SELECT ts FROM kline_multi WHERE code=? AND period=? AND ts>=?",
            (code, period, int(since_ms)),
        ).fetchall()
    return {int(r[0]) for r in rows}


def sync_multi_incremental(code: str, minute_bars: list[dict]) -> int:
    """collector 每轮调用：把本地累计 1m 序列按时间桶聚合，增量 upsert 派生周期。

    只写「尾部新桶 + 进行中最后一个桶」（ts >= 现有最大桶起点），
    不每轮全量重写历史；返回本轮写入根数。1m 不插值、不伪造。
    """
    written = 0
    for period, step in MULTI_PERIODS:
        agg = aggregate_bars(minute_bars, step * 60_000)
        latest = multi_latest_ts(code, period)
        fresh = [b for b in agg if b["ts"] >= latest] if latest else agg
        if fresh:
            written += upsert_multi(code, period, fresh)
    return written


# ------------------------------------------------------------- 统计 / 秒级

def period_stats() -> dict:
    """各周期根数与真实覆盖起点（毫秒）：{"1m":{"n":..,"from":..}, ...}。

    对齐 Android Store.periodStats()：按 period 分组，跨三品种合计；
    from 为毫秒整数（真实覆盖起点），空表为 0。
    """
    stats: dict[str, dict] = {}
    with _lock:
        c = _connect()
        for period, table in (("1m", "kline_min"), ("1d", "kline_day")):
            n = int(c.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0])
            first = 0
            if n:
                r = c.execute(
                    f"SELECT ts FROM {table} ORDER BY ts ASC LIMIT 1").fetchone()
                if r:
                    first = bar_ts_ms(r[0]) or 0
            stats[period] = {"n": n, "from": first}
        for period, _ in MULTI_PERIODS:
            n = int(c.execute(
                "SELECT COUNT(*) FROM kline_multi WHERE period=?",
                (period,)).fetchone()[0])
            r = c.execute(
                "SELECT MIN(ts) FROM kline_multi WHERE period=?",
                (period,)).fetchone()
            first = int(r[0]) if r and r[0] is not None else 0
            stats[period] = {"n": n, "from": first}
    return stats


def coverage_starts() -> dict:
    """各「品种×周期」真实覆盖起点毫秒，key 形如 "AL|1m"（对齐 App coverage）。"""
    out: dict[str, int] = {}
    with _lock:
        c = _connect()
        for code in PRODUCT_BY_CODE:
            for period, table in (("1m", "kline_min"), ("1d", "kline_day")):
                r = c.execute(
                    f"SELECT ts FROM {table} WHERE code=? ORDER BY ts ASC LIMIT 1",
                    (code,)).fetchone()
                out[f"{code}|{period}"] = (bar_ts_ms(r[0]) or 0) if r else 0
            for period, _ in MULTI_PERIODS:
                r = c.execute(
                    "SELECT MIN(ts) FROM kline_multi WHERE code=? AND period=?",
                    (code, period)).fetchone()
                out[f"{code}|{period}"] = (
                    int(r[0]) if r and r[0] is not None else 0)
    return out


def snapshot_ohlc(code: str, limit: int = 4500) -> list[dict]:
    """秒级 snapshots → 1s 点：价格转 OHLC 同值，ts 为毫秒整数。

    供 /api/app/kline 的 1s 直接使用、30s 再由 aggregate_bars 按桶聚合。
    没有 last 时依次用 (bid+ask)/2、bid、ask；无价可取的点跳过。
    """
    out = []
    for s in snapshot_range(code, limit):
        price = s.get("last")
        if price is None:
            bid, ask = s.get("bid"), s.get("ask")
            if bid is not None and ask is not None:
                price = (bid + ask) / 2.0
            else:
                price = bid if bid is not None else ask
        if price is None:
            continue
        out.append({
            "ts": int(s["ts"]) * 1000,   # snapshots.ts 为 epoch 秒
            "o": price, "h": price, "l": price, "c": price,
            "v": s.get("volume") or 0.0,
        })
    return out


# ------------------------------------------------------------- forecast_log
# 预测历史日志：每轮七档量化预测落库，供 /api/history 命中率统计。

_DIR_MAP = {"上涨": 1, "下跌": -1}  # 其余（震荡 / 数据不足）记 0


def dir_to_int(direction: str) -> int:
    return _DIR_MAP.get(direction, 0)


def log_forecast(code: str, ts_ms: int, horizon: str, hmin: int,
                 direction: str, score: float | None,
                 price: float | None) -> None:
    """幂等写入一条预测记录（主键 code,ts,horizon）。"""
    with _lock:
        c = _connect()
        c.execute(
            "INSERT OR REPLACE INTO forecast_log"
            "(code,ts,horizon,hmin,dir,score,price) VALUES(?,?,?,?,?,?,?)",
            (code, int(ts_ms), horizon, int(hmin), dir_to_int(direction),
             score, price),
        )
        c.commit()


def forecast_log_since(code: str, horizon: str, since_ms: int) -> list[dict]:
    """取该 (code,horizon) 自 since_ms 起的预测记录，按 ts 升序。"""
    with _lock:
        c = _connect()
        rows = c.execute(
            "SELECT ts,dir,score,price FROM forecast_log"
            " WHERE code=? AND horizon=? AND ts>=? ORDER BY ts ASC",
            (code, horizon, int(since_ms)),
        ).fetchall()
    return [dict(r) for r in rows]


def truncate_forecast_log(keep_ms: int) -> None:
    """删除 keep_ms 之前的全量预测日志，限制表无限增长（默认保留 90 天）。"""
    with _lock:
        c = _connect()
        c.execute("DELETE FROM forecast_log WHERE ts < ?", (int(keep_ms),))
        c.commit()


def price_at_or_after(code: str, ts_ms: int) -> float | None:
    """返回该品种在 ts_ms 或之后第一根 bar 的收盘价；用于命中率的实际涨跌判定。

    优先 1m（覆盖约 1023 分钟，足以覆盖 4h/8h 档）；1m 查不到（如 1d 档落在未来日线）
    再退回日线。无数据返回 None。
    """
    with _lock:
        c = _connect()
        r = c.execute(
            "SELECT c FROM kline_min WHERE code=? AND ts>=? ORDER BY ts ASC LIMIT 1",
            (code, _ms_to_str(ts_ms))).fetchone()
        if r and r[0] is not None:
            return float(r[0])
        r = c.execute(
            "SELECT c FROM kline_day WHERE code=? AND ts>=? ORDER BY ts ASC LIMIT 1",
            (code, _ms_to_str(ts_ms))).fetchone()
        if r and r[0] is not None:
            return float(r[0])
    return None


# ------------------------------------------------------------- kline_app
# 双向同步专用分表：手机上传的「源站段末口径」5/15/30/60m 序列。
# 与 kline_multi（服务端自然桶聚合口径）物理隔离，避免污染既有 /api/kline
# 与 /api/app/* 的输出；ts 一律 UTC 毫秒整数。

def get_app(code: str, period: str, limit: int = 100000) -> list[dict]:
    """按 ts 升序读取 kline_app 的 (code,period) 序列，ts 为毫秒整数。"""
    with _lock:
        c = _connect()
        rows = c.execute(
            "SELECT ts,o,h,l,c,v FROM kline_app WHERE code=? AND period=?"
            " ORDER BY ts ASC LIMIT ?",
            (code, period, int(limit)),
        ).fetchall()
    return [dict(r) for r in rows]


def upsert_app(code: str, period: str, bars: list[dict],
               source: str = "app") -> tuple[int, int]:
    """幂等写入 kline_app，返回 (净新增行数, 该组合写入后总行数)。

    bars 需已由调用方校验/去重，ts 为毫秒整数、OHLC 为有限浮点。
    先按主键查已存在 ts 计数，再用 INSERT OR REPLACE 覆盖重复项；
    added = 去重后根数 - 已存在数，重复调用恒为 0。
    """
    ts_list = [int(b["ts"]) for b in bars]
    with _lock:
        c = _connect()
        if not ts_list:
            total = int(c.execute(
                "SELECT COUNT(*) FROM kline_app WHERE code=? AND period=?",
                (code, period)).fetchone()[0])
            return 0, total
        marks = ",".join("?" for _ in ts_list)
        existing = int(c.execute(
            "SELECT COUNT(*) FROM kline_app WHERE code=? AND period=?"
            f" AND ts IN ({marks})",
            (code, period, *ts_list)).fetchone()[0])
        rows = [
            (code, period, int(b["ts"]), b["o"], b["h"], b["l"], b["c"],
             b["v"], source)
            for b in bars
        ]
        c.executemany(
            "INSERT OR REPLACE INTO kline_app(code,period,ts,o,h,l,c,v,source)"
            " VALUES(?,?,?,?,?,?,?,?,?)",
            rows,
        )
        c.commit()
        total = int(c.execute(
            "SELECT COUNT(*) FROM kline_app WHERE code=? AND period=?",
            (code, period)).fetchone()[0])
    return len(rows) - existing, total


def db_status() -> dict:
    """组装 /api/db/status 数据主体：periods / app_table / snapshots。

    periods 覆盖三品种×六周期(1m/5m/15m/30m/60m/1d)，值来自既有表，
    字符串/秒 ts 统一经 bar_ts_ms 转毫秒整数；app_table 覆盖 kline_app
    各 (code,period) 计数并补齐三品种×四周期默认 0；snapshots 为全表行数。
    """
    multi_periods = tuple(p for p, _ in MULTI_PERIODS)
    periods: dict[str, dict] = {}
    with _lock:
        c = _connect()
        for code in PRODUCT_BY_CODE:
            for period, table in (("1m", "kline_min"), ("1d", "kline_day")):
                r = c.execute(
                    f"SELECT COUNT(*), MIN(ts), MAX(ts) FROM {table}"
                    " WHERE code=?", (code,)).fetchone()
                n = int(r[0]) if r and r[0] is not None else 0
                lo = (bar_ts_ms(r[1]) or 0) if n else 0
                hi = (bar_ts_ms(r[2]) or 0) if n else 0
                periods[f"{code}|{period}"] = {"n": n, "min": lo, "max": hi}
            for period in multi_periods:
                r = c.execute(
                    "SELECT COUNT(*), MIN(ts), MAX(ts) FROM kline_multi"
                    " WHERE code=? AND period=?", (code, period)).fetchone()
                n = int(r[0]) if r and r[0] is not None else 0
                lo = int(r[1]) if n and r[1] is not None else 0
                hi = int(r[2]) if n and r[2] is not None else 0
                periods[f"{code}|{period}"] = {"n": n, "min": lo, "max": hi}

        app_table: dict[str, dict] = {
            f"{code}|{period}": {"n": 0}
            for code in PRODUCT_BY_CODE for period in multi_periods
        }
        for r in c.execute(
                "SELECT code, period, COUNT(*) FROM kline_app"
                " GROUP BY code, period").fetchall():
            app_table[f"{r[0]}|{r[1]}"] = {"n": int(r[2])}

        snaps = int(c.execute("SELECT COUNT(*) FROM snapshots").fetchone()[0])
    return {"periods": periods,
            "app_table": app_table,
            "snapshots": {"n": snaps}}
