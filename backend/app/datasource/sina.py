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
import logging
from datetime import datetime, timedelta, timezone
from typing import Iterable

from ..config import PRODUCT_BY_CODE
from .http_util import get_text, jsonp_to_json

CST = timezone(timedelta(hours=8))
log = logging.getLogger("sina")

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


_NOW_CST = lambda: datetime.now(CST)


def _parse_ts(value) -> datetime | None:
    """把 bar 时间戳解析为带时区的 datetime；无法解析返回 None。

    兼容 "YYYY-MM-DD HH:MM:SS"、"YYYY-MM-DD" 以及 ISO 8601（T / 时区 / Z）。
    """
    s = str(value or "").strip()
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
    return dt


def _drop_future(bars: list[dict], slack_hours: int = 2) -> list[dict]:
    """Sina appends a placeholder bar dated at the next session open.

    It is not real data (same price, tiny volume), so drop bars timestamped
    clearly in the future to keep the chart and features honest. 日线只有
    "YYYY-MM-DD"（10 位）、分钟线含完整时间，两种格式都要能解析。
    """
    limit = _NOW_CST() + timedelta(hours=slack_hours)
    out = []
    for b in bars:
        # live API rows use "t"; DB rows use "ts"
        dt = _parse_ts(b.get("t") or b.get("ts") or "")
        if dt is None:            # 无法解析时保守保留，避免误删真实数据
            out.append(b)
        elif dt <= limit:
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
        # 字段数校验：短响应直接跳过该品种并记录日志，避免未校验索引抛
        # IndexError 导致整批（三品种）快照全部丢失。内盘用到 parts[14]、
        # 外盘用到 parts[13]，按各自实际字段数取阈值。
        need = 15 if sym.startswith("nf_") else 14
        if len(parts) < need:
            log.warning("realtime %s 字段数不足 (%d<%d)，跳过该品种",
                        sym, len(parts), need)
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
        bars = [
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
    else:
        url = SINA_GLOBAL_DAY.format(sym=p["day_sym"])
        raw = jsonp_to_json(await get_text(url))
        bars = [
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
    # 日线同样会带下一交易日的未来占位 bar，入库/入 state 前统一剔除。
    return _drop_future(bars)


def _fetch_error(errors: list[str] | None, code: str, kind: str,
                 exc: BaseException) -> None:
    """异常记详细日志（含 URL），把脱敏摘要登记到错误出参供 collector 记录。"""
    status = getattr(getattr(exc, "response", None), "status_code", None)
    if status is not None:
        reason = f"HTTP {status}"
    else:
        low = str(exc).lower()
        if (isinstance(exc, (asyncio.TimeoutError, TimeoutError))
                or "timeout" in low or "timed out" in low):
            reason = "timeout"
        else:
            reason = type(exc).__name__
    msg = f"{code} {kind}: {reason}"
    log.warning("sina %s 拉取失败: %s", msg, exc)   # 详细异常（含 URL）只写日志
    if errors is not None:
        errors.append(msg)


async def fetch_all_minute(period: int = 1,
                           errors: list[str] | None = None) -> dict[str, list[dict]]:
    codes = list(PRODUCT_BY_CODE)
    results = await asyncio.gather(
        *(fetch_minute(c, period) for c in codes), return_exceptions=True
    )
    out: dict[str, list[dict]] = {}
    for c, r in zip(codes, results):
        if isinstance(r, list):
            out[c] = r
        else:
            out[c] = []
            _fetch_error(errors, c, "minute",
                         r if isinstance(r, BaseException) else Exception(str(r)))
    return out


async def fetch_all_daily(errors: list[str] | None = None) -> dict[str, list[dict]]:
    codes = list(PRODUCT_BY_CODE)
    results = await asyncio.gather(
        *(fetch_daily(c) for c in codes), return_exceptions=True
    )
    out: dict[str, list[dict]] = {}
    for c, r in zip(codes, results):
        if isinstance(r, list):
            out[c] = r
        else:
            out[c] = []
            _fetch_error(errors, c, "daily",
                         r if isinstance(r, BaseException) else Exception(str(r)))
    return out
