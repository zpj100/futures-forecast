"""Background collectors: realtime snapshots, klines, news, forecast refresh."""
from __future__ import annotations

import asyncio
import logging
import re
import time
from datetime import datetime, timezone

from . import store
from .config import (APP_HORIZONS, KLINE_INTERVAL, NEWS_INTERVAL, PRODUCT_BY_CODE,
                     SNAPSHOT_INTERVAL)
from .datasource import news, sina
from .datasource.sina import _drop_future
from .model.forecaster import forecast_one

log = logging.getLogger("collector")


class State:
    """In-memory cache shared with the API layer."""

    def __init__(self) -> None:
        self.quotes: dict[str, dict] = {}
        self.minute: dict[str, list[dict]] = {}
        self.daily: dict[str, list[dict]] = {}
        self.forecast: dict[str, dict] = {}
        self.news: list[dict] = []
        self.last_quote_ts: float = 0.0
        self.last_kline_ts: float = 0.0
        self.last_news_ts: float = 0.0
        self.last_forecast_ts: float = 0.0
        self.errors: list[str] = []
        self.native: dict[str, dict] = {}   # code -> {step: [native bars ms-ts]}

    def snapshot_meta(self) -> dict:
        return {
            "last_quote_ts": self.last_quote_ts,
            "last_kline_ts": self.last_kline_ts,
            "last_news_ts": self.last_news_ts,
            "last_forecast_ts": self.last_forecast_ts,
            "errors": self.errors[-5:],
        }


state = State()


_URL_RE = re.compile(r"https?://\S+", re.IGNORECASE)


def _brief(e: BaseException) -> str:
    """把异常转成不含敏感 URL 的短摘要（HTTP 503 / timeout / 类名）。"""
    status = getattr(getattr(e, "response", None), "status_code", None)
    if status is not None:
        return f"HTTP {status}"
    low = str(e).lower()
    if (isinstance(e, (asyncio.TimeoutError, TimeoutError))
            or "timeout" in low or "timed out" in low):
        return "timeout"
    return type(e).__name__


def _err(summary: str) -> None:
    """state.errors 只保留脱敏摘要；完整异常（含第三方 URL）由调用方写日志。"""
    clean = _URL_RE.sub("<url>", " ".join(str(summary).split()))
    state.errors.append(f"{datetime.now(timezone.utc):%H:%M:%S} {clean[:120]}")
    state.errors = state.errors[-20:]


async def _fetch_native(code: str) -> dict[int, list[dict]]:
    """抓取内盘原生 5/15/30/60m 序列（Sina 直采），归一化为 ms ts，返回 {step: bars}。

    外盘(LME)原生分钟接口仅返回当前交易时段、无法回溯，不具备多周期历史价值，
    故只对内盘调用；外盘预测仍走既有「1m 聚合」路径（样本不足时如实标数据不足）。
    """
    out: dict[int, list[dict]] = {}
    for step in (5, 15, 30, 60):
        try:
            bars = await sina.fetch_minute(code, step)
        except Exception as e:  # noqa: BLE001
            _report(f"native {code} {step}m", e)
            continue
        norm = []
        for b in bars:
            ts = store.bar_ts_ms(b.get("t") or b.get("ts"))
            if ts is None or b.get("c") is None:
                continue
            norm.append({"ts": ts, "o": b.get("o"), "h": b.get("h"),
                         "l": b.get("l"), "c": b.get("c"), "v": b.get("v")})
        if norm:
            out[step] = norm
    return out


def _report(context: str, exc: BaseException) -> None:
    """完整异常只写 logging.warning，对外只登记脱敏摘要（meta.errors 字段不变）。"""
    log.warning("%s 失败: %s", context, exc)
    _err(f"{context}: {_brief(exc)}")


def _log_forecast(code: str, fc: dict) -> None:
    """把七档预测快照幂等写入 forecast_log，供 /api/history 命中率统计。"""
    if not fc or "horizons" not in fc:
        return
    ts = int(time.time() * 1000)
    for h in fc["horizons"]:
        store.log_forecast(code, ts, h.get("key"), h.get("minutes", 0),
                          h.get("direction"), h.get("score"), h.get("price"))


async def tick_snapshots() -> None:
    """Capture a realtime quote snapshot every few seconds per product."""
    while True:
        try:
            quotes = await sina.fetch_realtime()
            now = time.time()
            for code, q in quotes.items():
                state.quotes[code] = q
                store.add_snapshot(code, q, int(now))
            if quotes:
                state.last_quote_ts = now
        except asyncio.CancelledError:
            raise
        except Exception as e:  # noqa: BLE001
            _report("snapshot", e)
        await asyncio.sleep(SNAPSHOT_INTERVAL)


async def tick_klines() -> None:
    """Refresh minute + daily klines and recompute forecasts."""
    while True:
        errors: list[str] = []
        try:
            mins = await sina.fetch_all_minute(1, errors=errors)
            days = await sina.fetch_all_daily(errors=errors)
            for msg in errors:
                _err(f"kline: {msg}")
            for code in PRODUCT_BY_CODE:
                if mins.get(code):
                    store.upsert_minute(code, mins[code])
                if days.get(code):
                    store.upsert_daily(code, days[code])
                # serve the DB (accumulated) merged with fresh bars
                m = _drop_future(store.get_minute(code, 1200) or mins.get(code, []))
                d = _drop_future(store.get_daily(code, 4500) or days.get(code, []))
                # 本次无数据时不覆盖 state 里上次成功的数据
                if m or code not in state.minute:
                    state.minute[code] = m
                if d or code not in state.daily:
                    state.daily[code] = d
                p = PRODUCT_BY_CODE[code]
                mm, dd = state.minute[code], state.daily[code]
                if not mm and not dd and state.forecast.get(code):
                    continue      # 保留上次成功预测
                # 内盘原生多周期直采（根治预测样本不足）：不经 1m 聚合，
                # 直接以 Sina 原生 5/15/30/60m 序列喂给预测（外盘仅当前时段，跳过）。
                native = {}
                if p["market"] == "inner":
                    native = await _fetch_native(code)
                    for step, bars in native.items():
                        store.upsert_native(code, f"{step}m", bars)
                state.native[code] = native
                # 多周期增量落库：把本地累计 1m 按自然时间桶聚合成 5/15/30/60m，
                # 只写尾部新桶/更新进行中桶，不每轮全量重写（1m 不插值、不伪造）；
                # 外盘/无原生周期仍依赖此聚合，内盘预测优先用原生（见 forecast_one）。
                if mm:
                    store.sync_multi_incremental(code, mm)
                # CPU 密集建模放线程，避免阻塞事件循环导致 WS/API 停摆
                state.forecast[code] = await asyncio.to_thread(
                    forecast_one, code, p["name"], mm, dd, APP_HORIZONS,
                    native=state.native.get(code))
                # 预测历史落库（七档），供命中率统计；并定期清理 90 天前日志
                _log_forecast(code, state.forecast[code])
                store.truncate_forecast_log(int(time.time() * 1000)
                                           - 90 * 86400_000)
            state.last_kline_ts = time.time()
            state.last_forecast_ts = state.last_kline_ts
        except asyncio.CancelledError:
            raise
        except Exception as e:  # noqa: BLE001
            _report("kline", e)
        await asyncio.sleep(KLINE_INTERVAL)


async def tick_news() -> None:
    while True:
        errors: list[str] = []
        try:
            items = await news.fetch_all(errors=errors)
            for msg in errors:
                _err(f"news: {msg}")
            if items:
                store.upsert_news(items)
                state.news = store.get_news(120)
                state.last_news_ts = time.time()
        except asyncio.CancelledError:
            raise
        except Exception as e:  # noqa: BLE001
            _report("news", e)
        await asyncio.sleep(NEWS_INTERVAL)


async def bootstrap() -> None:
    """Load persisted data so the UI has content immediately on cold start."""
    for code in PRODUCT_BY_CODE:
        state.minute[code] = _drop_future(store.get_minute(code, 1200))
        state.daily[code] = _drop_future(store.get_daily(code, 4500))
        # 冷启动也把库里已有的 1m 历史增量聚合成多周期，避免新增表长期为空
        if state.minute[code]:
            store.sync_multi_incremental(code, state.minute[code])
        # 冷启动直采内盘原生多周期，使首轮预测即可用原生序列（外盘跳过）
        if PRODUCT_BY_CODE[code]["market"] == "inner":
            try:
                native = await _fetch_native(code)
                for step, bars in native.items():
                    store.upsert_native(code, f"{step}m", bars)
                state.native[code] = native
            except Exception as e:  # noqa: BLE001
                _report(f"bootstrap native {code}", e)
    state.news = store.get_news(120)


async def run_all() -> None:
    await bootstrap()
    await asyncio.gather(tick_snapshots(), tick_klines(), tick_news())
