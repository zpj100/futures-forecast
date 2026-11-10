#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""增量写入自检：复刻 Engine.incrementalMinute() 与 Forecaster.aggregateByTime()。

校验三件事：
1. 只写「本地最后一根及之后」，重复跑不产生重复行；
2. 5/15/30/60 分钟由 1 分钟按**时间桶**聚合，桶起点能被周期整除；
3. 源站返回 1023 根时，实际写库量远小于全量（真实增量）。

用法：python3 scripts/check_incremental.py
"""

import random
import sqlite3
import sys
import time

PERIOD_STEP = {"1m": 1, "5m": 5, "15m": 15, "30m": 30, "60m": 60}


def aggregate_by_time(bars, minutes):
    """[(ts,o,h,l,c,v)] -> 按 ⌊ts/周期⌋ 分桶。对齐规则与 Java 一致。"""
    if minutes <= 1:
        return list(bars)
    bucket = minutes * 60_000
    out, cur, cur_ts = [], None, None
    for ts, o, h, l, c, v in bars:
        t = ts - (ts % bucket)
        if cur is None or t != cur_ts:
            if cur is not None:
                out.append(cur)
            cur_ts = t
            cur = [t, o, h, l, c, v]
        else:
            cur[2] = max(cur[2], h)
            cur[3] = min(cur[3], l)
            cur[4] = c
            cur[5] += v
    if cur is not None:
        out.append(cur)
    return [tuple(x) for x in out]


def tail(bars, local_max):
    return [b for b in bars if local_max <= 0 or b[0] >= local_max]


def setup():
    db = sqlite3.connect(":memory:")
    db.execute(
        "CREATE TABLE kline(code TEXT, period TEXT, ts INTEGER,"
        " o REAL, h REAL, l REAL, c REAL, v REAL, PRIMARY KEY(code, period, ts))"
    )
    return db


def upsert(db, code, period, bars):
    db.executemany(
        "INSERT OR REPLACE INTO kline VALUES(?,?,?,?,?,?,?,?)",
        [(code, period) + tuple(b) for b in bars],
    )


def local_max(db, code, period):
    row = db.execute(
        "SELECT MAX(ts) FROM kline WHERE code=? AND period=?", (code, period)
    ).fetchone()
    return row[0] or 0


def fetch_1m(now, n=1023):
    """模拟新浪 getFewMinLine 返回最近 1023 根 1 分钟线。"""
    step = 60_000
    start = (now // step) * step - (n - 1) * step
    px, out = 20380.0, []
    for i in range(n):
        # 每一分钟 4 个采样，合成 OHLC，形状与真实数据一致
        o = px
        h, l = px, px
        for _ in range(4):
            px += random.uniform(-1.8, 1.8)
            h, l = max(h, px), min(l, px)
        out.append((start + i * step, o, h, l, px, 120.0))
    return out


def incremental_minute(db, code, now):
    """与 Engine.incrementalMinute(force=true) 同逻辑。"""
    m = fetch_1m(now)
    added = 0
    fresh = tail(m, local_max(db, code, "1m"))
    if fresh:
        upsert(db, code, "1m", fresh)
        added += len(fresh)
    writes = {"1m": len(fresh)}
    for period in ("5m", "15m", "30m", "60m"):
        agg = aggregate_by_time(m, PERIOD_STEP[period])
        add = tail(agg, local_max(db, code, period))
        # 本地完全没有该周期时一次补齐（首次安装 / 预置库缺档）
        if add:
            upsert(db, code, period, add)
            added += len(add)
        writes[period] = len(add)
    return added, writes, len(m)


def main():
    random.seed(20261110)
    db = setup()
    code = "AL"
    now = int(time.time() * 1000)

    # 第一轮：本地空库，等价于首次增量（会把 1023 根 1m 全收下）
    added1, w1, incoming = incremental_minute(db, code, now)
    print(f"首轮：源站 {incoming} 根 → 写入 {added1} 根 {w1}")
    assert w1["1m"] == incoming, "本地为空时 1m 应全量入账"
    assert w1["5m"] > 0 and w1["60m"] > 0, "长周期应被聚合出来"

    # 第二轮：同一批数据再跑一次，只应写 boundary 那一根（不新增行）
    total_before = total_rows(db)
    added2, w2, _ = incremental_minute(db, code, now)
    total_after = total_rows(db)
    print(f"重跑：写入 {added2} 根 {w2}；总行数 {total_before} → {total_after}")
    assert total_after == total_before, "重复增量不应新增行"
    for p in PERIOD_STEP:
        assert w2[p] <= 1, f"{p} 重复写入应只有 boundary 一根，实际 {w2[p]}"

    # 第三轮：再过 3 分钟，源站多出 3 根新的 1 分钟线
    now2 = now + 3 * 60_000
    added3, w3, _ = incremental_minute(db, code, now2)
    print(f"三分钟后：写入 {added3} 根 {w3}")
    assert w3["1m"] == 4, f"应新增 3 根 + 1 根 boundary，实际 {w3['1m']}"
    # 5 分钟桶可能未翻新，只要求 1m 有增量
    assert w3["5m"] <= 2

    # 桶对齐：所有长周期时间戳都能被周期整除
    for p, step in PERIOD_STEP.items():
        if step == 1:
            continue
        bad = [
            ts
            for (ts,) in db.execute(
                "SELECT ts FROM kline WHERE code=? AND period=?", (code, p)
            )
            if ts % (step * 60_000) != 0
        ]
        assert not bad, f"{p} 有 {len(bad)} 根未对齐桶起点，例如 {bad[:3]}"

    # 与源站同周期口径对比：本地聚合的 60m 收盘价应等于对应桶内最后一根 1m 收盘
    m = db.execute(
        "SELECT ts,c FROM kline WHERE code=? AND period='1m' ORDER BY ts", (code,)
    ).fetchall()
    h60 = db.execute(
        "SELECT ts,c FROM kline WHERE code=? AND period='60m' ORDER BY ts", (code,)
    ).fetchall()
    last_bucket = {}
    for ts, c in m:
        last_bucket[ts - ts % 3_600_000] = c
    for ts, c in h60:
        if ts in last_bucket:
            assert abs(last_bucket[ts] - c) < 1e-9, f"60m 收盘价与 1m 不一致 @{ts}"
    print(f"桶对齐与收盘价校验通过（60m {len(h60)} 根）")

    rows = total_rows(db)
    print(f"\n==> OK：本地 {rows} 行，三次增量均无重复、无错位")
    return 0


def total_rows(db):
    return db.execute("SELECT COUNT(*) FROM kline").fetchone()[0]


if __name__ == "__main__":
    sys.exit(main())
