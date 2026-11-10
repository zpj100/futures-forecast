"""服务端启动自检补全：依据期货交易日历检测各品种 1m K 线缺口并自动补齐。

设计要点（2026-11）：
  * 启动时（collector 之前）调用 check_and_fill()：逐品种取库内最新 1m ts，
    向后枚举「应交易」时段（calendar.trading_sessions，含跨午夜夜盘、跳过周末），
    逐段比对实际根数与期望根数；覆盖率 < 阈值（默认 0.6）即判为缺失/部分缺失。
  * 对「落在源站 1m 1023 根窗口内（时段结束 ≥ now−1023 分钟）」的缺口，触发一次
    fetch_all_minute(1)+upsert+sync_multi_incremental 把最新 1023 根拉回并补齐派生周期；
    窗口外的历史缺口物理不可补（源站无分页/日期参数），如实标注 unrecoverable。
  * 报告存模块全局 LAST_REPORT，经 /api/health 与 /api/autofill/status 暴露，
    并支持 POST /api/autofill/run 手动重跑。
"""
from __future__ import annotations

import asyncio
import logging
import time
from datetime import datetime, timedelta, timezone

from . import calendar, store
from .config import PRODUCT_BY_CODE
from .datasource import sina

log = logging.getLogger("autofill")

CST = timezone(timedelta(hours=8))

# 自检扫描窗口：最近 30 天（覆盖绝大多数可回溯场景，且不会因无限历史拖慢启动）。
_SCAN_DAYS = 30
# 源站 1m 窗口上限（根）：超出此窗口的历史缺口不可补。
_REACH_MINUTES = 1023
# 单段覆盖率阈值：实际/期望低于此值判为缺失。
_COVERAGE_THRESHOLD = 0.6
# 重新拉取两轮之间限速，避免启动瞬间对源站造成压力。
_FILL_RATE = 0.02

# 启动自检报告（模块全局，供接口读取）。
LAST_REPORT: dict = {
    "ok": False, "ts": 0, "scan_days": _SCAN_DAYS,
    "reach_minutes": _REACH_MINUTES, "coverage_threshold": _COVERAGE_THRESHOLD,
    "filled_reachable": 0, "products": [], "note": "尚未执行启动自检",
}


def _scan_product(code: str, now_ms: int) -> dict:
    """扫描单品种近 _SCAN_DAYS 天的交易时段覆盖率，返回该品种报告。"""
    last = store.minute_latest_ts(code)
    first_run = last == 0
    scan_start_ms = max(last, now_ms - _SCAN_DAYS * 86400_000) if last else \
        now_ms - _SCAN_DAYS * 86400_000
    start_dt = datetime.fromtimestamp(scan_start_ms / 1000, CST)
    end_dt = datetime.fromtimestamp(now_ms / 1000, CST)

    sessions = calendar.trading_sessions(start_dt, end_dt)
    reach_ms = now_ms - _REACH_MINUTES * 60_000

    missing: list[dict] = []
    unrecoverable: list[dict] = []
    covered = 0
    for ss, se in sessions:
        sms = int(ss.timestamp() * 1000)
        ems = int(se.timestamp() * 1000)
        expected = calendar.expected_minute_count(ss, se)
        if expected <= 0:
            continue
        actual = store.minute_count_in_range(code, sms, ems)
        cov = actual / expected
        seg = {"start": sms, "end": ems, "expected": expected, "actual": actual}
        if cov >= _COVERAGE_THRESHOLD:
            covered += 1
        elif ems >= reach_ms:
            seg["reachable"] = True
            missing.append(seg)
        else:
            unrecoverable.append(seg)

    return {
        "code": code, "last_ts": last, "first_run": first_run,
        "sessions": len(sessions), "covered": covered,
        "missing": missing, "unrecoverable": unrecoverable,
        "missing_count": len(missing),
        "unrecoverable_count": len(unrecoverable),
    }


async def _fetch_and_upsert() -> None:
    """触发一次全品种最新 1m/日线拉取并落库 + 派生周期增量聚合。

    与 collector.tick_klines 的单轮逻辑一致，但仅用于启动补齐，不进入常驻循环。
    """
    errors: list[str] = []
    mins = await sina.fetch_all_minute(1, errors=errors)
    days = await sina.fetch_all_daily(errors=errors)
    for msg in errors:
        log.warning("autofill 拉取警告: %s", msg)
    for code in PRODUCT_BY_CODE:
        if mins.get(code):
            store.upsert_minute(code, mins[code])
        if days.get(code):
            store.upsert_daily(code, days[code])
        if mins.get(code):
            # 增量聚合 5/15/30/60m，窗口内缺口随最新 1023 根一并补齐
            store.sync_multi_incremental(code, mins[code])


async def check_and_fill() -> dict:
    """启动自检 + 补全：返回报告并写入 LAST_REPORT。"""
    global LAST_REPORT
    now_ms = int(time.time() * 1000)

    # 先扫描，判断是否存在窗口内缺口
    pre = [_scan_product(code, now_ms) for code in PRODUCT_BY_CODE]
    reachable_missing = sum(p["missing_count"] for p in pre)

    filled = 0
    if reachable_missing > 0:
        try:
            await _fetch_and_upsert()
            filled = reachable_missing
            # 拉取后重扫，更新实际覆盖率（缺口已被最新 1023 根补齐）
            post = [_scan_product(code, now_ms) for code in PRODUCT_BY_CODE]
        except Exception:  # noqa: BLE001
            log.exception("autofill 补齐拉取失败")
            post = pre
    else:
        post = pre

    LAST_REPORT = {
        "ok": True, "ts": now_ms, "scan_days": _SCAN_DAYS,
        "reach_minutes": _REACH_MINUTES,
        "coverage_threshold": _COVERAGE_THRESHOLD,
        "filled_reachable": filled, "products": post,
        "note": (calendar.session_coverage_note()
                 + (" 本次启动已对窗口内 %d 个缺失时段触发一次补齐拉取。" % filled
                    if filled else " 窗口内无缺失时段，无需补齐。")
                 + " 窗口外历史缺口（源站 1m 仅 1023 根、无分页）物理不可补，如实标注。"),
    }
    log.info("autofill 自检完成：窗口内缺失时段 %d，已补齐 %d", reachable_missing, filled)
    return LAST_REPORT


def get_report() -> dict:
    return LAST_REPORT
