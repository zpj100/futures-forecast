"""期货交易日历：枚举沪铝/氧化铝（SHFE）与伦铝（LME）应交易时段。

口径（2026-11 确认）：
  * 日盘 9:00–15:00，含交易所规定的休市子窗口：
      9:00–10:15、10:30–11:30、13:30–15:00
    （10:15–10:30、11:30–13:30、15:00–21:00 为休市，不产生 1m K 线）
  * 夜盘 21:00–次日 01:00（跨午夜，连续 4 小时）
  * 跳过周末（周六=5、周日=6）；法定节假日暂未纳入，标注为已知限制。

所有时间为北京时间（CST，UTC+8）。trading_sessions 返回的是「应交易」
的连续时段切片（已与查询窗口 [start, end] 求交），供自检补全逐段比对。
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

# 北京时间（与数据源、落库口径一致）
CST = timezone(timedelta(hours=8))

# 日盘活跃子窗口，单位为「当日分钟数」（0=00:00）
_DAY_WINDOWS_MIN = [
    (9 * 60, 10 * 60 + 15),       # 9:00 – 10:15
    (10 * 60 + 30, 11 * 60 + 30),  # 10:30 – 11:30
    (13 * 60 + 30, 15 * 60),       # 13:30 – 15:00
]
# 夜盘 21:00 – 次日 01:00（结束跨午夜，用 25:00 表示次日 01:00）
_NIGHT_START_MIN = 21 * 60          # 21:00
_NIGHT_END_MIN = 25 * 60            # 次日 01:00

_WEEKEND = (5, 6)  # Saturday, Sunday


def _is_trading_day(d: datetime.date) -> bool:
    """周末不交易；节假日此处未建模（已知限制）。"""
    return d.weekday() not in _WEEKEND


def _seg(start: datetime, end: datetime,
         lo: datetime, hi: datetime) -> tuple[datetime, datetime] | None:
    """把 [start,end] 与查询窗口 [lo,hi] 求交，返回裁剪后的连续段或 None。"""
    cs = max(start, lo)
    ce = min(end, hi)
    return (cs, ce) if cs < ce else None


def trading_sessions(start_dt: datetime, end_dt: datetime
                     ) -> list[tuple[datetime, datetime]]:
    """枚举 [start_dt, end_dt] 内所有应交易的连续时段（CST，闭开区间逻辑用端值）。

    返回 [(seg_start, seg_end), ...]，每个 seg 已是「活跃交易分钟」区间，
    段内每分钟都应有一根 1m K 线（流动性极低的品种可能偶发缺失，由覆盖率阈值容忍）。
    夜盘段自然跨越午夜（seg_end 在次日）。
    """
    if start_dt.tzinfo is None:
        start_dt = start_dt.replace(tzinfo=CST)
    if end_dt.tzinfo is None:
        end_dt = end_dt.replace(tzinfo=CST)

    out: list[tuple[datetime, datetime]] = []
    # 从 start 所在日扫到 end 所在日（含），覆盖跨日夜盘
    cursor = start_dt.date()
    end_date = end_dt.date()
    while cursor <= end_date:
        if _is_trading_day(cursor):
            y, m, d = cursor.year, cursor.month, cursor.day
            # 日盘三个子窗口（属于交易日 cursor 当天）
            for s_min, e_min in _DAY_WINDOWS_MIN:
                seg = _seg(
                    datetime(y, m, d, s_min // 60, s_min % 60, tzinfo=CST),
                    datetime(y, m, d, e_min // 60, e_min % 60, tzinfo=CST),
                    start_dt, end_dt,
                )
                if seg:
                    out.append(seg)
            # 夜盘锚定到「所属交易日」cursor：21:00(cursor 前一日) → 01:00(cursor)。
            # 这样周五不会有夜盘（结束日周六非交易日，不生成），而周日夜盘
            # （21:00 周日 → 01:00 周一）会在处理周一(cursor=周一)时正确生成。
            prev = cursor - timedelta(days=1)
            ns = datetime(prev.year, prev.month, prev.day,
                          _NIGHT_START_MIN // 60, _NIGHT_START_MIN % 60,
                          tzinfo=CST)
            ne = datetime(y, m, d, 1, 0, tzinfo=CST)  # 次日 01:00 = 交易日当日 01:00
            seg = _seg(ns, ne, start_dt, end_dt)
            if seg:
                out.append(seg)
        cursor += timedelta(days=1)
    return out


def expected_minute_count(start_dt: datetime, end_dt: datetime) -> int:
    """该活跃交易段内期望的 1m K 线根数（段端点已裁剪到活跃区间）。"""
    secs = (end_dt - start_dt).total_seconds()
    return int(secs // 60) if secs > 0 else 0


def session_coverage_note() -> str:
    return ("交易日历：日盘 9:00–15:00（含 10:15–10:30、11:30–13:30 休市子窗口），"
            "夜盘 21:00–次日 01:00；跳过周末，法定节假日未建模（已知限制）。"
            "源站 1m 固定最多 1023 根（≈2.8 个交易日），超出窗口的历史缺口物理不可补，"
            "只如实标注。")
