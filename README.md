# 学记期货 · futures-forecast

手机 App（学记期货）＋ Python 研究后端两件套。

App 端**完全独立运行**：直连新浪公开行情，把秒级快照、分钟线、日线落进手机 SQLite，
在本地算指标与六周期方向，界面为移动优先的深色/白天双配色，带预测留档与命中率统计，
并可从私有仓库自助更新、可选接入 DeepSeek 等大模型做独立分析。

后端是本仓库的量化研究工具（岭回归 + 前向验证），用于回测与调参，App 不依赖它。

下面三个品种的实时采集与六周期（1分钟 / 5分钟 / 10分钟 / 1小时 / 8小时 / 1天）方向预测逻辑两端同源：
**电解铝（沪铝主力 AL0）**、**氧化铝（沪氧化铝主力 AO0）**、**伦铝（LME 3M AHD）**。

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

## 七、Android App（学记期货）

2.0 起 APK 不再依赖电脑：采集、存储、指标、预测全部在手机内完成，装完即用。

| 项 | 说明 |
|---|---|
| 应用名 | 学记期货（图标为深色底 + 白色方框，框内双行小字） |
| 数据 | Java 直连新浪公开行情，秒级快照 / 分钟线 / 日线写入手机 SQLite |
| 预置数据 | APK 内置 `assets/seed.db`：约 1.9 万根真实 K 线（日线 2005 起、分钟线 1/5/15/30/60 分钟），首装即有线可看 |
| 刷新 | 默认 30 秒，可切 5 秒 / 10 秒 / 30 秒 / 1 分钟 / 5 分钟，另有手动刷新 |
| 界面 | 底部四标签（行情 / 预测 / 历史 / 设置）；行情页三品种一屏并排；**黑夜 / 白天**双配色 |
| 预测 | 每次预测落库，到期自动回填实际价并统计方向命中率 |
| 大模型 | 默认 DeepSeek，可切任意 OpenAI 兼容端点，Key 用 AndroidKeyStore 加密存本机 |

```bash
# 构建（WSL Debian + JDK 21 + Gradle 8.9 + SDK 34）
python3 scripts/build_seed.py      # 可选：重新抓一份预置行情（约 1.5 MB）
bash android/build-apk.sh
```

### 历史数据与同步

发版前用 `scripts/build_seed.py` 抓一份真实行情打进包里（不做任何合成）：

| 周期 | 根数（每品种） | 覆盖范围 |
|------|---------------|----------|
| 日线 | 沪铝 5313 / 氧化铝 821 / 伦铝 2537 | 2005 / 2023 / 2016 至今 |
| 60 分钟 | 1023 | 约 195 天（可覆盖「最近 180 天」） |
| 30 / 15 / 5 / 1 分钟 | 各 1023 | 100 / 56 / 17 / 4 天 |

设置页「同步历史数据」与官方源做**增量**合并：只把「本地最后一根及之后」的 bar 写库
（boundary 那根会被替换刷新），重复点击不会重复写库，结果回显每周期「新增根数 + 本地总量」。
源站每周期最多返回 1023 根，因此 1 分钟线只能覆盖最近几个交易日；**秒级点公开接口不提供历史，
只能由 App 运行时累积**（保留 30 天），预置包里不含伪造的秒级数据。

> **预置库导入的两个坑（v2.1.2 修复）**
> 1. `seed_meta.version` 是 12 位时间戳（`202611092054`），旧代码用 `Integer.parseInt` 解析必然
>    `NumberFormatException`，被 catch 成 0 后直接判定「无需导入」——1.9 万根 K 线从未进过手机库，
>    于是装完曲线一直「等待数据」。现改为 **long 解析**。
> 2. 首次导入失败后没有自愈：现在只要本地 `kline` 为空就强制导入一次（`INSERT OR IGNORE`，不覆盖新数据）；
>    另加「ATTACH 失败则逐行写入」兜底与 `assets/seed_ver.txt` 版本旁文件（命中就不再拷贝 1.5MB）。

产物：`releases/futures-forecast-v2.1.2.apk`（约 3.0 MB，已签名，minSdk 24）

装好后无需任何配置即可看到行情；要大模型分析就在「设置」填 API Key，
要 App 内自助更新就在「设置」填 Gitee 个人令牌。

## 八、版本与自动更新

版本号规则：`versionCode = 主*100 + 次*10 + 修订`（2.1.2 → 212），Release 正文首行标注 `versionCode: 212`。

**更新源固定为 Gitee（首要源）**：App 内「设置 → 应用更新」带令牌读取本仓库 `releases/latest`，
比较 `versionCode` 后下载并调起安装。令牌只存手机端（AndroidKeyStore 加密），不上传任何服务器。

> **为什么必须走附件 API（v2.1.1 修复 403）**
> 私有仓库的发行版直链 `https://gitee.com/zpj100/futures-forecast/releases/download/v2.1.1/xxx.apk`
> 无论带 `Authorization: token` 头还是 `?access_token=` **都返回 403**（该地址要浏览器登录态 Cookie）。
> 唯一可用路径是附件接口：
>
> ```
> GET https://gitee.com/api/v5/repos/{owner}/{repo}/releases/{release_id}/attach_files/{attach_file_id}/download?access_token=xxx
> ```
>
> 它 303 跳到 `foruda.gitee.com` 的临时签名地址，客户端需**手动逐跳跟随**（Java 默认自动跳转会丢参数）。
> `attach_file_id` 与文件体积只能从 `/releases/{id}/attach_files` 列表取——
> `releases/latest` 的 `assets` 里既没有 id 也没有 size。

`release/version.json` 保留为可读的版本清单，便于脚本或第三方读取：

```json
{
  "version": "2.1.2",
  "versionCode": 212,
  "url": "https://gitee.com/zpj100/futures-forecast/releases",
  "notes": "…"
}
```

发新版流程：改 `android/app/build.gradle` 的 `versionCode` / `versionName`
→ `bash android/build-apk.sh` → 更新 `release/version.json` → 提交并打 tag（`v2.1.2`）
→ 创建 Gitee Release 并上传 APK（正文必须含 `versionCode: 212`）→ `python3 scripts/delete_asset.py` 可清理旧附件。

## 九、免责声明

本项目为量化统计研究与可视化演示，所有预测与建议均不构成投资建议。期货交易有杠杆风险，请自行判断并控制仓位。
