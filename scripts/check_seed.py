#!/usr/bin/env python3
"""预置行情库自检：版本旁文件是否同步、各周期覆盖、模拟导入后的可查询性。

用法：python3 scripts/check_seed.py
"""
import os
import sqlite3
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SEED = os.path.join(ROOT, "android", "app", "src", "main", "assets", "seed.db")
VER = os.path.join(ROOT, "android", "app", "src", "main", "assets", "seed_ver.txt")

DDL = """
CREATE TABLE ticks(code TEXT NOT NULL, ts INTEGER NOT NULL, price REAL, bid REAL,
                   ask REAL, vol REAL, oi REAL, PRIMARY KEY(code, ts));
CREATE TABLE kline(code TEXT NOT NULL, period TEXT NOT NULL, ts INTEGER NOT NULL,
                   o REAL, h REAL, l REAL, c REAL, v REAL,
                   PRIMARY KEY(code, period, ts));
CREATE INDEX idx_kline_q ON kline(code, period, ts DESC);
"""


def main():
    if not os.path.exists(SEED):
        print("缺少 assets/seed.db，先跑 scripts/build_seed.py")
        return 1
    db = sqlite3.connect(SEED)
    meta = dict(db.execute("SELECT key, value FROM seed_meta"))
    ver = meta.get("version", "")
    rows = db.execute("SELECT COUNT(*) FROM kline").fetchone()[0]
    print(f"seed.db      {os.path.getsize(SEED)/1048576:.2f} MB  rows={rows}  version={ver}")

    ok = True
    side = open(VER, encoding="utf-8").read().strip() if os.path.exists(VER) else ""
    if side != ver:
        print(f"[FAIL] seed_ver.txt={side!r} 与 seed_meta.version={ver!r} 不一致")
        ok = False
    else:
        print(f"seed_ver.txt {side}  ✓")

    try:
        v = int(ver)
        print(f"version 按 long 解析：{v}  (int 上限 {2**31 - 1}，超出即旧版溢出根因)")
        if v <= 0:
            ok = False
    except ValueError:
        print(f"[FAIL] version={ver!r} 不是数字")
        ok = False

    print("\n各周期（每品种）:")
    for code, per, n, lo, hi in db.execute(
            "SELECT code, period, COUNT(*), MIN(ts), MAX(ts) FROM kline "
            "GROUP BY code, period ORDER BY code, period"):
        days = (hi - lo) / 86400000
        print(f"  {code:4s} {per:4s} {n:5d} 根  覆盖 {days:6.1f} 天")

    # 模拟 App 首次启动：建空库 → ATTACH → INSERT OR IGNORE → 用 App 的 SQL 取数
    tmp = os.path.join(tempfile.gettempdir(), "seed_check.db")
    if os.path.exists(tmp):
        os.remove(tmp)
    local = sqlite3.connect(tmp)
    local.executescript(DDL)
    local.execute(f"ATTACH DATABASE '{SEED}' AS seed")
    local.execute("INSERT OR IGNORE INTO kline(code,period,ts,o,h,l,c,v) "
                  "SELECT code,period,ts,o,h,l,c,v FROM seed.kline")
    local.commit()
    local.execute("DETACH DATABASE seed")
    got = local.execute("SELECT COUNT(*) FROM kline").fetchone()[0]
    print(f"\n模拟导入后本地 kline = {got} 行")
    if got != rows:
        print("[FAIL] 导入行数与预置库不一致")
        ok = False

    for code in ("AL", "AO", "AHD"):
        n = local.execute("SELECT COUNT(*) FROM kline WHERE code=? AND period='1d'",
                          (code,)).fetchone()[0]
        m = local.execute("SELECT COUNT(*) FROM kline WHERE code=? AND period='1m'",
                          (code,)).fetchone()[0]
        print(f"  {code:4s} 日线 {n:5d}  1分钟 {m:5d}")
        if n == 0:
            print(f"[FAIL] {code} 没有日线，预测会一直报数据不足")
            ok = False
    local.close()
    os.remove(tmp)

    print("\n结果:", "OK" if ok else "有问题")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
