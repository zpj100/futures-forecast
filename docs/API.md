# 接口文档

服务默认监听 `http://localhost:8123`，`/docs` 提供自动生成的 OpenAPI 交互文档。
所有接口返回 JSON（UTF-8），WebSocket 每 3 秒推送一次。

## 通用说明

- 品种代码：`AL`（沪铝主力）、`AO`（沪氧化铝主力）、`AHD`（LME 3M 伦铝）
- 价格单位：内盘「元/吨」，外盘「美元/吨」
- 时间戳：ISO 8601，UTC 时区
- 未知品种返回 `404 {"error": "unknown code"}`

## 1. 健康检查

`GET /api/health`

```json
{
  "ok": true,
  "ts": "2026-11-08T17:45:11.343506+00:00",
  "products": ["AL", "AO", "AHD"],
  "meta": {
    "last_quote_ts": 1794159911.17,
    "last_kline_ts": 1794159907.16,
    "last_news_ts": 1794159907.16,
    "last_forecast_ts": 1794159907.16,
    "errors": []
  }
}
```

`meta` 为各采集任务的最后成功时间与最近错误，用于前端判断数据新鲜度。

## 2. 品种与行情概览

`GET /api/products`

返回三品种的静态信息、最新报价、已累积的数据量，以及六个预测周期的定义。

| 字段 | 说明 |
|------|------|
| `quote` | 最新报价对象（见下） |
| `snapshots` | 已累积的秒级快照条数 |
| `minute_bars` | 1 分钟 K 线根数 |
| `daily_bars` | 日线根数 |

`GET /api/quote`

```json
{
  "quotes": {
    "AL": {
      "code": "AL", "ts": "2026-11-08T17:47:26.21+00:00",
      "name": "铝连续", "open": 23040.0, "high": 23245.0, "low": 23010.0,
      "last": 23180.0, "bid": 23180.0, "ask": 0.0,
      "volume": 226809.0, "open_interest": 81995.0, "prev_close": 23080.0
    }
  },
  "meta": { "...": "同 /api/health 的 meta" }
}
```

外盘 `AHD` 无 `volume` / `open_interest`（接口不提供，字段为 `null`）。

## 3. K 线

`GET /api/kline/{code}?period={period}&limit={n}`

| 参数 | 取值 | 默认 |
|------|------|------|
| `period` | `1m` `5m` `15m` `30m` `60m` `1d` | `1m` |
| `limit` | 20 ~ 4500 | 400 |

```json
{
  "code": "AL",
  "period": "5m",
  "bars": [
    {"ts": 1794159300, "o": 23150.0, "h": 23180.0, "l": 23140.0, "c": 23175.0, "v": 312}
  ]
}
```

- `5m/15m/30m/60m` 由本地 1 分钟线聚合，`1d` 直接取新浪日线（沪铝回溯至 2005 年）。
- 数据源偶发返回未来占位 bar，服务已在采集、入库、接口三层过滤，返回结果不含未来时间点。

## 4. 秒级快照

`GET /api/snapshots/{code}?limit={n}`，`limit` 取值 10 ~ 5000，默认 900。

```json
{"code": "AL", "points": [{"ts": 1794159911, "last": 23180.0, "bid": 23180.0, "ask": 0.0}]}
```

快照由服务运行期每 3 秒抓取累积。公开接口拿不到历史秒级数据，因此秒级序列从服务首次启动开始生长，运行时间越长数据越完整。

## 5. 趋势预测

`GET /api/forecast` 返回全部品种；`GET /api/forecast?code=AL` 返回单品种。

```json
{
  "code": "AL",
  "price": 23180.0,
  "overall": "中性",
  "score": 0.0,
  "recommendation": "综合研判：中性（评分 0.00）…",
  "indicators": {"rsi": 47.5, "macd_hist": -0.5477, "atr_pct": 0.041, "...": "..."},
  "horizons": [
    {
      "key": "1h", "label": "1小时", "minutes": 60,
      "direction": "震荡", "p_up": 0.5, "exp_move_pct": 0.0,
      "target_price": 23180.0, "confidence": 0.0,
      "best_hit": 0.6, "accuracy": 0.6, "samples": 120, "test_samples": 1023,
      "defensive": false,
      "signals": {
        "reversion": {"hit": 0.60, "weight": 0.10, "signal": 0}
      }
    }
  ]
}
```

字段含义：

| 字段 | 说明 |
|------|------|
| `direction` | `看涨` / `看跌` / `震荡` |
| `p_up` | 上涨概率，区间 0.5 ~ 0.85，证据越强上限越高 |
| `exp_move_pct` | 预期涨跌幅（%） |
| `target_price` | 对应预期幅度的目标价 |
| `confidence` | 置信度，与信号权重总量相关 |
| `best_hit` / `accuracy` | 走向外（walk-forward）验证的命中率 |
| `signals.*.weight` | 该信号权重；胜率不足 50% 或证据不足时为 0 |
| `defensive` | 8 小时与 1 天共用日线序列时为 `true`（夜盘约 8 小时） |

预测模型为多信号集成：EMA 趋势、RSI 反转、布林带 Z 反转、动量、岭回归。每个信号先做尾部段走向外验证，只有超额胜率大于 0 的信号参与加权，概率上限随证据总量收缩。证据不足时输出「震荡」，不给出高置信度的方向判断。

## 6. 量化分析

`GET /api/analysis`

三品种的指标、六周期预测与综合评分汇总，供仪表盘量化面板使用。

| 指标 | 说明 |
|------|------|
| `ema5/10/20/60` | 指数移动平均 |
| `rsi` | 14 周期 RSI |
| `macd` / `macd_signal` / `macd_hist` | MACD 三线 |
| `atr` / `atr_pct` | 平均真实波幅及占比 |
| `boll_upper` / `boll_lower` / `boll_pos` | 布林带上下轨与 0~1 位置 |
| `vol20_pct` | 20 周期波动率 |
| `range_low` / `range_high` / `range_pos` | 区间与区间位置 |
| `vol_change` | 量能变化 |

## 7. 资讯

`GET /api/news?limit={n}&code={code}`，`limit` 取值 5 ~ 200，默认 40，`code` 可选。

```json
{
  "news": [
    {"ts": 1794159600, "source": "东方财富", "title": "…", "url": "https://…", "summary": "…", "code": "AO"}
  ],
  "count": 1
}
```

资讯来源：东方财富全文检索（标题命中关键词）+ 同花顺 7×24 快讯（关键词过滤），按品种关键词映射归类。

## 8. WebSocket

`WS /ws`

```json
{
  "type": "tick",
  "ts": "2026-11-08T17:47:13.52+00:00",
  "quotes": {"AL": {"last": 23180.0, "…": "…"}},
  "forecast": {"AL": {"price": 23180.0, "overall": "中性", "score": 0.0, "horizons": []}}
}
```

每 3 秒推送一次，前端断线后自动重连。
