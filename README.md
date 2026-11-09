# 铝期货趋势预测 · futures-forecast

实时采集 **电解铝（沪铝主力 AL0）**、**氧化铝（沪氧化铝主力 AO0）**、**伦铝（LME 3M AHD）**
三个品种的公开行情，做六周期（1分钟 / 5分钟 / 10分钟 / 1小时 / 8小时 / 1天）方向预测，
并把实时曲线、量化指标、趋势建议和财经资讯汇总在一个仪表盘里。

数据全部来自公开接口，无需任何 API Key。

---

## 一、能做什么

| 能力 | 说明 |
|------|------|
| 实时行情 | 每 3 秒抓取一次最新价 / 买卖价 / 成交量 / 持仓量 |
| 秒级数据累积 | 公开接口给不到历史秒级数据，服务把每次抓取自建为秒级快照库 |
| 多周期 K 线 | 1 分钟原始 + 5/15/60 分钟本地聚合 + 多年日线 |
| 六周期预测 | 走向外验证的岭回归 + 动量融合，输出方向、上涨概率、预期幅度、目标价、验证胜率 |
| 量化指标 | RSI、MACD、ATR、布林带位置、波动率、区间位置、EMA 多空排列 |
| 资讯聚合 | 东方财富全文检索（电解铝 / 氧化铝 / 伦铝）+ 同花顺 7×24 快讯按关键词过滤 |
| 趋势建议 | 综合六周期评分给出方向建议，并提示超买超卖、波动率、多周期分歧等风险 |

## 二、快速开始

```bash
# 安装依赖
pip install --break-system-packages -r backend/requirements.txt

# 启动（默认 8000 端口）
bash run.sh
```

打开 <http://localhost:8000> 即为仪表盘，`/docs` 为自动生成的接口文档。

## 三、数据来源与粒度（重要）

| 品种 | 实时 | 1 分钟历史 | 日线历史 |
|------|------|-----------|---------|
| 电解铝 AL0 | 新浪 `nf_AL0` | 约 1023 根（≈3 个交易日） | 2005 至今 |
| 氧化铝 AO0 | 新浪 `nf_AO0` | 约 1023 根 | 2023-06 至今 |
| 伦铝 AHD | 新浪 `hf_AHD` | 当日分时 | 2016 至今 |

**关于“秒级一年历史”**：公开接口不提供秒级历史，付费源（天勤 TqSdk / CTP / Wind）才有。
本项目采取两条腿走路：

1. 运行即累积——每 3 秒一条快照存进本地 SQLite，跑得越久秒级历史越长；
2. 分钟线兜底——1 分钟粒度可回溯约 3 个交易日，日线可回溯多年，足以支撑 8 小时与 1 天周期的建模。

## 四、预测模型

每个 (品种 × 周期) 独立训练：

1. **特征**：标准化收益率、EMA 比值、RSI、MACD 柱、布林 Z 值、ATR 占比、20 期波动率、量比、时间趋势项；8 小时周期额外拼接分钟级特征块。
2. **目标**：未来 N 根的**对数收益率**，1% 分位缩尾去极值。
3. **验证**：前 70% 训练、后 30% 走向外验证，报告真实样本外方向胜率。
4. **融合**：岭回归权重与其验证胜率挂钩（胜率 ≤50% 时权重为 0），剩余权重给动量持续性；若两个信号都没有验证优势，自动收缩输出并报“震荡”，避免用掷硬币的准确率给出自信的错误结论。
5. **概率**：用验证残差标准差把融合信号映射为正态 CDF，得到上涨概率。
6. **综合**：六周期按置信度加权投票得到看涨 / 中性 / 看跌，并生成带风险提示的建议文本。

> 8 小时与 1 天共用日线基准序列（A 股一个完整交易日含夜盘约 8 小时），两者在特征窗口和动量权重上不同。

## 五、接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/health` | 健康检查与采集时间戳 |
| GET | `/api/products` | 三品种实时行情与数据量统计 |
| GET | `/api/quote` | 最新报价 |
| GET | `/api/kline/{code}` | `code`=AL/AO/AHD，`period`=1m/5m/15m/30m/60m/1d |
| GET | `/api/snapshots/{code}` | 秒级快照历史 |
| GET | `/api/forecast` | 全部预测；加 `?code=AL` 取单品种 |
| GET | `/api/analysis` | 三品种量化汇总 |
| GET | `/api/news` | 资讯，可加 `?code=AL` 过滤 |
| WS | `/ws` | 每 3 秒推送行情与预测摘要 |

## 六、目录结构

```
futures-forecast/
├── backend/
│   ├── app/
│   │   ├── config.py            品种、周期、采集间隔
│   │   ├── store.py             SQLite：快照 / K 线 / 资讯
│   │   ├── collector.py         三个后台采集任务
│   │   ├── main.py              FastAPI + WebSocket
│   │   ├── datasource/
│   │   │   ├── http_util.py     共用 HTTP 与 jsonp 解析
│   │   │   ├── sina.py          行情 / 分钟线 / 日线
│   │   │   └── news.py          资讯聚合与关键词过滤
│   │   ├── model/
│   │   │   ├── features.py      技术指标与特征工程
│   │   │   └── forecaster.py    多周期预测
│   │   └── static/              前端（原生 JS + Canvas）
│   └── requirements.txt
├── data/                        SQLite 数据目录（不入库）
├── docs/
├── scripts/
├── run.sh
└── README.md
```

## 七、Android APK

APK 是一个 WebView 壳，加载局域网内的后端地址，本身不含数据。

```bash
# 构建（首次需联网下载 Gradle 依赖，之后可离线）
bash android/build-apk.sh
```

产物：`releases/futures-forecast-v1.0.0.apk`（约 2.3 MB，已签名）

使用步骤：

1. 电脑上 `bash run.sh` 启动后端，记下电脑局域网 IP（如 `192.168.1.10`）
2. 手机安装 APK，首次打开会弹出「服务器地址」对话框
3. 填 `http://192.168.1.10:8000`，保存即可
4. 之后点击右下角齿轮可随时修改地址

手机与电脑需在同一局域网。服务用 HTTPS 暴露到公网时也可直接填公网地址。

## 八、自动更新

`release/version.json` 是客户端读取的版本清单：

```json
{
  "version": "1.0.0",
  "versionCode": 1,
  "url": "https://gitee.com/zpj100/futures-forecast/releases",
  "mirror": "https://github.com/zpj100/futures-forecast/releases/latest",
  "notes": "首个版本：三品种实时行情、六周期趋势预测、量化指标、资讯聚合与 Web 仪表盘。"
}
```

APK 启动后拉取 Gitee 上的 `release/version.json`，`versionCode` 大于本地值时弹窗提示更新，点击跳转 Release 页。
仓库同时镜像到 GitHub，Gitee 不可达时可手动从 GitHub下载。

发新版流程：改 `android/app/build.gradle` 的 `versionCode` / `versionName` → 构建 → 同步更新 `release/version.json` → 打 Gitee Release 并上传 APK。

## 九、免责声明

本项目为量化统计研究与可视化演示，所有预测与建议均不构成投资建议。期货交易有杠杆风险，请自行判断并控制仓位。
