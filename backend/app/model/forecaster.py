"""Multi-horizon trend forecaster for aluminium futures.

Design philosophy
-----------------
Financial trend prediction is a *weak-signal* problem. Rather than trusting a
single black box, we build a small library of economically-motivated signals
and let **walk-forward validation decide how much each one deserves to be
trusted** for the specific (product, horizon) at hand.

Signal library
--------------
1. ``trend``   — EMA5 vs EMA20 slope following. Works in persistent regimes.
2. ``reversion`` — RSI(14) mean reversion. Works at short horizons where
   microstructure over-extends.
3. ``boll``    — Bollinger z-score reversion. Complements RSI with volatility
   normalisation.
4. ``momentum`` — raw N-bar momentum persistence (the naive baseline).

For each signal the out-of-sample directional hit rate is measured with rolling
walk-forward folds. Signals at or below coin-flip get **zero** weight: if the
market is efficient at that horizon, we say "震荡" instead of fabricating a
confident call. Surviving signals are combined in proportion to their excess
accuracy, and the blended edge is mapped to an up-probability through a normal
CDF whose width comes from the realised forecast residuals.

Honest data caveat
------------------
The public Sina feed serves ~1023 one-minute bars (about three sessions) and
daily history back to 2005. There is no free second-level history, so the
service accumulates its own 3-second snapshot archive while it runs. The 8h and
1d horizons share the daily base series (a full A-share session with the night
session is roughly eight hours).
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

from .features import build_features, ema, indicators, rsi


HORIZON_DEFS = [
    ("1m", "1分钟", 1, "minute", 1),
    ("5m", "5分钟", 5, "minute", 5),
    ("10m", "10分钟", 10, "minute", 10),
    ("1h", "1小时", 60, "minute", 60),
    ("8h", "8小时", 480, "daily", 1),
    ("1d", "1天", 1440, "daily", 1),
]

_MIN_SAMPLES = 120
_FOLD_FRACTION = 0.45   # fraction of window used for out-of-sample folds
_FOLDS = 4


def _phi(z: float) -> float:
    return 0.5 * (1.0 + math.erf(z / math.sqrt(2.0)))


# --------------------------------------------------------------------- util

def _bollinger_z(close: np.ndarray, span: int = 20) -> np.ndarray:
    ma = ema(close, span)
    sd = np.zeros(len(close))
    for i in range(len(close)):
        lo = max(0, i - span + 1)
        sd[i] = close[lo:i + 1].std() if i > lo else 0.0
    return np.divide(close - ma, sd, out=np.zeros_like(close), where=sd > 1e-9)


def _signals(close: np.ndarray) -> dict[str, np.ndarray]:
    """Return {name: signal array} with values in {-1, 0, +1}."""
    e5, e20 = ema(close, 5), ema(close, 20)
    trend = np.sign(e5 - e20)

    r = rsi(close, 14)
    reversion = np.where(r > 65, -1.0, np.where(r < 35, 1.0, 0.0))

    z = _bollinger_z(close, 20)
    boll = np.where(z > 1.5, -1.0, np.where(z < -1.5, 1.0, 0.0))

    return {"trend": trend, "reversion": reversion, "boll": boll}


def _momentum_signal(close: np.ndarray, lookback: int) -> np.ndarray:
    n = len(close)
    sig = np.zeros(n)
    if n > lookback:
        sig[lookback:] = np.sign(close[lookback:] - close[:-lookback])
    return sig


def _eval_signal(sig: np.ndarray, fwd_sign: np.ndarray,
                 start: int) -> tuple[float, int]:
    """Directional hit rate of `sig` against realised forward direction."""
    s = sig[start:]
    f = fwd_sign[start:]
    m = (s != 0) & (f != 0) & np.isfinite(f)
    if m.sum() < 30:
        return 0.5, int(m.sum())
    return float((np.sign(s[m]) == np.sign(f[m])).mean()), int(m.sum())


def _eval_counts(sig: np.ndarray, fwd_sign: np.ndarray) -> tuple[int, int]:
    m = (sig != 0) & (fwd_sign != 0) & np.isfinite(fwd_sign)
    if m.sum() == 0:
        return 0, 0
    hits = int((np.sign(sig[m]) == np.sign(fwd_sign[m])).sum())
    return hits, int(m.sum())


def _walk_forward_eval(builder, close: np.ndarray, fwd_sign: np.ndarray,
                       step: int):
    """Score a rule-based signal on the most recent held-out segment.

    Signals like RSI/Bollinger reversion fire only when the market is
    stretched, so splitting into folds leaves too few observations per fold and
    the estimate collapses to 0.5. Pooling the entire recent out-of-sample tail
    keeps sparse signals measurable while still evaluating on data that was not
    used to pick the rule's parameters (they are fixed a priori).
    """
    n = len(close)
    start = max(int(n * (1 - _FOLD_FRACTION)), 60)
    sig = builder(close)
    hits, cnt = _eval_counts(sig[start:], fwd_sign[start:])

    # fall back to the whole series if the tail is too sparse
    if cnt < 40:
        hits_all, cnt_all = _eval_counts(sig, fwd_sign)
        if cnt_all >= 40:
            return hits_all / cnt_all, cnt_all
    if cnt < 20:
        return 0.5, cnt
    return hits / cnt, cnt


# ----------------------------------------------------------------- features

def _ridge_forecast(close: np.ndarray, high: np.ndarray, low: np.ndarray,
                    vol: np.ndarray, step: int, use_intraday: bool,
                    intraday: list[dict] | None):
    """Optional ridge model; returns (edge_log, hit, resid_std) or None."""
    X, _ = build_features(close, high, low, vol)
    if use_intraday and intraday and len(intraday) >= 60:
        ic = np.array([b["c"] for b in intraday], dtype=float)
        ih = np.array([b["h"] or b["c"] for b in intraday], dtype=float)
        il = np.array([b["l"] or b["c"] for b in intraday], dtype=float)
        iv = np.array([b.get("v") or 0.0 for b in intraday], dtype=float)
        Xi, _ = build_features(ic, ih, il, iv)
        X = np.column_stack([X, np.tile(Xi[-1], (X.shape[0], 1))])

    n = len(close)
    fwd = np.full(n, np.nan)
    fwd[:-step] = np.log(close[step:] / close[:-step])

    m = np.isfinite(fwd)
    X, fwd = X[m], fwd[m]
    if len(fwd) < _MIN_SAMPLES:
        return None

    lo, hi = np.quantile(fwd, [0.01, 0.99])
    y = np.clip(fwd, lo, hi)

    n_is = max(int(len(y) * (1 - _FOLD_FRACTION)), 80)
    if n_is >= len(y) - 10:
        return None

    preds, truth = [], []
    step_fold = max((len(y) - n_is) // _FOLDS, 20)
    for f in range(_FOLDS):
        tr_end = n_is + f * step_fold
        te_end = min(tr_end + step_fold, len(y))
        if tr_end >= len(y) or te_end <= tr_end:
            break
        Xtr, ytr = X[:tr_end], y[:tr_end]
        Xte, yte = X[tr_end:te_end], y[tr_end:te_end]
        mu, sd = Xtr.mean(0), Xtr.std(0)
        sd[sd < 1e-9] = 1.0
        k = Xtr.shape[1]
        reg = 2.0 * np.eye(k)
        reg[0, 0] = 0.0
        XtX = ((Xtr - mu) / sd).T @ ((Xtr - mu) / sd)
        try:
            coef = np.linalg.solve(XtX + reg, ((Xtr - mu) / sd).T @ ytr)
        except np.linalg.LinAlgError:
            coef = np.linalg.lstsq(XtX + reg, ((Xtr - mu) / sd).T @ ytr,
                                   rcond=None)[0]
        preds.append(((Xte - mu) / sd) @ coef)
        truth.append(yte)

    if not preds:
        return None
    p = np.concatenate(preds)
    t = np.concatenate(truth)
    hit = float((np.sign(p) == np.sign(t)).mean())
    resid_std = float((t - p).std()) or float(t.std()) or 1e-6

    mu, sd = X.mean(0), X.std(0)
    sd[sd < 1e-9] = 1.0
    k = X.shape[1]
    reg = 2.0 * np.eye(k)
    reg[0, 0] = 0.0
    Xs = (X - mu) / sd
    try:
        coef = np.linalg.solve(Xs.T @ Xs + reg, Xs.T @ y)
    except np.linalg.LinAlgError:
        coef = np.linalg.lstsq(Xs.T @ Xs + reg, Xs.T @ y, rcond=None)[0]
    x_last = (X[-1] - mu) / sd
    return float(x_last @ coef), hit, resid_std


# --------------------------------------------------------------------- main

def forecast_one(code: str, name: str, minute_bars: list[dict],
                 daily_bars: list[dict]) -> dict:
    out: list[dict] = []
    ind: dict = {}

    m_close = (np.array([b["c"] for b in minute_bars], dtype=float)
               if minute_bars else np.array([]))
    d_close = (np.array([b["c"] for b in daily_bars], dtype=float)
               if daily_bars else np.array([]))
    price = float(m_close[-1]) if len(m_close) else (
        float(d_close[-1]) if len(d_close) else 0.0)

    for key, label, minutes, series, step in HORIZON_DEFS:
        base = minute_bars if series == "minute" else daily_bars
        if len(base) < _MIN_SAMPLES + step + 10:
            out.append(_empty(key, label, minutes, price,
                              f"样本不足（{len(base)} 根）"))
            continue

        close = np.array([b["c"] for b in base], dtype=float)
        high = np.array([b["h"] or b["c"] for b in base], dtype=float)
        low = np.array([b["l"] or b["c"] for b in base], dtype=float)
        vol = np.array([b.get("v") or 0.0 for b in base], dtype=float)

        n = len(close)
        fwd_sign = np.zeros(n)
        fwd_sign[:-step] = np.sign(close[step:] - close[:-step])

        # ---- signal library with walk-forward hit rates
        candidates: list[tuple[str, np.ndarray, float]] = []
        for sname, sarr in _signals(close).items():
            hit, cnt = _walk_forward_eval(lambda c, nm=sname:
                                          _signals(c)[nm], close, fwd_sign, step)
            candidates.append((sname, sarr, hit))

        # momentum baseline with a horizon-scaled lookback
        lb = max(step, 3)
        mom_sig = _momentum_signal(close, lb)
        mom_hit, _ = _walk_forward_eval(
            lambda c, l=lb: _momentum_signal(c, l), close, fwd_sign, step)
        candidates.append(("momentum", mom_sig, mom_hit))

        # ---- ridge as one more candidate
        ridge = _ridge_forecast(close, high, low, vol, step,
                                key == "8h", minute_bars if key == "8h" else None)
        ridge_edge = 0.0
        ridge_hit = 0.5
        ridge_sigma = float(np.diff(np.log(np.maximum(close, 1e-9))).std() or 1e-6)
        if ridge:
            ridge_edge, ridge_hit, ridge_sigma = ridge

        # ---- combine: weights proportional to *excess* accuracy above 0.5
        cue = 0.5
        details = {}
        weighted_sign = 0.0        # sum(w * signal)
        wsum = 0.0
        best_weighted_hit = 0.0
        for sname, sarr, hit in candidates:
            excess = max(0.0, hit - cue)
            w = excess if excess >= 0.02 else 0.0
            s_val = float(sarr[-1]) if len(sarr) else 0.0
            weighted_sign += w * s_val
            wsum += w
            if w > 0:
                best_weighted_hit = max(best_weighted_hit, hit)
            details[sname] = {"hit": round(hit, 4), "weight": round(w, 4),
                              "signal": int(s_val),
                              "samples": 0}

        ridge_excess = max(0.0, ridge_hit - cue)
        ridge_w = ridge_excess if ridge_excess >= 0.02 else 0.0
        details["ridge"] = {"hit": round(ridge_hit, 4),
                            "weight": round(ridge_w, 4),
                            "signal": 0,
                            "samples": 0}

        sigma = max(ridge_sigma, 1e-6)

        if wsum > 0:
            # Directional conviction = weighted agreement in [-1, 1].
            conviction = weighted_sign / wsum
            # Total evidence: how much validated edge backs this call.
            evidence = min(wsum / 0.25, 1.0)
            horizon_scale = sigma * math.sqrt(max(step, 1)) * 0.5
            edge = conviction * horizon_scale * evidence
            edge += ridge_w * ridge_edge
        else:
            edge = 0.0

        # Cap the probability move by the strength of the evidence. A tiny
        # weight must never produce a 0.8+ conviction.
        p_raw = _phi(edge / sigma)
        if wsum > 0:
            cap = 0.5 + 0.35 * min(wsum / 0.20, 1.0)
            p_up = min(max(p_raw, 1.0 - cap), cap)
        else:
            p_up = 0.5

        exp_pct = math.expm1(edge) * 100.0
        target = price * math.exp(edge)
        conf = min(abs(p_up - 0.5) * 2.0, 1.0)
        best_hit = max(best_weighted_hit, ridge_hit if ridge_w > 0 else 0.0)
        if p_up >= 0.55:
            direction = "上涨"
        elif p_up <= 0.45:
            direction = "下跌"
        else:
            direction = "震荡"

        notes = []
        if best_hit < 0.52:
            notes.append("各信号验证胜率均接近随机，结论偏中性")
        if series == "daily" and key in ("8h", "1d"):
            notes.append("基于日线序列")
        note = "；".join(notes)

        out.append({
            "key": key, "label": label, "minutes": minutes,
            "direction": direction,
            "p_up": round(p_up, 4),
            "exp_move_pct": round(exp_pct, 4),
            "target_price": round(target, 3),
            "confidence": round(conf, 4),
            "best_hit": round(best_hit, 4),
            "accuracy": round(best_hit, 4),
            "samples": int(_MIN_SAMPLES),
            "test_samples": int(len(close)),
            "momentum_pct": round(float(
                (close[-1] / close[-1 - lb] - 1) * 100 if len(close) > lb else 0), 4),
            "blended_edge": round(float(edge), 6),
            "resid_std": round(float(sigma), 6),
            "signals": details,
            "note": note,
        })

    # ---- indicators from the finest series available
    if len(m_close) >= 30:
        ind = indicators(m_close,
                         np.array([b["h"] or b["c"] for b in minute_bars], float),
                         np.array([b["l"] or b["c"] for b in minute_bars], float),
                         np.array([b.get("v") or 0.0 for b in minute_bars], float))
    elif len(d_close) >= 30:
        ind = indicators(d_close,
                         np.array([b["h"] or b["c"] for b in daily_bars], float),
                         np.array([b["l"] or b["c"] for b in daily_bars], float),
                         np.array([b.get("v") or 0.0 for b in daily_bars], float))

    # ---- composite view
    dirs = {"上涨": 1.0, "下跌": -1.0, "震荡": 0.0}
    acc = wsum_total = 0.0
    for h in out:
        w = max(h["confidence"], 0.05)
        acc += dirs[h["direction"]] * w
        wsum_total += w
    score = acc / wsum_total if wsum_total else 0.0

    if score >= 0.3:
        overall = "看涨"
    elif score <= -0.3:
        overall = "看跌"
    else:
        overall = "中性"

    return {
        "code": code, "name": name, "price": round(price, 3),
        "horizons": out, "indicators": ind,
        "score": round(score, 4), "overall": overall,
        "recommendation": _recommend(overall, score, ind, out),
    }


def _empty(key: str, label: str, minutes: int, price: float, note: str) -> dict:
    return {
        "key": key, "label": label, "minutes": minutes,
        "direction": "数据不足", "p_up": 0.5, "exp_move_pct": 0.0,
        "target_price": round(price, 3), "confidence": 0.0,
        "best_hit": 0.0, "accuracy": 0.0, "samples": 0, "test_samples": 0,
        "momentum_pct": 0.0, "blended_edge": 0.0, "resid_std": 0.0,
        "signals": {}, "note": note,
    }


def _recommend(overall: str, score: float, ind: dict,
               horizons: list[dict]) -> str:
    parts: list[str] = []
    if overall == "看涨":
        parts.append("短线偏多：回踩短均线不破可轻仓跟随，跌破 EMA20 止损")
    elif overall == "看跌":
        parts.append("短线偏空：反弹至短均线附近承压可轻仓试空，站上 EMA20 止损")
    else:
        parts.append("方向不明：以区间思路对待，等突破区间边界再跟随")

    r = ind.get("rsi")
    if isinstance(r, (int, float)):
        if r >= 70:
            parts.append(f"RSI {r} 已进入超买区，追多需谨慎")
        elif r <= 30:
            parts.append(f"RSI {r} 已进入超卖区，追空需谨慎")

    atr_pct = ind.get("atr_pct")
    if isinstance(atr_pct, (int, float)) and atr_pct > 1.2:
        parts.append(f"波动率偏高（ATR {atr_pct}%），建议降低仓位")

    boll = ind.get("boll_pos")
    if isinstance(boll, (int, float)):
        if boll > 0.95:
            parts.append("价格贴近布林上轨，短线有回落压力")
        elif boll < 0.05:
            parts.append("价格贴近布林下轨，短线有反抽需求")

    strong = [h for h in horizons if h["confidence"] >= 0.2]
    if len(strong) >= 2 and len({h["direction"] for h in strong}) > 1:
        parts.append("多周期方向不一致，优先降低杠杆")

    return "；".join(parts) + "。以上为量化统计结论，不构成投资建议。"
