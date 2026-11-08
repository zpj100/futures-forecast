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

# Forecast horizons: key -> (label, minutes)
HORIZONS = [
    ("1m", "1分钟", 1),
    ("5m", "5分钟", 5),
    ("10m", "10分钟", 10),
    ("1h", "1小时", 60),
    ("8h", "8小时", 480),
    ("1d", "1天", 1440),
]

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
