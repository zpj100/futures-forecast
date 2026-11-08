# 实现说明

记录项目的技术选型、数据源验证结论和模型设计，便于后续维护与 APK 复用。

## 一、技术栈

| 层 | 选型 | 原因 |
|----|------|------|
| 后端 | Python 3.11 + FastAPI + uvicorn | 异步采集与 WebSocket 原生支持，单进程即可跑采集 + 服务 |
| HTTP | httpx 异步客户端 | 复用连接，超时可控 |
| 存储 | SQLite（标准库 sqlite3） | 零外部依赖，单文件随项目带走 |
| 计算 | numpy | 指标与岭回归用矩阵运算；环境无 sklearn/lightgbm |
| 前端 | 原生 JS + Canvas | 无构建、无 CDN，离线预览可用 |
| 图表 | Canvas 手绘折线 | 避免引入图表库依赖 |

## 二、数据源验证结论

逐源实测后的取舍：

| 数据源 | 结论 |
|--------|------|
| 新浪 `hq.sinajs.cn` | **采用**。实时 `nf_AL0` / `nf_AO0` / `hf_AHD` 可用 |
| 新浪 `InnerFuturesNewService.getFewMinLine` | **采用**。内盘分钟线，最多 1023 根（约 3 个交易日） |
| 新浪 `GlobalFuturesService.getGlobalFuturesMinLine` | **采用**。外盘分钟线，仅当日 |
| 新浪 `getDailyKLine` / `getGlobalFuturesDailyKLine` | **采用**。沪铝 2005 起、氧化铝 2023 起、伦铝 2016 起 |
| 东方财富 push2his | 弃用，返回空 |
| 路透 / 博易 / 生意社 / SMM | 弃用，内容反爬 |

资讯源：

| 源 | 说明 |
|----|------|
| 东方财富全文检索 `search-api-web.eastmoney.com` | 按关键词检索，标题命中才保留 |
| 同花顺 7×24 `news.10jqka.com.cn` | 快讯流，按品种关键词过滤 |

### 关键陷阱

- 新浪 jsonp 返回带 `/*<script>...</script>*/` 前缀和 `var _=(...)` 外壳，需要先剥离再解析（`jsonp_to_json`）。
- 分钟线接口偶发返回未来占位 bar（如 `2026-11-09 00:00`），必须在采集层、接口层、入库层三层过滤，否则会污染预测。库里已有的脏行不删除，靠查询时过滤。

### 字段映射

内盘 `nf_` 行情：
```
name, ?, open, high, low, ?, last, ?, bid, ask, ..., volume(13), oi(14), prev_close(10)
```

外盘 `hf_` 行情：
```
last, ?, bid, ask, high, low, time, prev_close, open, ..., volume(13)
```

## 三、数据粒度策略

公开接口拿不到历史秒级数据。策略：

1. **最细历史**：1 分钟 K 线（内盘约 3 个交易日，外盘仅当日）
2. **秒级序列**：服务每 3 秒抓一次快照，自建秒级库，运行时间越长越完整
3. **长期回溯**：日线从 2005 / 2023 / 2016 年起，支撑 8 小时与 1 天预测

## 四、预测模型

### 多信号集成

| 信号 | 逻辑 |
|------|------|
| `trend` | EMA5 / EMA20 交叉趋势跟随 |
| `reversion` | RSI 超买超卖反转 |
| `boll` | 布林带 Z-score 均值回归 |
| `momentum` | 近期动量延续 |
| `ridge` | 岭回归（特征：EMA 偏离、RSI、布林位置、波动率、动量） |

### 验证与加权

1. 每个信号在对应周期的序列上做**走向外（walk-forward）**验证，取尾部 45% 段合并统计
2. 胜率 ≤ 50% 的信号权重归零；超额胜率（`hit - 0.5`）作为权重
3. 稀疏信号（RSI / 布林）若按多折验证，每折样本不足会导致胜率塌回 0.5，因此改为尾部段合并统计
4. 概率上限随证据总量收缩：`cap = 0.5 + 0.35 * min(wsum / 0.20, 1.0)`

### 实测表现

分钟级反转类信号有效（伦铝 10 分钟 0.735、沪铝 1 小时 0.60），趋势跟随在分钟级无效。日线各信号胜率约 0.5，模型输出「震荡」。这是诚实的弱优势表达，避免伪造强势信号。

## 五、采集任务

| 任务 | 间隔 | 内容 |
|------|------|------|
| 快照 | 3 秒 | 三品种实时报价 → `snapshots` 表 |
| K 线 | 60 秒 | 分钟线 + 日线更新 → `kline_min` / `kline_day` |
| 资讯 | 300 秒 | 东财 + 同花顺 → `news` 表 |

采集结果同时写入内存态 `collector.state`，接口优先读内存，落库用于持久化与重启恢复。

## 六、前端

- 深色 OLED 风格，红涨绿跌（国内期货习惯）
- 原生 JS，WebSocket 自动重连
- Canvas 绘制分时折线，切换品种与周期即时重绘
- 无任何 CDN 依赖，断网本地预览仍可用

## 七、为 APK 预留

`release/version.json` 为客户端版本清单：

- `versionCode` 用于判断是否需要更新
- `url` 指向 GitHub Release 最新页，`mirror` 指向 Gitee
- APK 计划用 WebView 壳加载局域网后端，检查 Release 自动更新

## 八、已知限制

- 秒级历史无法回溯，只能从服务首次运行起累积
- 外盘分钟线仅当日，跨日需依赖日线
- 环境无 sklearn / lightgbm，模型用 numpy 手写岭回归
- 预测在弱优势时输出「震荡」，方向准确率受限于分钟级噪声

## 九、APK 设计方案

### 形态

WebView 壳，加载局域网内后端的地址。APK 不含数据、不含前端资源，体积约 2.3 MB。

不内嵌前端的原因：前端依赖 WebSocket 实时推送与 `/api/*` 接口，内嵌静态资源后仍需后端，不如直接加载后端页面，改前端免重打包。

### 服务器地址配置

- 首次启动若未配置地址，弹出对话框要求填写，取消则退出
- 地址持久化到 `SharedPreferences`
- 右下角半透明齿轮可随时修改
- 地址必须以 `http://` 或 `https://` 开头，校验失败重新弹窗

### 连接失败处理

`onReceivedError` 主框架加载失败时注入错误页，提供「修改服务器地址」与「重试」两个按钮，通过 `FuturesNative` JS 接口调用原生方法。

### 版本检查

APK 启动后异步拉取 `https://raw.githubusercontent.com/zpj100/futures-forecast/main/release/version.json`：

1. 解析 `versionCode`，与本机 `BuildConfig.VERSION_CODE` 比较
2. 远端更大时弹窗展示 `notes`，点击跳转 `url`
3. 网络失败静默忽略，不打扰使用

### 外部链接

`shouldOverrideUrlLoading` 拦截非服务器域名的链接，用系统浏览器打开，避免在 WebView 内迷失。

### 返回键

WebView 可后退时优先后退；已在首页时 2 秒内再按一次才退出。

### 构建

- AGP 8.2.2 + Gradle 8.2.1 + JDK 17 + compileSdk 34
- 签名密钥 `android/app/keystore.jks`（RSA 2048，10 年有效期）
- `bash android/build-apk.sh` 一键构建，产物放 `releases/`

### 已知限制

- 局域网地址变化后需手动改；后续可加 mDNS 自动发现
- Android 9+ 默认禁止明文 HTTP，本项目通过 `usesCleartextTraffic="true"` 放开（仅用于局域网场景）
