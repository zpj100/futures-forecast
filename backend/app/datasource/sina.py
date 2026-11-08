"""Sina quote datasource: realtime tick, minute klines, daily klines.

Sina public endpoints used (no key required):
  * realtime : https://hq.sinajs.cn/list=nf_AL0,nf_AO0,hf_AHD
  * minute   : /futures/api/jsonp.php/.../InnerFuturesNewService.getFewMinLine
               /futures/api/jsonp.php/.../GlobalFuturesService.getGlobalFuturesMinLine
  * daily    : InnerFuturesNewService.getDailyKLine
               GlobalFuturesService.getGlobalFuturesDailyKLine

Granularity notes (verified 2026-11):
  * inner minute API returns at most 1023 bars (~3 trading days of 1m data).
  * global (LME) minute API returns the current session only.
  * daily history reaches back to 2005 (AL0) / 2023 (AO0) / 2016 (AHD).
Therefore "second level" history has to be accumulated locally over time.
"""
from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone

CST = timezone(timedelta(hours=8))
from typing import Iterable

from ..config import PRODUCT_BY_CODE
from .http_util import get_text, jsonp_to_json

SINA_RT = "https://hq.sinajs.cn/list={syms}"
SINA_INNER_MIN = (
    "https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20_=/"
    "InnerFuturesNewService.getFewMinLine?symbol={sym}&type={period}"
)
SINA_INNER_DAY = (
    "https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20_=/"
    "InnerFuturesNewService.getDailyKLine?symbol={sym}"
)
SINA_GLOBAL_MIN = (
    "https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20_=/"
    "GlobalFuturesService.getGlobalFuturesMinLine?symbol={sym}&type={period}"
)
SINA_GLOBAL_DAY = (
    "https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20_=/"
    "GlobalFuturesService.getGlobalFuturesDailyKLine?symbol={sym}"
)


def _f(v: str | float | None) -> float | None:
    if v is None or v == "":
        return None
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


_NOW_CST = lambda: datetime.now(timezone(timedelta(hours=8)))


def _drop_future(bars: list[dict], slack_hours: int = 2) -> list[dict]:
    """Sina appends a placeholder bar dated at the next session open.

    It is not real data (same price, tiny volume), so drop bars timestamped
    clearly in the future to keep the chart and features honest.
    """
    limit = _NOW_CST() + timedelta(hours=slack_hours)
    out = []
    for b in bars:
        # live API rows use "t"; DB rows use "ts"
        t = b.get("t") or b.get("ts") or ""
        try:
            dt = datetime.strptime(t, "%Y-%m-%d %H:%M:%S").replace(tzinfo=CST)
        except ValueError:
            out.append(b)
            continue
        if dt <= limit:
            out.append(b)
    return out


def parse_realtime(text: str) -> dict[str, dict]:
    """Parse hq.sinajs.cn response into {code: quote}."""
    out: dict[str, dict] = {}
    for line in text.splitlines():
        line = line.strip()
        if not line.startswith("var hq_str_"):
            continue
        head, _, rest = line.partition("=")
        sym = head[len("var hq_str_"):].strip()
        payload = rest.strip().strip(";").strip('"')
        if not payload:
            continue
        parts = payload.split(",")
        code = None
        for p in PRODUCT_BY_CODE.values():
            if p["sina_rt"] == sym:
                code = p["code"]
                break
        if code is None:
            continue

        q: dict = {"code": code, "ts": datetime.now(timezone.utc).isoformat()}
        if sym.startswith("nf_"):
            # 内盘: name,?,open,high,low,?,last,?,bid,ask,...
            q.update(
                name=parts[0],
                open=_f(parts[2]),
                high=_f(parts[3]),
                low=_f(parts[4]),
                last=_f(parts[6]),
                bid=_f(parts[8]) if len(parts) > 8 else None,
                ask=_f(parts[9]) if len(parts) > 9 else None,
                volume=_f(parts[13]) if len(parts) > 13 else None,
                open_interest=_f(parts[14]) if len(parts) > 14 else None,
            )
            q["prev_close"] = _f(parts[10]) if len(parts) > 10 else None
        else:
            # 外盘: last,?,bid,ask,high,low,time,prev_settle?,open,...
            q.update(
                name=parts[12] if len(parts) > 12 else sym,
                last=_f(parts[0]),
                bid=_f(parts[2]) if len(parts) > 2 else None,
                ask=_f(parts[3]) if len(parts) > 3 else None,
                high=_f(parts[4]) if len(parts) > 4 else None,
                low=_f(parts[5]) if len(parts) > 5 else None,
                open=_f(parts[8]) if len(parts) > 8 else None,
                prev_close=_f(parts[7]) if len(parts) > 7 else None,
                volume=_f(parts[13]) if len(parts) > 13 else None,
            )
        if q.get("last") is None:
            continue
        out[code] = q
    return out


async def fetch_realtime(codes: Iterable[str] | None = None) -> dict[str, dict]:
    wanted = list(codes) if codes else list(PRODUCT_BY_CODE)
    syms = ",".join(PRODUCT_BY_CODE[c]["sina_rt"] for c in wanted)
    text = await get_text(SINA_RT.format(syms=syms), encoding="gbk")
    data = parse_realtime(text)
    return {c: data[c] for c in wanted if c in data}


async def fetch_minute(code: str, period: int = 1) -> list[dict]:
    """Return list of {t, o, h, l, c, v} minute bars."""
    p = PRODUCT_BY_CODE[code]
    if p["market"] == "inner":
        url = SINA_INNER_MIN.format(sym=p["minute_sym"], period=period)
        raw = jsonp_to_json(await get_text(url))
        rows = raw if isinstance(raw, list) else []
        bars = [
            {
                "t": r.get("d"),
                "o": _f(r.get("o")),
                "h": _f(r.get("h")),
                "l": _f(r.get("l")),
                "c": _f(r.get("c")),
                "v": _f(r.get("v")),
            }
            for r in rows
            if r.get("d")
        ]
        return _drop_future(bars)

    url = SINA_GLOBAL_MIN.format(sym=p["minute_sym"], period=period)
    raw = jsonp_to_json(await get_text(url))
    bars: list[dict] = []
    for group in raw.values() if isinstance(raw, dict) else []:
        for row in group or []:
            # first row: [date, price, mkt, ?, time, price, vol, ?, avg, full_dt]
            # later : [time, price, vol, ?, avg, full_dt]
            if len(row) >= 6:
                ts = row[-1]
                price = _f(row[1])
                vol = _f(row[2])
                bars.append({"t": ts, "o": price, "h": price, "l": price,
                             "c": price, "v": vol})
    bars.sort(key=lambda b: b["t"] or "")
    return _drop_future(bars)


async def fetch_daily(code: str) -> list[dict]:
    p = PRODUCT_BY_CODE[code]
    if p["market"] == "inner":
        url = SINA_INNER_DAY.format(sym=p["day_sym"])
        raw = jsonp_to_json(await get_text(url))
        return [
            {
                "t": r.get("d"),
                "o": _f(r.get("o")),
                "h": _f(r.get("h")),
                "l": _f(r.get("l")),
                "c": _f(r.get("c")),
                "v": _f(r.get("v")),
                "oi": _f(r.get("p")),
            }
            for r in (raw if isinstance(raw, list) else [])
            if r.get("d") and r.get("c")
        ]

    url = SINA_GLOBAL_DAY.format(sym=p["day_sym"])
    raw = jsonp_to_json(await get_text(url))
    return [
        {
            "t": r.get("date"),
            "o": _f(r.get("open")),
            "h": _f(r.get("high")),
            "l": _f(r.get("low")),
            "c": _f(r.get("close")),
            "v": _f(r.get("volume")),
            "oi": _f(r.get("position")),
        }
        for r in (raw if isinstance(raw, list) else [])
        if r.get("date") and r.get("close")
    ]


async def fetch_all_minute(period: int = 1) -> dict[str, list[dict]]:
    codes = list(PRODUCT_BY_CODE)
    results = await asyncio.gather(
        *(fetch_minute(c, period) for c in codes), return_exceptions=True
    )
    out: dict[str, list[dict]] = {}
    for c, r in zip(codes, results):
        out[c] = r if isinstance(r, list) else []
    return out


async def fetch_all_daily() -> dict[str, list[dict]]:
    codes = list(PRODUCT_BY_CODE)
    results = await asyncio.gather(
        *(fetch_daily(c) for c in codes), return_exceptions=True
    )
    out: dict[str, list[dict]] = {}
    for c, r in zip(codes, results):
        out[c] = r if isinstance(r, list) else []
    return out
