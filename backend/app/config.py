"""Global configuration for the futures forecast service."""
import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent.parent
DATA_DIR = Path(os.getenv("FF_DATA_DIR", BASE_DIR / "data"))
DATA_DIR.mkdir(parents=True, exist_ok=True)

DB_PATH = DATA_DIR / "futures.db"
STATIC_DIR = Path(__file__).resolve().parent / "static"

# Three tracked products.
# code     : internal key
# name     : display name
# sina_rt  : realtime quote symbol (hq.sinajs.cn)
# market   : "inner" (SHFE) or "global" (LME)
# minute_sym: symbol used by the sina minute-kline API
# price_mult: multiplier applied to raw quote to get a comparable display price
PRODUCTS = [
    {
        "code": "AL",
        "name": "电解铝（沪铝主力）",
        "short": "沪铝",
        "sina_rt": "nf_AL0",
        "market": "inner",
        "minute_sym": "AL0",
        "day_sym": "AL0",
        "unit": "元/吨",
    },
    {
        "code": "AO",
        "name": "氧化铝（沪氧化铝主力）",
        "short": "氧化铝",
        "sina_rt": "nf_AO0",
        "market": "inner",
        "minute_sym": "AO0",
        "day_sym": "AO0",
        "unit": "元/吨",
    },
    {
        "code": "AHD",
        "name": "伦铝（LME 3M）",
        "short": "伦铝",
        "sina_rt": "hf_AHD",
        "market": "global",
        "minute_sym": "AHD",
        "day_sym": "AHD",
        "unit": "美元/吨",
    },
]

PRODUCT_BY_CODE = {p["code"]: p for p in PRODUCTS}

# 唯一的预测周期定义源：(key, label, minutes, series, step)
#   series: "minute" 用 1 分钟序列按 step 分钟做时间桶聚合 / "daily" 用日线序列
#   step  : 该档真实周期，既作为时间桶宽度，也作为前向标签的回看根数
# main.py 的 /api/products 与 model/forecaster.py 都由此派生，避免重复硬编码。
HORIZONS = [
    ("1m", "1分钟", 1, "minute", 1),
    ("5m", "5分钟", 5, "minute", 5),
    ("10m", "10分钟", 10, "minute", 10),
    ("1h", "1小时", 60, "minute", 60),
    ("8h", "8小时", 480, "daily", 1),
    ("1d", "1天", 1440, "daily", 1),
]

# App 对照接口使用的七档（与 Android Forecaster.HORIZONS 一致：1/5/15/30/240/480/1440 分钟）。
# 既有 /api/forecast 仍只由 HORIZONS 六档驱动；forecast_one 通过可选参数切到本定义算七档，
# 从而「30m/4h 用聚合序列补算」，样本不足时沿用 _empty 的「数据不足 / p_up=0.5」，不伪造精度。
APP_HORIZONS = [
    ("1m", "1分钟", 1, "minute", 1),
    ("5m", "5分钟", 5, "minute", 5),
    ("15m", "15分钟", 15, "minute", 15),
    ("30m", "30分钟", 30, "minute", 30),
    ("4h", "4小时", 240, "minute", 240),
    ("8h", "8小时", 480, "daily", 1),
    ("1d", "1天", 1440, "daily", 1),
]

# 多周期落库：从 1m 序列按「自然时间桶起点」聚合出的派生周期 (period, step 分钟)。
# backend 内部只用这一种桶口径（毫秒 ts），与 Android aggregateByTime 一致。
MULTI_PERIODS = [("5m", 5), ("15m", 15), ("30m", 30), ("60m", 60)]

# Seconds between realtime snapshot captures (drives the "second-level" archive).
SNAPSHOT_INTERVAL = int(os.getenv("FF_SNAPSHOT_INTERVAL", "3"))
# Seconds between minute-kline refreshes.
KLINE_INTERVAL = int(os.getenv("FF_KLINE_INTERVAL", "60"))
# Seconds between news refreshes.
NEWS_INTERVAL = int(os.getenv("FF_NEWS_INTERVAL", "300"))

HTTP_TIMEOUT = float(os.getenv("FF_HTTP_TIMEOUT", "10"))
HTTP_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
)
