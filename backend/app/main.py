"""FastAPI application: REST + WebSocket."""
from __future__ import annotations

import asyncio
import json
import logging
from contextlib import asynccontextmanager
from datetime import datetime, timezone

from fastapi import FastAPI, Query, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from . import collector, store
from .config import HORIZONS, PRODUCTS, PRODUCT_BY_CODE, STATIC_DIR
from .datasource.http_util import close_client
from .datasource.sina import _drop_future

logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("api")


@asynccontextmanager
async def lifespan(app: FastAPI):
    store.init()
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


@app.get("/api/health")
async def health():
    return {
        "ok": True,
        "ts": datetime.now(timezone.utc).isoformat(),
        "products": [p["code"] for p in PRODUCTS],
        "meta": collector.state.snapshot_meta(),
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
        {"key": k, "label": l, "minutes": m} for k, l, m in HORIZONS]}


@app.get("/api/quote")
async def quote():
    return {"quotes": collector.state.quotes,
            "meta": collector.state.snapshot_meta()}


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
    agg: list[dict] = []
    for i in range(0, len(base), step):
        chunk = base[i:i + step]
        if not chunk:
            continue
        agg.append({
            "ts": chunk[-1]["ts"],
            "o": chunk[0]["o"],
            "h": max(b["h"] or b["c"] for b in chunk),
            "l": min(b["l"] or b["c"] for b in chunk),
            "c": chunk[-1]["c"],
            "v": sum(b.get("v") or 0 for b in chunk),
        })
    return {"code": code, "period": period, "bars": agg[-limit:]}


@app.get("/api/snapshots/{code}")
async def snapshots(code: str, limit: int = Query(900, ge=10, le=5000)):
    if code not in PRODUCT_BY_CODE:
        return JSONResponse({"error": "unknown code"}, status_code=404)
    return {"code": code, "points": store.snapshot_range(code, limit)}


@app.get("/api/ticks/{code}")
async def ticks(code: str,
                bucket: int = Query(0, ge=0, le=3600),
                limit: int = Query(600, ge=10, le=5000)):
    """Price series built from the locally accumulated snapshot archive.

    bucket=0 gives raw capture points (one per 3s snapshot); bucket>0 gives
    OHLC bars over that many seconds. This is the only second-level source:
    public APIs expose no second-level history, so the series starts when the
    service first ran.
    """
    if code not in PRODUCT_BY_CODE:
        return JSONResponse({"error": "unknown code"}, status_code=404)
    return {"code": code, "bucket": bucket,
            "bars": store.tick_series(code, bucket, limit)}


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


@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket):
    await ws.accept()
    try:
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
    except Exception:  # noqa: BLE001
        pass


if STATIC_DIR.joinpath("index.html").exists():
    app.mount("/", StaticFiles(directory=STATIC_DIR, html=True), name="static")
else:  # pragma: no cover
    @app.get("/")
    async def root():
        return {"service": "futures-forecast", "docs": "/docs"}
