"""Background collectors: realtime snapshots, klines, news, forecast refresh."""
from __future__ import annotations

import asyncio
import logging
import time
from datetime import datetime, timezone

from . import store
from .config import (KLINE_INTERVAL, NEWS_INTERVAL, PRODUCT_BY_CODE,
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

    def snapshot_meta(self) -> dict:
        return {
            "last_quote_ts": self.last_quote_ts,
            "last_kline_ts": self.last_kline_ts,
            "last_news_ts": self.last_news_ts,
            "last_forecast_ts": self.last_forecast_ts,
            "errors": self.errors[-5:],
        }


state = State()


def _err(msg: str) -> None:
    state.errors.append(f"{datetime.now(timezone.utc):%H:%M:%S} {msg}")
    state.errors = state.errors[-20:]


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
        except Exception as e:  # noqa: BLE001
            _err(f"snapshot: {e}")
        await asyncio.sleep(SNAPSHOT_INTERVAL)


async def tick_klines() -> None:
    """Refresh minute + daily klines and recompute forecasts."""
    while True:
        try:
            mins = await sina.fetch_all_minute(1)
            days = await sina.fetch_all_daily()
            for code in PRODUCT_BY_CODE:
                if mins.get(code):
                    store.upsert_minute(code, mins[code])
                if days.get(code):
                    store.upsert_daily(code, days[code])
                # serve the DB (accumulated) merged with fresh bars
                m = _drop_future(store.get_minute(code, 1200) or mins.get(code, []))
                d = _drop_future(store.get_daily(code, 4500) or days.get(code, []))
                state.minute[code] = m
                state.daily[code] = d
                p = PRODUCT_BY_CODE[code]
                state.forecast[code] = forecast_one(code, p["name"], m, d)
            state.last_kline_ts = time.time()
            state.last_forecast_ts = state.last_kline_ts
        except Exception as e:  # noqa: BLE001
            _err(f"kline: {e}")
        await asyncio.sleep(KLINE_INTERVAL)


async def tick_news() -> None:
    while True:
        try:
            items = await news.fetch_all()
            if items:
                store.upsert_news(items)
                state.news = store.get_news(120)
                state.last_news_ts = time.time()
        except Exception as e:  # noqa: BLE001
            _err(f"news: {e}")
        await asyncio.sleep(NEWS_INTERVAL)


async def bootstrap() -> None:
    """Load persisted data so the UI has content immediately on cold start."""
    for code in PRODUCT_BY_CODE:
        state.minute[code] = _drop_future(store.get_minute(code, 1200))
        state.daily[code] = _drop_future(store.get_daily(code, 4500))
    state.news = store.get_news(120)


async def run_all() -> None:
    await bootstrap()
    await asyncio.gather(tick_snapshots(), tick_klines(), tick_news())
