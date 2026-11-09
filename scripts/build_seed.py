#!/usr/bin/env python3
"""构建随 APK 分发的预置行情库 assets/seed.db。

只放公开可得的真实数据，不合成任何行情：
  - 日线：多年历史（沪铝 2005 起 / 氧化铝上市起 / 伦铝 2016 起）
  - 分钟线：新浪分时接口每个周期最多 1023 根
      type=1  ≈ 3 个交易日      type=5  ≈ 13 个交易日    type=15 ≈ 64 个交易日
      type=30 ≈ 100 个交易日    type=60 ≈ 197 个交易日（覆盖 180 天）
  - 秒级点：公开接口不提供历史秒级数据，库里留空，由 App 运行后自行累积

用法：python3 scripts/build_seed.py [--out android/app/src/main/assets/seed.db]
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys
import time
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TZ = timezone(timedelta(hours=8))
BASE = ("https://stock2.finance.sina.com.cn/futures/api/jsonp.php/var%20_=/")
HEADERS = {
    "User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
    "Referer": "https://finance.sina.com.cn/",
}

PRODUCTS = [
    {"code": "AL", "kind": "inner", "sym": "AL0"},
    {"code": "AO", "kind": "inner", "sym": "AO0"},
    {"code": "AHD", "kind": "global", "sym": "AHD"},
]
MIN_TYPES = [1, 5, 15, 30, 60]

SCHEMA = """
CREATE TABLE ticks(code TEXT NOT NULL, ts INTEGER NOT NULL,
  price REAL, bid REAL, ask REAL, vol REAL, oi REAL, PRIMARY KEY(code, ts));
CREATE TABLE kline(code TEXT NOT NULL, period TEXT NOT NULL, ts INTEGER NOT NULL,
  o REAL, h REAL, l REAL, c REAL, v REAL, PRIMARY KEY(code, period, ts));
CREATE TABLE forecasts(id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT, horizon TEXT,
  dir TEXT, p_up REAL, target REAL, price REAL, score REAL, source TEXT, reason TEXT,
  created_at INTEGER, due_at INTEGER, actual REAL, hit INTEGER);
CREATE TABLE seed_meta(key TEXT PRIMARY KEY, value TEXT);
CREATE INDEX idx_kline_q ON kline(code, period, ts DESC);
CREATE INDEX idx_ticks_q ON ticks(code, ts DESC);
"""


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req, timeout=25) as r:
        return r.read().decode("utf-8", errors="ignore")


def jsonp(text: str) -> str:
    a, o = text.find("["), text.find("{")
    if a < 0 and o < 0:
        return "null"
    if a >= 0 and (o < 0 or a < o):
        start, close = a, "]"
    else:
        start, close = o, "}"
    end = text.rfind(close)
    return text[start:end + 1] if end > start else "null"


def ts_of(s: str) -> int:
    s = (s or "").strip()
    if not s:
        return 0
    fmt = "%Y-%m-%d %H:%M:%S" if len(s) > 10 else "%Y-%m-%d"
    try:
        return int(datetime.strptime(s, fmt).replace(tzinfo=TZ).timestamp() * 1000)
    except ValueError:
        return 0


def fnum(v) -> float:
    try:
        return float(v)
    except (TypeError, ValueError):
        return 0.0


def minute_bars(p, typ: int):
    if p["kind"] == "inner":
        url = f"{BASE}InnerFuturesNewService.getFewMinLine?symbol={p['sym']}&type={typ}"
        arr = json.loads(jsonp(fetch(url)))
        out = []
        for r in arr:
            t = ts_of(r.get("d", ""))
            c = fnum(r.get("c"))
            if t and c > 0:
                out.append((t, fnum(r.get("o")), fnum(r.get("h")), fnum(r.get("l")), c, fnum(r.get("v"))))
        return out, "1m" if typ == 1 else f"{typ}m"
    # 伦铝：只会返回当日分时，按 1 分钟存
    url = f"{BASE}GlobalFuturesService.getGlobalFuturesMinLine?symbol={p['sym']}&type=1"
    obj = json.loads(jsonp(fetch(url)))
    out = []
    for rows in obj.values():
        for r in rows:
            if not isinstance(r, list) or len(r) < 6:
                continue
            t = ts_of(r[-1])
            px = fnum(r[1])
            if t and px > 0:
                out.append((t, px, px, px, px, fnum(r[2])))
    out.sort(key=lambda x: x[0])
    return out, "1m"


def daily_bars(p):
    if p["kind"] == "inner":
        url = f"{BASE}InnerFuturesNewService.getDailyKLine?symbol={p['sym']}"
        arr = json.loads(jsonp(fetch(url)))
        out = []
        for r in arr:
            t = ts_of(r.get("d", ""))
            c = fnum(r.get("c"))
            if t and c > 0:
                out.append((t, fnum(r.get("o")), fnum(r.get("h")), fnum(r.get("l")), c, fnum(r.get("v"))))
        return out
    url = f"{BASE}GlobalFuturesService.getGlobalFuturesDailyKLine?symbol={p['sym']}"
    arr = json.loads(jsonp(fetch(url)))
    out = []
    for r in arr:
        t = ts_of(r.get("date", ""))
        c = fnum(r.get("close"))
        if t and c > 0:
            out.append((t, fnum(r.get("open")), fnum(r.get("high")), fnum(r.get("low")), c, fnum(r.get("volume"))))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(ROOT / "android/app/src/main/assets/seed.db"))
    ap.add_argument("--skip-minute", action="store_true", help="只打包日线（体积更小）")
    args = ap.parse_args()

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    if out.exists():
        out.unlink()
    db = sqlite3.connect(out)
    db.executescript(SCHEMA)

    total = 0
    for p in PRODUCTS:
        d = daily_bars(p)
        db.executemany(
            "INSERT OR REPLACE INTO kline(code,period,ts,o,h,l,c,v) VALUES(?,?,?,?,?,?,?,?)",
            [(p["code"], "1d", *row) for row in d])
        total += len(d)
        span = ""
        if d:
            span = f"{datetime.fromtimestamp(d[0][0]/1000, TZ):%Y-%m-%d} → {datetime.fromtimestamp(d[-1][0]/1000, TZ):%Y-%m-%d}"
        print(f"{p['code']:4s} 日线 {len(d):5d} 根  {span}")

        if args.skip_minute:
            continue
        for typ in MIN_TYPES:
            try:
                bars, period = minute_bars(p, typ)
            except Exception as e:      # 单个周期失败不影响整体
                print(f"      {typ}m 失败：{e}")
                continue
            if not bars:
                continue
            db.executemany(
                "INSERT OR REPLACE INTO kline(code,period,ts,o,h,l,c,v) VALUES(?,?,?,?,?,?,?,?)",
                [(p["code"], period, *row) for row in bars])
            total += len(bars)
            first = datetime.fromtimestamp(bars[0][0] / 1000, TZ)
            last = datetime.fromtimestamp(bars[-1][0] / 1000, TZ)
            days = (last - first).days
            print(f"      {period:3s} {len(bars):5d} 根  {first:%Y-%m-%d %H:%M} → {last:%Y-%m-%d %H:%M}  覆盖 {days} 天")
            if p["kind"] == "global":
                break                   # 伦铝分时只有当日，取一次 1m 即可
            time.sleep(0.6)

    meta = {
        "version": int(time.strftime("%Y%m%d%H%M")),
        "built_at": int(time.time() * 1000),
        "rows": total,
    }
    db.executemany("INSERT OR REPLACE INTO seed_meta(key,value) VALUES(?,?)",
                   [(k, str(v)) for k, v in meta.items()])
    db.commit()
    db.execute("VACUUM")
    db.close()
    # 小体积版本号旁文件：App 启动时先读它比对版本，命中就不再拷贝 1.5MB 的 seed.db
    (out.parent / "seed_ver.txt").write_text(str(meta["version"]), encoding="utf-8")
    size = out.stat().st_size
    print(f"\n==> {out}  {size/1048576:.2f} MB, {total} 行, version={meta['version']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
