#!/usr/bin/env python3
"""一次性修复：回填 forecast_log.price。

历史原因：旧版 _log_forecast 把 horizon 字典里并不存在的 price/score 字段写入，
导致所有历史预测记录的 price 恒为 NULL；命中率统计（/api/history）会整行跳过
price IS NULL 的记录，于是页面恒显「样本不足」。

本脚本用实时行情快照表 snapshots（每 3 秒一笔，含 last 价，按 code,ts 索引）
为 forecast_log 中 price IS NULL 的记录回填「预测时刻或之前最近一笔」的实时价，
使其能参与命中率统计。幂等：只动 price IS NULL 的行；无对应快照的行保持 NULL（跳过）。

用法：
    python3 backfill_forecast_price.py [db_path]
默认 db_path = /opt/futures-forecast/data/futures.db
"""
import sqlite3
import sys
import os

DEFAULT_DB = "/opt/futures-forecast/data/futures.db"


def main() -> int:
    db = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_DB
    if not os.path.exists(db):
        print(f"DB 不存在: {db}")
        return 2
    conn = sqlite3.connect(db, timeout=60)
    conn.execute("PRAGMA busy_timeout=60000")
    try:
        before = conn.execute(
            "SELECT COUNT(*) FROM forecast_log WHERE price IS NULL"
        ).fetchone()[0]

        # 用预测时刻或之前最近一笔实时 last 价回填 entry price
        conn.execute(
            """
            UPDATE forecast_log
            SET price = (
                SELECT s.last
                FROM snapshots s
                WHERE s.code = forecast_log.code
                  AND s.ts <= forecast_log.ts
                ORDER BY s.ts DESC
                LIMIT 1
            )
            WHERE price IS NULL
              AND EXISTS (
                SELECT 1 FROM snapshots s
                WHERE s.code = forecast_log.code
                  AND s.ts <= forecast_log.ts
              )
            """
        )
        updated = conn.total_changes
        conn.commit()

        after = conn.execute(
            "SELECT COUNT(*) FROM forecast_log WHERE price IS NULL"
        ).fetchone()[0]

        print(f"price IS NULL 修复前: {before}")
        print(f"回填更新行数: {updated}")
        print(f"price IS NULL 修复后: {after}")
        print("--- 回填后可用于命中率统计的样本（price 非空 且 dir!=0）---")
        for r in conn.execute(
            """
            SELECT code, horizon, COUNT(*)
            FROM forecast_log
            WHERE price IS NOT NULL AND dir != 0
            GROUP BY code, horizon
            ORDER BY code, horizon
            """
        ):
            print(r)
        return 0
    finally:
        conn.close()


if __name__ == "__main__":
    raise SystemExit(main())
