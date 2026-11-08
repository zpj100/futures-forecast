"""Feature engineering and technical indicators (numpy only, no heavy deps)."""
from __future__ import annotations

import numpy as np


def ema(x: np.ndarray, span: int) -> np.ndarray:
    if len(x) == 0:
        return x
    alpha = 2.0 / (span + 1.0)
    out = np.empty_like(x, dtype=float)
    out[0] = x[0]
    for i in range(1, len(x)):
        out[i] = alpha * x[i] + (1 - alpha) * out[i - 1]
    return out


def rsi(x: np.ndarray, period: int = 14) -> np.ndarray:
    if len(x) < 2:
        return np.full(len(x), 50.0)
    d = np.diff(x, prepend=x[0])
    gain = np.where(d > 0, d, 0.0)
    loss = np.where(d < 0, -d, 0.0)
    ag = np.empty_like(x)
    al = np.empty_like(x)
    ag[0] = gain[0]
    al[0] = loss[0]
    a = 1.0 / period
    for i in range(1, len(x)):
        ag[i] = a * gain[i] + (1 - a) * ag[i - 1]
        al[i] = a * loss[i] + (1 - a) * al[i - 1]
    rs = np.divide(ag, al, out=np.full_like(ag, 1e9), where=al > 1e-12)
    return 100.0 - 100.0 / (1.0 + rs)


def atr(high: np.ndarray, low: np.ndarray, close: np.ndarray, period: int = 14) -> np.ndarray:
    prev = np.roll(close, 1)
    prev[0] = close[0]
    tr = np.maximum(high - low, np.maximum(np.abs(high - prev), np.abs(low - prev)))
    return ema(tr, period)


def rolling_std(x: np.ndarray, period: int) -> np.ndarray:
    n = len(x)
    out = np.zeros(n)
    for i in range(n):
        lo = max(0, i - period + 1)
        out[i] = x[lo:i + 1].std() if i + 1 > lo else 0.0
    return out


def build_features(close: np.ndarray, high: np.ndarray, low: np.ndarray,
                   vol: np.ndarray) -> tuple[np.ndarray, list[str]]:
    """Return (feature_matrix[n, k], names). Rows align with `close`."""
    n = len(close)
    logc = np.log(np.maximum(close, 1e-9))
    ret1 = np.diff(logc, prepend=logc[0])

    e5, e10, e20, e60 = (ema(close, s) for s in (5, 10, 20, 60))
    macd = ema(close, 12) - ema(close, 26)
    macd_sig = ema(macd, 9)
    r14 = rsi(close, 14)
    a14 = atr(high, low, close, 14)

    ma20 = ema(close, 20)
    dev = close - ma20
    sd20 = rolling_std(dev, 20)
    boll_z = np.divide(dev, sd20, out=np.zeros(n), where=sd20 > 1e-9)

    volstd = rolling_std(ret1, 20)
    vmean = np.zeros(n)
    for i in range(n):
        lo = max(0, i - 20)
        vmean[i] = vol[lo:i + 1].mean() if i > lo else vol[i]

    feats = np.column_stack([
        ret1 / (volstd + 1e-9),
        np.log(e5 / e20),
        np.log(e10 / e60),
        np.log(e5 / close),
        (r14 - 50.0) / 50.0,
        (macd - macd_sig) / (close + 1e-9),
        boll_z,
        a14 / (close + 1e-9),
        volstd,
        np.log((vol + 1.0) / (vmean + 1.0)),
        np.clip(np.arange(n) / max(n - 1, 1), 0, 1),
    ])
    names = ["ret1_z", "ema5_20", "ema10_60", "ema5_px", "rsi", "macd_hist",
             "boll_z", "atr_pct", "vol20", "vol_ratio", "trend_time"]
    return np.nan_to_num(feats, nan=0.0, posinf=0.0, neginf=0.0), names


def indicators(close: np.ndarray, high: np.ndarray, low: np.ndarray,
               vol: np.ndarray) -> dict:
    if len(close) < 30:
        return {}
    e5, e10, e20, e60 = (ema(close, s) for s in (5, 10, 20, 60))
    macd = ema(close, 12) - ema(close, 26)
    macd_sig = ema(macd, 9)
    r14 = rsi(close, 14)
    a14 = atr(high, low, close, 14)
    ret1 = np.diff(np.log(np.maximum(close, 1e-9)), prepend=0.0)
    volstd = rolling_std(ret1, 20)
    ma20 = e20
    sd20 = rolling_std(close - ma20, 20)
    upper = ma20 + 2 * sd20
    lower = ma20 - 2 * sd20

    lo = float(low[-120:].min())
    hi = float(high[-120:].max())
    px = float(close[-1])

    def r(v: float, nd: int = 2) -> float:
        return round(float(v), nd)

    return {
        "price": r(px, 4),
        "ema5": r(e5[-1]), "ema10": r(e10[-1]),
        "ema20": r(e20[-1]), "ema60": r(e60[-1]),
        "rsi": r(r14[-1], 1),
        "macd": r(macd[-1], 4),
        "macd_signal": r(macd_sig[-1], 4),
        "macd_hist": r(macd[-1] - macd_sig[-1], 4),
        "atr": r(a14[-1], 4),
        "atr_pct": r(a14[-1] / px * 100, 3),
        "boll_upper": r(upper[-1], 3),
        "boll_lower": r(lower[-1], 3),
        "boll_pos": r((px - lower[-1]) / max(upper[-1] - lower[-1], 1e-9), 3),
        "vol20_pct": r(volstd[-1] * 100, 3),
        "range_low": r(lo, 3),
        "range_high": r(hi, 3),
        "range_pos": r((px - lo) / max(hi - lo, 1e-9), 3),
        "vol_change": r(vol[-1] / max(vol[-21:-1].mean(), 1e-9), 2)
        if len(vol) > 21 else 1.0,
        "bias": "偏多" if e5[-1] > e20[-1] > e60[-1]
                else "偏空" if e5[-1] < e20[-1] < e60[-1] else "震荡",
    }
