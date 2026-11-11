"""数据库双向同步接口：GET /api/db/status、GET /api/db/export、POST /api/db/import。

契约（与 Android / 前端组严格一致）：
- 所有 ts 统一 UTC 毫秒整数。
- export 的 5m/15m/30m/60m 取数优先级：① 服务端权威原生多周期 kline_native
  （内盘 AL/AO 直采，不经 1m 聚合，含数周~数月连续历史）；② 回退聚合表
  kline_multi（外盘 AHD / 历史）；③ 再回退手机上传的 kline_app 分表。手机同步
  时以此 authoritative 序列为准，避免把缺口反向污染回手机。
- import 一律写 kline_app，不碰 kline_min/kline_day/kline_multi（既有接口零影响）。
- sync3d 仅返回最近 3 天切片（前端/轻量补缺用）；手机 1m+ 全量补齐应改用
  export 分页（since=0 一路翻页），不要依赖 sync3d 的 3 天窗口。
"""
from __future__ import annotations

import logging
import math
import time

from fastapi import APIRouter, Query, Request
from fastapi.responses import JSONResponse

from . import store
from .config import MULTI_PERIODS, PRODUCT_BY_CODE

log = logging.getLogger("db_sync")

router = APIRouter()

# 八周期白名单（export / import 共用，与 main.APP_PERIODS 一致）
_DB_PERIODS = ("1s", "30s", "1m", "5m", "15m", "30m", "60m", "1d")
# 只读 kline_app 的四个多周期（源站段末口径）
_APP_PERIODS = tuple(p for p, _ in MULTI_PERIODS)      # 5m/15m/30m/60m
# 读取既有表全量的上限（既有表数据量有限：1m 源站上限约 1023 根）
_ALL = 1_000_000

_NOTE_STATUS = (
    "ts 统一 UTC 毫秒整数；5m/15m/30m/60m 的导出优先取自服务端权威原生"
    "多周期 kline_native（内盘直采，不经 1m 聚合），无则回退聚合"
    "kline_multi，再无则手机上传 kline_app；手机同步补缺以本序列为准"
)
_NOTE_EMPTY_MULTI = "该周期服务端暂无权威原生/聚合数据，亦无手机上传的源站口径数据"
_NOTE_IMPORT = (
    "写入独立分表 kline_app（源站段末口径），不触碰 kline_min/kline_day/"
    "kline_multi，既有接口零影响；主键 (code,period,ts) 幂等，重复上传净新增为 0"
)


def _finite(value):
    """转有限浮点；None / bool / 非数 / NaN / inf 返回 None。"""
    if isinstance(value, bool) or value is None:
        return None
    try:
        f = float(value)
    except (TypeError, ValueError):
        return None
    return f if math.isfinite(f) else None


def _norm_bar(ts_ms, b):
    """规整为 {ts(ms int),o,h,l,c,v}；ts 无效或收盘价非有限则丢弃该行。

    o/h/l 缺失或非有限回退收盘价，v 缺失或非有限回退 0.0，
    保证输出的 OHLC 一律为有限浮点、ts 一律为 int。
    """
    if ts_ms is None:
        return None
    c = _finite(b.get("c"))
    if c is None:
        return None
    o = _finite(b.get("o"))
    h = _finite(b.get("h"))
    l = _finite(b.get("l"))
    v = _finite(b.get("v"))
    return {
        "ts": int(ts_ms),
        "o": o if o is not None else c,
        "h": h if h is not None else c,
        "l": l if l is not None else c,
        "c": c,
        "v": v if v is not None else 0.0,
    }


def _valid_code(code):
    return code in PRODUCT_BY_CODE


def _valid_period(period):
    return period in _DB_PERIODS


def _source_note(period):
    if period == "1s":
        return "1s 来自 snapshots 原始秒级点，OHLC 同值，ts 为毫秒整数"
    if period == "30s":
        return "30s 来自 snapshots 按 30 秒自然时间桶聚合，ts 为毫秒整数"
    if period == "1m":
        return "1m 来自 kline_min，字符串 ts 已统一转 UTC 毫秒整数"
    if period == "1d":
        return "1d 来自 kline_day，字符串 ts 已统一转 UTC 毫秒整数"
    return (f"{period} 优先取自服务端权威原生多周期 kline_native（内盘直采，"
            "不经 1m 聚合）；无则回退聚合 kline_multi；再无则手机上传 kline_app")


def _export_rows(code, period):
    """按周期取数并规整为毫秒 ts + 有限 OHLC 的候选列表。"""
    if period == "1s":
        raw = store.snapshot_ohlc(code, _ALL)           # ts 已是毫秒整数
        rows = [_norm_bar(b.get("ts"), b) for b in raw]
    elif period == "30s":
        pts = store.snapshot_ohlc(code, _ALL)
        agg = store.aggregate_bars(pts, 30_000)         # 30 秒自然时间桶
        rows = [_norm_bar(b.get("ts"), b) for b in agg]
    elif period == "1m":
        raw = store.get_minute(code, _ALL)              # ts 字符串
        rows = [_norm_bar(store.bar_ts_ms(b.get("ts")), b) for b in raw]
    elif period == "1d":
        raw = store.get_daily(code, _ALL)               # ts 字符串
        rows = [_norm_bar(store.bar_ts_ms(b.get("ts")), b) for b in raw]
    else:  # 5m/15m/30m/60m：优先服务端权威原生多周期（内盘直采），
           # 回退聚合 kline_multi（外盘/历史），再回退手机上传 kline_app。
           # 手机同步补缺时以此 authoritative 序列为准，避免把缺口反向污染到手机。
        raw = store.get_native(code, period, _ALL)       # 内盘原生：最权威
        if not raw:
            raw = store.get_multi(code, period, _ALL)     # 外盘/聚合回退
        if not raw:
            raw = store.get_app(code, period, _ALL)       # 手机上传回退
        rows = [_norm_bar(b.get("ts"), b) for b in raw]
    return [r for r in rows if r is not None]


def _paginate(rows, since, limit):
    """since 毫秒（严格大于）过滤 + 升序分页，返回 (bars, next_since, has_more)。"""
    rows = sorted((r for r in rows if r["ts"] > since), key=lambda r: r["ts"])
    page = rows[:limit + 1]
    has_more = len(page) > limit
    bars = page[:limit]
    next_since = bars[-1]["ts"] if bars else since
    return bars, next_since, has_more


@router.get("/api/db/status")
async def db_status(request: Request):
    try:
        data = store.db_status()
        return {
            "periods": data["periods"],
            "app_table": data["app_table"],
            "snapshots": data["snapshots"],
            "ts": int(time.time() * 1000),
            "server_version": request.app.version,
            "note": _NOTE_STATUS,
        }
    except Exception as e:  # noqa: BLE001
        log.exception("db/status 失败")
        return JSONResponse({"error": type(e).__name__}, status_code=500)


@router.get("/api/db/export")
async def db_export(code: str = Query("AL"),
                    period: str = Query("1m"),
                    since: int = Query(0),
                    limit: int = Query(300)):
    if not _valid_code(code) or not _valid_period(period):
        return JSONResponse({"error": "invalid code or period"}, status_code=400)
    since = max(0, int(since))
    limit = max(1, min(300, int(limit)))
    try:
        rows = _export_rows(code, period)
        bars, next_since, has_more = _paginate(rows, since, limit)
        if period in _APP_PERIODS and not rows:
            note = _NOTE_EMPTY_MULTI
        else:
            note = _source_note(period)
        return {
            "code": code,
            "period": period,
            "bars": bars,
            "next_since": next_since,
            "has_more": has_more,
            "note": note,
        }
    except Exception as e:  # noqa: BLE001
        log.exception("db/export 失败")
        return JSONResponse({"error": type(e).__name__}, status_code=500)


@router.get("/api/db/sync3d")
async def db_sync3d(code: str = Query("AL"),
                    period: str = Query("1m")):
    """返回服务端权威的「最近 3 天」完整切片（非分页），供手机/前端补缺。

    手机同步时按 (code,period) 调用本接口，把返回的全部 bar 以 INSERT OR REPLACE
    幂等写入本地 SQLite，即完成「对比最近 3 天、缺少的插入」：服务端为权威源，
    最近 3 天内以服务端为准，覆盖/补齐手机本地缺口。
    数据来源与 /api/db/export 一致（原生 kline_native → 聚合 kline_multi →
    手机上传 kline_app）。所有 ts 为毫秒整数。
    """
    if not _valid_code(code) or not _valid_period(period):
        return JSONResponse({"error": "invalid code or period"}, status_code=400)
    window = int(time.time() * 1000) - 3 * 86400_000
    try:
        rows = [r for r in _export_rows(code, period) if r["ts"] >= window]
        return {
            "code": code,
            "period": period,
            "window_start": window,
            "bars": rows,
            "count": len(rows),
            "note": _source_note(period),
        }
    except Exception as e:  # noqa: BLE001
        log.exception("db/sync3d 失败")
        return JSONResponse({"error": type(e).__name__}, status_code=500)


@router.post("/api/db/import")
async def db_import(payload: dict):
    try:
        if not isinstance(payload, dict):
            return JSONResponse({"ok": False, "error": "invalid body"},
                                status_code=400)
        code = payload.get("code")
        period = payload.get("period")
        if not _valid_code(code):
            return JSONResponse({"ok": False, "error": "invalid code"},
                                status_code=400)
        if not _valid_period(period):
            return JSONResponse({"ok": False, "error": "invalid period"},
                                status_code=400)
        bars = payload.get("bars")
        if not isinstance(bars, list):
            return JSONResponse({"ok": False, "error": "bars must be a list"},
                                status_code=400)
        if len(bars) > 300:
            return JSONResponse({"ok": False, "error": "bars exceed 300"},
                                status_code=400)
        source = payload.get("source", "app")
        source = source if isinstance(source, str) else "app"

        upper = int(time.time() * 1000) + 86400_000
        unique: dict[int, dict] = {}
        for b in bars:
            if not isinstance(b, dict):
                return JSONResponse({"ok": False, "error": "invalid bar"},
                                    status_code=400)
            ts = b.get("ts")
            if isinstance(ts, bool) or not isinstance(ts, (int, float)):
                return JSONResponse({"ok": False, "error": "invalid ts"},
                                    status_code=400)
            ts_i = int(ts)
            if ts_i <= 0 or ts_i >= upper:
                return JSONResponse({"ok": False, "error": "invalid ts"},
                                    status_code=400)
            c = _finite(b.get("c"))
            if c is None:
                return JSONResponse({"ok": False, "error": "invalid OHLC"},
                                    status_code=400)
            vals = {}
            for key in ("o", "h", "l", "v"):
                raw = b.get(key)
                if raw is None:
                    vals[key] = None                 # 缺失回退
                else:
                    f = _finite(raw)
                    if f is None:
                        return JSONResponse(
                            {"ok": False, "error": "invalid OHLC"},
                            status_code=400)
                    vals[key] = f
            unique[ts_i] = {
                "ts": ts_i,
                "o": vals["o"] if vals["o"] is not None else c,
                "h": vals["h"] if vals["h"] is not None else c,
                "l": vals["l"] if vals["l"] is not None else c,
                "c": c,
                "v": vals["v"] if vals["v"] is not None else 0.0,
            }

        added, total = store.upsert_app(code, period, list(unique.values()),
                                        source)
        return {"ok": True, "added": added, "total": total,
                "note": _NOTE_IMPORT}
    except Exception as e:  # noqa: BLE001
        log.exception("db/import 失败")
        return JSONResponse({"ok": False, "error": type(e).__name__},
                            status_code=500)
