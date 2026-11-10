"""FastAPI application: REST + WebSocket."""
from __future__ import annotations

import asyncio
import json
import logging
import time
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from urllib.parse import urlparse

from fastapi import FastAPI, Query, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from . import autofill, collector, llm, store
from . import db_sync
from .config import (APP_HORIZONS, HORIZONS, MULTI_PERIODS, PRODUCTS,
                     PRODUCT_BY_CODE, STATIC_DIR)
from .datasource.http_util import close_client
from .datasource.sina import _drop_future
from .model.forecaster import forecast_one

logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("api")

# 市场 bar 时间戳为北京时间（CST），时间桶聚合需与数据源口径保持一致。
CST = timezone(timedelta(hours=8))

# /ws 加固：允许无 Origin 或本机 Origin，并限制全局并发连接数。
_WS_MAX_CONNECTIONS = 20
_ws_connections = 0


@asynccontextmanager
async def lifespan(app: FastAPI):
    store.init()
    # 启动自检补全：依据期货交易日历检测缺口并补齐窗口内缺失（不阻塞服务启动）
    try:
        await autofill.check_and_fill()
    except Exception:  # noqa: BLE001
        log.exception("启动自检补全失败（不影响服务启动）")
    task = asyncio.create_task(collector.run_all())
    log.info("collectors started")
    try:
        yield
    finally:
        task.cancel()
        try:
            await task
        except (asyncio.CancelledError, Exception):
            pass
        await close_client()


app = FastAPI(title="铝期货趋势预测", version="1.0.0", lifespan=lifespan)

# 数据库双向同步接口（/api/db/*），独立分表 kline_app，不影响既有路由。
app.include_router(db_sync.router)


@app.get("/api/health")
async def health():
    return {
        "ok": True,
        "ts": datetime.now(timezone.utc).isoformat(),
        "products": [p["code"] for p in PRODUCTS],
        "meta": collector.state.snapshot_meta(),
        "autofill": autofill.get_report(),
    }


@app.get("/api/products")
async def products():
    out = []
    for p in PRODUCTS:
        code = p["code"]
        out.append({
            "code": code,
            "name": p["name"],
            "short": p["short"],
            "unit": p["unit"],
            "quote": collector.state.quotes.get(code),
            "snapshots": store.snapshot_count(code),
            "minute_bars": store.minute_count(code),
            "daily_bars": store.daily_count(code),
        })
    return {"products": out, "horizons": [
        {"key": k, "label": l, "minutes": m} for k, l, m, _, _ in APP_HORIZONS]}


@app.get("/api/quote")
async def quote():
    return {"quotes": collector.state.quotes,
            "meta": collector.state.snapshot_meta()}


def _aggregate_kline(base: list[dict], step: int) -> list[dict]:
    """按时间桶聚合 1m 序列（统一走 store.aggregate_bars 的自然毫秒桶口径）。

    为兼容既有 /api/kline/{code} 的输出，ts 仍格式化回 "%Y-%m-%d %H:%M:%S"
    字符串：字段名/结构与既有接口一致，仅内部聚合算法收敛为「自然桶起点」一种。
    OHLC = 首 open / 末 close / max high / min low / sum vol。
    """
    agg = store.aggregate_bars(base, step * 60_000)
    return [
        {
            "ts": datetime.fromtimestamp(b["ts"] / 1000, CST).strftime("%Y-%m-%d %H:%M:%S"),
            "o": b["o"], "h": b["h"], "l": b["l"], "c": b["c"], "v": b["v"],
        }
        for b in agg
    ]


@app.get("/api/kline/{code}")
async def kline(code: str,
                period: str = Query("1m", pattern="^(1m|5m|15m|30m|60m|1d)$"),
                limit: int = Query(400, ge=20, le=4500)):
    if code not in PRODUCT_BY_CODE:
        return JSONResponse({"error": "unknown code"}, status_code=404)
    if period == "1d":
        bars = collector.state.daily.get(code) or store.get_daily(code, limit)
        bars = _drop_future(bars)
        return {"code": code, "period": period, "bars": bars[-limit:]}

    base = _drop_future(collector.state.minute.get(code) or store.get_minute(code, 1200))
    if period == "1m":
        return {"code": code, "period": period, "bars": base[-limit:]}

    step = {"5m": 5, "15m": 15, "30m": 30, "60m": 60}[period]
    agg = _aggregate_kline(base, step)
    return {"code": code, "period": period, "bars": agg[-limit:]}


@app.get("/api/snapshots/{code}")
async def snapshots(code: str, limit: int = Query(900, ge=10, le=5000)):
    if code not in PRODUCT_BY_CODE:
        return JSONResponse({"error": "unknown code"}, status_code=404)
    return {"code": code, "points": store.snapshot_range(code, limit)}


@app.get("/api/forecast")
async def forecast(code: str | None = None):
    if code:
        if code not in PRODUCT_BY_CODE:
            return JSONResponse({"error": "unknown code"}, status_code=404)
        return collector.state.forecast.get(code, {})
    return {"forecasts": collector.state.forecast,
            "meta": collector.state.snapshot_meta()}


@app.get("/api/news")
async def news(limit: int = Query(40, ge=5, le=200),
               code: str | None = None):
    items = store.get_news(limit, code)
    return {"news": items, "count": len(items)}


@app.get("/api/analysis")
async def analysis():
    """Composite quantitative view across the three products."""
    rows = []
    for code, p in PRODUCT_BY_CODE.items():
        f = collector.state.forecast.get(code) or {}
        rows.append({
            "code": code,
            "short": p["short"],
            "unit": p["unit"],
            "price": f.get("price"),
            "overall": f.get("overall"),
            "score": f.get("score"),
            "indicators": f.get("indicators", {}),
            "horizons": f.get("horizons", []),
            "recommendation": f.get("recommendation"),
        })
    return {"rows": rows, "meta": collector.state.snapshot_meta()}


# =====================================================================
#  App 对照接口 / 体检回补（新增）
#  - backend 内部只用「自然时间桶起点」一种口径（毫秒 ts）
#  - 既有接口 /api/kline/{code} 等输出保持字段名/结构不变
# =====================================================================

APP_PERIODS = ("1s", "30s", "1m", "5m", "15m", "30m", "60m", "1d")
APP_REPAIR_PERIODS = ("1m", "5m", "15m", "30m", "60m", "1d")
CHECK_DAYS = 7
_CHECK_MS = CHECK_DAYS * 86400_000
REPAIR_BATCH = 300
REPAIR_RATE = 0.02
_MULTI_STEP = dict(MULTI_PERIODS)

NOTE_1M = "1m 源站上限 1023 根，仅覆盖约 2 交易日，7 天前空洞不可补"
NOTE_TICK = "秒级仅本机采集，公开源不提供历史秒级，无法回溯补齐"
NOTE_BUCKET = ("时间桶口径：源站 5m/15m/30m/60m 的 ts 是交易时段段末，"
               "本地聚合用自然桶起点(ts - floorMod(ts, step*60000))，"
               "两者不要求一致，但 backend 内部只用自然桶起点一种")


def _unknown():
    return JSONResponse({"error": "unknown code"}, status_code=404)


def _fail(exc, context):
    """完整异常只写日志，对外只返回不含 URL/堆栈的脱敏错误类名。"""
    log.exception("%s 失败", context)
    return JSONResponse({"error": type(exc).__name__}, status_code=500)


def _valid_code(code):
    return code in PRODUCT_BY_CODE


def _valid_period(period):
    return period in APP_PERIODS


def _ohlcv(b, ts=None):
    """规整成 {ts(ms int),o,h,l,c,v}，缺失的 OHLC 回退到收盘价、量回退 0。"""
    c = b.get("c")
    return {
        "ts": int(ts if ts is not None else b["ts"]),
        "o": b.get("o") if b.get("o") is not None else c,
        "h": b.get("h") if b.get("h") is not None else c,
        "l": b.get("l") if b.get("l") is not None else c,
        "c": c,
        "v": b.get("v") if b.get("v") is not None else 0.0,
    }


def _app_kline_bars(code, period, limit):
    """八周期统一输出：bars[].ts 一律为毫秒整数。"""
    if period == "1s":
        bars = [_ohlcv(b) for b in store.snapshot_ohlc(code, limit)]
    elif period == "30s":
        pts = store.snapshot_ohlc(code, 4500)
        bars = [_ohlcv(b) for b in store.aggregate_bars(pts, 30_000)]
    elif period == "1m":
        bars = []
        for b in store.get_minute(code, limit):
            ts = store.bar_ts_ms(b.get("ts"))
            if ts is not None and b.get("c") is not None:
                bars.append(_ohlcv(b, ts))
    elif period in _MULTI_STEP:
        bars = [_ohlcv(b) for b in store.get_multi(code, period, limit)]
    else:  # 1d
        bars = []
        for b in store.get_daily(code, limit):
            ts = store.bar_ts_ms(b.get("ts"))
            if ts is not None and b.get("c") is not None:
                bars.append(_ohlcv(b, ts))
    return bars[-limit:] if limit else bars


def _compare_window(window, local):
    """逐根比对（口径同 App Engine.analyze）：返回 (missing, gaps, tail, cleared)。

    源站有、本地没有 = 缺失；本地近窗口一根都没有而源站有 = 整段清空；
    本地最后一根之后的新 bar 属正常增量，不算缺口。不自建交易日历。
    """
    if not window:
        return 0, 0, 0, False
    local = set(local)
    if not local:
        return len(window), 1, 0, True
    local_max = max(local)
    miss = gap = tail = 0
    in_gap = False
    for t in window:
        if t in local:
            in_gap = False
        elif t > local_max:
            tail += 1
        else:
            miss += 1
            if not in_gap:
                gap += 1
                in_gap = True
    return miss, gap, tail, False


def _run_check():
    """体检：近 7 天以源站落库序列为基准，逐根比对本地各周期。"""
    cut = int(time.time() * 1000) - _CHECK_MS
    scanned = missing = gaps = cleared = 0
    coverage = store.coverage_starts()
    coverage["note"] = "各周期真实覆盖起点（毫秒 epoch）；" + NOTE_1M
    for code in PRODUCT_BY_CODE:
        minute = store.get_minute(code, 1200)
        # 近 7 天源站基准 1m 序列（ts 统一成毫秒整数）
        win_bars = []
        for b in minute:
            t = store.bar_ts_ms(b.get("ts"))
            if t is not None and t >= cut:
                win_bars.append(dict(b, ts=t))
        m1 = [b["ts"] for b in win_bars]

        jobs = [("1m", m1, set(m1))]          # 1m 基准==本地，逐根比对自身
        for p, s in MULTI_PERIODS:
            expected = [b["ts"] for b in
                        store.aggregate_bars(win_bars, s * 60_000)]
            jobs.append((p, expected, store.multi_ts_set(code, p, cut)))
        day_win = []
        for b in store.get_daily(code, 4500):
            t = store.bar_ts_ms(b.get("ts"))
            if t is not None and t >= cut:
                day_win.append(t)
        jobs.append(("1d", day_win, set(day_win)))   # 1d 基准==本地

        for period, window, local in jobs:
            if window:
                scanned += 1
            m, g, _, cl = _compare_window(window, local)
            missing += m
            gaps += g
            if cl:
                cleared += 1
    return {
        "days": CHECK_DAYS,
        "scanned": scanned,
        "missing": missing,
        "gaps": gaps,
        "cleared": cleared,
        "coverage": coverage,
        "note": (f"体检窗口近 {CHECK_DAYS} 天，以源站落库序列为基准逐根比对本地各周期，"
                 f"不自建交易日历。{NOTE_1M}；{NOTE_TICK}。{NOTE_BUCKET}。"
                 "派生周期缺失/清空由 /api/sync/repair 按时间桶聚合回补。"),
    }


def _build_state():
    """贴近 App Engine.state() 的对照状态。"""
    products = []
    for p in PRODUCTS:
        code = p["code"]
        period_bars = {"1m": store.minute_count(code),
                       "1d": store.daily_count(code)}
        for per, _ in MULTI_PERIODS:
            period_bars[per] = store.multi_count(code, per)
        products.append({
            "code": code, "name": p["name"], "short": p["short"],
            "unit": p["unit"],
            "quote": collector.state.quotes.get(code),
            "minute_bars": store.minute_count(code),
            "daily_bars": store.daily_count(code),
            "period_bars": period_bars,
        })
    periods = store.period_stats()
    return {
        "products": products,
        "periods": periods,
        "last_sync": int(collector.state.last_kline_ts * 1000),
        "check": _run_check(),
        "coverage": store.coverage_starts(),
        "note": f"{NOTE_TICK}；{NOTE_1M}；{NOTE_BUCKET}",
        # 贴近 App Engine.state() 的附加字段
        "last_tick": int(collector.state.last_quote_ts * 1000),
        "last_error": (collector.state.errors[-1] if collector.state.errors else ""),
        "kline_rows": sum(v["n"] for v in periods.values()),
        "horizons": [{"key": k, "label": l, "minutes": m}
                     for k, l, m, _, _ in APP_HORIZONS],
        "server_version": app.version,
    }


async def _forecast_seven(code):
    """七档预测：复用 forecast_one 建模逻辑，传入 APP_HORIZONS，CPU 密集放线程。"""
    p = PRODUCT_BY_CODE[code]
    mm = _drop_future(collector.state.minute.get(code)
                      or store.get_minute(code, 1200))
    dd = _drop_future(collector.state.daily.get(code)
                      or store.get_daily(code, 4500))
    return await asyncio.to_thread(forecast_one, code, p["name"], mm, dd,
                                   APP_HORIZONS)


def _server():
    return {"version": app.version, "ts": int(time.time() * 1000)}


@app.get("/api/app/kline")
async def app_kline(code: str = Query("AL"),
                    period: str = Query("1m"),
                    limit: int = Query(400, ge=1, le=4500)):
    if not _valid_code(code) or not _valid_period(period):
        return _unknown()
    try:
        return {"code": code, "period": period,
                "bars": _app_kline_bars(code, period, limit)}
    except Exception as e:  # noqa: BLE001
        return _fail(e, "app/kline")


@app.get("/api/app/forecast")
async def app_forecast(code: str = Query("ALL")):
    if code != "ALL" and not _valid_code(code):
        return _unknown()
    try:
        codes = list(PRODUCT_BY_CODE) if code == "ALL" else [code]
        results = []
        for c in codes:
            results.append(await _forecast_seven(c))
        if code == "ALL":
            return {"code": "ALL", "forecasts": results,
                    "meta": collector.state.snapshot_meta()}
        return results[0]
    except Exception as e:  # noqa: BLE001
        return _fail(e, "app/forecast")


@app.get("/api/app/state")
async def app_state():
    try:
        return _build_state()
    except Exception as e:  # noqa: BLE001
        return _fail(e, "app/state")


@app.get("/api/app/snapshot")
async def app_snapshot(code: str = Query("AL"),
                       period: str = Query("1m"),
                       limit: int = Query(400, ge=1, le=4500)):
    if not _valid_code(code) or not _valid_period(period):
        return _unknown()
    try:
        return {
            "state": _build_state(),
            "kline": {"code": code, "period": period,
                      "bars": _app_kline_bars(code, period, limit)},
            "forecast": await _forecast_seven(code),
            "server": _server(),
        }
    except Exception as e:  # noqa: BLE001
        return _fail(e, "app/snapshot")


@app.get("/api/sync/check")
async def sync_check():
    try:
        return _run_check()
    except Exception as e:  # noqa: BLE001
        return _fail(e, "sync/check")


@app.post("/api/sync/repair")
async def sync_repair():
    batches = []
    added = 0
    try:
        for code in PRODUCT_BY_CODE:
            minute = store.get_minute(code, 1200)
            day = store.get_daily(code, 4500)
            for period in APP_REPAIR_PERIODS:
                rec = {"code": code, "period": period, "written": 0, "added": 0}
                try:
                    if period == "1m":
                        rec["written"] = len(minute)   # 源站落库基准本身，不写回
                    elif period == "1d":
                        rec["written"] = len(day)
                    else:
                        agg = store.aggregate_bars(
                            minute, _MULTI_STEP[period] * 60_000)
                        rec["written"] = len(agg)
                        for i in range(0, len(agg), REPAIR_BATCH):
                            chunk = agg[i:i + REPAIR_BATCH]
                            rec["added"] += store.upsert_multi(
                                code, period, chunk, only_new=True)
                            await asyncio.sleep(REPAIR_RATE)
                    added += rec["added"]
                except Exception as e:  # 单批失败登记后继续跑完其余批次
                    log.exception("sync/repair %s %s 失败", code, period)
                    rec["error"] = type(e).__name__
                batches.append(rec)
        return {
            "ok": True,
            "batches": batches,
            "added": added,
            "note": ("按「品种×周期」拆批回补：把本地累计 1m 按自然时间桶聚合成 "
                     "5/15/30/60m 增量写入 kline_multi，每批 ≤300 根、批间限速、"
                     "自动连续跑完；1m/1d 为源站落库基准序列本身不写回；"
                     "1m 不插值不伪造；主键幂等，重复调用零新增。"
                     f"{NOTE_1M}。{NOTE_BUCKET}"),
        }
    except Exception as e:  # noqa: BLE001
        return _fail(e, "sync/repair")


# =====================================================================
#  启动自检补全 / 命中率历史 / 大模型（新增）
# =====================================================================

@app.get("/api/autofill/status")
async def autofill_status():
    return autofill.get_report()


@app.post("/api/autofill/run")
async def autofill_run():
    try:
        return await autofill.check_and_fill()
    except Exception as e:  # noqa: BLE001
        return _fail(e, "autofill/run")


def _history_for_code(code: str, since_ms: int) -> list[dict]:
    rows = []
    for key, label, minutes, _, _ in APP_HORIZONS:
        logs = store.forecast_log_since(code, key, since_ms)
        total = hits = 0
        for lg in logs:
            pred = lg["dir"]
            if pred == 0 or lg["price"] is None:
                continue
            actual = store.price_at_or_after(code, lg["ts"] + minutes * 60_000)
            if actual is None:
                continue
            act_dir = 1 if actual > lg["price"] else (-1 if actual < lg["price"] else 0)
            if act_dir == 0:
                continue
            total += 1
            if act_dir == pred:
                hits += 1
        rows.append({
            "key": key, "label": label, "minutes": minutes,
            "total": total, "hits": hits,
            "accuracy": round(hits / total, 4) if total else None,
        })
    return rows


@app.get("/api/history")
async def history(code: str = Query("AL"),
                  days: int = Query(30, ge=1, le=365)):
    if code != "ALL" and code not in PRODUCT_BY_CODE:
        return _unknown()
    since = int(time.time() * 1000) - days * 86_400_000
    if code == "ALL":
        return {"code": "ALL", "days": days,
                "per_product": {c: _history_for_code(c, since)
                                for c in PRODUCT_BY_CODE}}
    return {"code": code, "days": days, "horizons": _history_for_code(code, since)}


@app.get("/api/llm/config")
async def llm_config_get():
    return llm.get_config()


@app.post("/api/llm/config")
async def llm_config_post(payload: dict):
    try:
        base = str(payload.get("base", "") or "")
        model = str(payload.get("model", "") or "")
        key = payload.get("key")
        key = str(key) if isinstance(key, str) else None
        return llm.set_config(base, model, key)
    except Exception as e:  # noqa: BLE001
        return _fail(e, "llm/config")


@app.post("/api/llm/analyze")
async def llm_analyze(payload: dict):
    try:
        code = payload.get("code", "AL")
        if code not in PRODUCT_BY_CODE:
            return _unknown()
        period = payload.get("period", "1m")
        return await llm.analyze(code, period)
    except Exception as e:  # noqa: BLE001
        return _fail(e, "llm/analyze")


def _origin_allowed(origin: str | None) -> bool:
    """允许无 Origin（本机脚本/websockets 客户端）或指向本机的 Origin。"""
    if not origin:
        return True
    host = (urlparse(origin).hostname or "").lower()
    return host in ("localhost", "127.0.0.1", "::1", "[::1]")


@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket):
    global _ws_connections
    origin = ws.headers.get("origin")
    if not _origin_allowed(origin):
        log.warning("ws 拒绝非本机 Origin: %s", origin)
        await ws.close(code=1008)
        return
    if _ws_connections >= _WS_MAX_CONNECTIONS:
        log.warning("ws 连接数已达上限 %d，拒绝新连接", _WS_MAX_CONNECTIONS)
        await ws.close(code=1008)
        return
    _ws_connections += 1
    try:
        await ws.accept()
        while True:
            payload = {
                "type": "tick",
                "ts": datetime.now(timezone.utc).isoformat(),
                "quotes": collector.state.quotes,
                "forecast": {
                    c: {
                        "price": f.get("price"),
                        "overall": f.get("overall"),
                        "score": f.get("score"),
                        "horizons": f.get("horizons"),
                    }
                    for c, f in collector.state.forecast.items()
                },
            }
            await ws.send_text(json.dumps(payload, ensure_ascii=False))
            await asyncio.sleep(3)
    except WebSocketDisconnect:
        pass
    except asyncio.CancelledError:
        raise
    except Exception:  # noqa: BLE001
        log.exception("ws 连接异常")   # 记录异常日志而非静默 pass
    finally:
        _ws_connections -= 1


if STATIC_DIR.joinpath("index.html").exists():
    app.mount("/", StaticFiles(directory=STATIC_DIR, html=True), name="static")
else:  # pragma: no cover
    @app.get("/")
    async def root():
        return {"service": "futures-forecast", "docs": "/docs"}
