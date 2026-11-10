#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""同步体检自检：复刻 Engine.syncHistory() 的「体检 → 补齐 → 增量」三段（v2.4.0）。

校验四件事：
1. 数据完好时走增量：只写本地最后一根及之后，历史行不动；
2. 中间挖洞能被发现：missing / gaps 与人工挖掉的一致，回补后逐根齐全；
3. 整段清空能被识别：cleared=True，窗口内数据整体写回；
4. 幂等：修好后再跑一次，missing=0、added=0、不重写历史。

用法：python3 scripts/check_sync_repair.py
"""

import sqlite3
import sys

CHECK_DAYS = 7
DAY = 86400_000
MIN = 60_000


def build_source(n, step_min, end, gap_weekend=True):
    """从 end 往前造 n 根分钟线：只造交易时段（每天 9:00-15:00 与 21:00-23:00），跳过周末。"""
    bars, ts = [], end
    while len(bars) < n:
        day = ts // DAY
        # 周末跳过（0=周日、6=周六）
        if (day + 4) % 7 in (0, 6):
            ts -= DAY
            continue
        hh = (ts % DAY) // 3600_000
        if 9 <= hh < 15 or 21 <= hh < 23:
            bars.append(ts)
        ts -= step_min * MIN
    return list(reversed(bars))


def make_db(ts_list):
    db = sqlite3.connect(":memory:")
    db.execute("CREATE TABLE kline(code TEXT, period TEXT, ts INTEGER, PRIMARY KEY(code, period, ts))")
    db.executemany("INSERT OR REPLACE INTO kline VALUES('AL','1m',?)", [(t,) for t in ts_list])
    db.commit()
    return db


def analyze(db, source, cut):
    window = [t for t in source if t >= cut]
    local = {r[0] for r in db.execute("SELECT ts FROM kline WHERE code='AL' AND period='1m' AND ts>=?", (cut,))}
    out = {"window": window, "local_window": len(local), "missing": 0, "gaps": 0,
           "tail": 0, "cleared": len(local) == 0 and bool(window)}
    if out["cleared"]:
        out["missing"] = len(window)
        out["gaps"] = 1
        return out
    local_max = max(local) if local else 0
    in_gap = False
    for t in window:
        if t in local:
            in_gap = False
        elif local_max > 0 and t > local_max:
            out["tail"] += 1           # 正常尾部新增，不算缺口
        else:
            out["missing"] += 1
            if not in_gap:
                out["gaps"] += 1
                in_gap = True
    return out


def apply(db, source, info, force_full=False):
    before = db.execute("SELECT COUNT(*) FROM kline").fetchone()[0]
    local_max = db.execute("SELECT MAX(ts) FROM kline").fetchone()[0] or 0
    heal = force_full or info["missing"] > 0 or info["cleared"]
    write = info["window"] if heal else [t for t in source if local_max <= 0 or t >= local_max]
    db.executemany("INSERT OR REPLACE INTO kline VALUES('AL','1m',?)", [(t,) for t in write])
    db.commit()
    after = db.execute("SELECT COUNT(*) FROM kline").fetchone()[0]
    return {"added": max(0, after - before), "written": len(write), "healed": heal}


def check(name, cond, extra=""):
    extra = str(extra)
    if len(extra) > 120:
        extra = extra[:120] + "…"
    print(("  [OK]   " if cond else "  [FAIL] ") + name + (("  " + extra) if extra else ""))
    return cond


def brief(info):
    return "window=%d local=%d missing=%d gaps=%d cleared=%s" % (
        len(info["window"]), info["local_window"], info["missing"],
        info["gaps"], info["cleared"])


def main():
    ok = True
    now = 1_760_000_000_000 - (1_760_000_000_000 % MIN)
    cut = now - CHECK_DAYS * DAY
    # 3000 根 1 分钟线 ≈ 6 个交易日，足够覆盖 7 天窗口
    source = build_source(3000, 1, now)

    print("1) 数据完好 → 只增量")
    db = make_db(source[:-10])
    info = analyze(db, source, cut)
    r = apply(db, source, info)
    got = {x[0] for x in db.execute("SELECT ts FROM kline")}
    ok &= check("体检无缺失", info["missing"] == 0 and info["gaps"] == 0, brief(info))
    # 写 10 根新数据 + 1 根本地最后一根（REPLACE 刷新），净新增 10
    ok &= check("只写尾部（含边界刷新）", r["written"] == 11 and r["added"] == 10, str(r))
    ok &= check("本地与源站一致", got == set(source), "%d/%d" % (len(got), len(source)))

    print("2) 中间挖洞 5 根 → 体检发现并回补")
    holed = list(source)
    del holed[1500:1505]
    db = make_db(holed)
    info = analyze(db, source, cut)
    ok &= check("缺失 5 根 / 1 段", info["missing"] == 5 and info["gaps"] == 1, brief(info))
    r = apply(db, source, info)
    got = {x[0] for x in db.execute("SELECT ts FROM kline")}
    ok &= check("回补后逐根齐全", got == set(source), "%d/%d" % (len(got), len(source)))
    ok &= check("走了补齐分支", r["healed"] is True, str(r))

    print("3) 整段清空 → 识别 cleared 并整体写回")
    db = make_db([])
    info = analyze(db, source, cut)
    ok &= check("cleared=True", info["cleared"] is True, brief(info))
    r = apply(db, source, info)
    got = {x[0] for x in db.execute("SELECT ts FROM kline")}
    ok &= check("窗口内全部写回", got == set(info["window"]), "%d/%d" % (len(got), len(info["window"])))

    print("4) 修好后再跑 → 幂等")
    info2 = analyze(db, source, cut)
    r2 = apply(db, source, info2)
    ok &= check("无缺失", info2["missing"] == 0 and info2["gaps"] == 0, brief(info2))
    ok &= check("零新增", r2["added"] == 0, str(r2))

    print("5) 强制全量 → 整窗重写但净新增为 0")
    r3 = apply(db, source, info2, force_full=True)
    ok &= check("重写窗口", r3["healed"] is True and r3["written"] == len(info2["window"]), str(r3))
    ok &= check("净新增 0", r3["added"] == 0, str(r3))

    print()
    print("结果：" + ("全部通过" if ok else "存在失败项"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
