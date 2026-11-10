package com.zpj.futures.quant;

import com.zpj.futures.data.SinaClient;
import com.zpj.futures.db.Bar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 多周期方向预测（移植自 backend/app/model/forecaster.py 的信号融合部分）。
 *
 * 与 Python 版的差异：不做在线岭回归训练（手机上代价高且易过拟合），
 * 保留「信号库 + 走前验证胜率加权 + 证据收缩概率上限」这套主体逻辑，
 * 结论口径与后端一致：胜率接近随机的周期输出「震荡」。
 */
public class Forecaster {

    /**
     * 预测档位：与大模型分析的周期一一对应，便于互相印证。
     * 3 位是「分钟数」，用于到期时间与展示；实际计算以 {源序列, 每根代表的分钟数} 决定。
     */
    public static final String[][] HORIZONS = {
            {"1m", "1分钟", "1"},
            {"5m", "5分钟", "5"},
            {"15m", "15分钟", "15"},
            {"30m", "30分钟", "30"},
            {"4h", "4小时", "240"},
            {"8h", "8小时", "480"},
            {"1d", "1天", "1440"},
    };

    /**
     * CST 夜盘 21:00 对应的 UTC 毫秒相位（Asia/Shanghai = UTC+8，21:00 CST = 13:00 UTC）。
     * 用于 4h/8h 时间桶对齐：桶边界从夜盘开盘 21:00 起算，而非 UTC 自然整点。
     */
    public static final long CST_21_00_OFFSET = 13L * 3600_000L;

    public static JSONObject forecast(String code, List<Bar> minute, List<Bar> hour60,
                                      List<Bar> daily) {
        return forecast(code, minute, hour60, daily, null, false);
    }

    /**
     * @param prev      上一次的完整结果；{@code shortOnly} 时用它复用 4h / 8h / 1d 三档
     * @param shortOnly true 只重算 ≤30 分钟的四档（整分钟调度用），长周期档沿用 prev
     */
    public static JSONObject forecast(String code, List<Bar> minute, List<Bar> hour60,
                                      List<Bar> daily, JSONObject prev, boolean shortOnly) {
        JSONObject out = new JSONObject();
        double price = minute != null && !minute.isEmpty() ? minute.get(minute.size() - 1).c
                : (daily != null && !daily.isEmpty() ? daily.get(daily.size() - 1).c : 0);
        JSONArray horizons = new JSONArray();
        double acc = 0, wsumTotal = 0;

        for (String[] h : HORIZONS) {
            String key = h[0];
            int minutes = Integer.parseInt(h[2]);

            // 伦铝 AHD 没有 60m 序列，4h/8h 恒「数据不足」。明确标「不适用」，
            // 并从综合投票分母 wsumTotal 剔除，避免 0.05 的无效权重稀释 score。
            if ("AHD".equals(code) && (minutes == 240 || minutes == 480)) {
                JSONObject na = new JSONObject();
                try {
                    na.put("key", key);
                    na.put("label", h[1]);
                    na.put("minutes", minutes);
                    na.put("direction", "不适用");
                    na.put("p_up", 0.5);
                    na.put("confidence", 0);
                    na.put("exp_move_pct", 0);
                    na.put("target_price", Indicators.round(price, 3));
                    na.put("best_hit", 0);
                    na.put("signals", new JSONObject());
                    na.put("note", "伦铝源站仅提供当日分时/日线，无 60 分钟序列，此档不适用");
                } catch (Exception ignored) {
                }
                horizons.put(na);
                continue;
            }

            // 4h/8h 由 60 分钟线聚合，日线直接用，其余用 1 分钟线；
            // 聚合后「下一根」就是目标周期，因此走前验证的步长按 1 根算
            List<Bar> src;
            int horizonBars, lookback;
            if (minutes >= 1440) {
                src = daily;
                horizonBars = 1;
                lookback = 20;
            } else if (minutes >= 240) {
                src = aggregateByTime(hour60, minutes);
                horizonBars = 1;
                lookback = 6;
            } else {
                src = minute;
                horizonBars = minutes;
                lookback = minutes;
            }
            JSONObject r = shortOnly && minutes >= 240 ? reuse(prev, key) : null;
            if (r == null) r = one(key, h[1], minutes, src, horizonBars, lookback, price);
            horizons.put(r);
            double conf = r.optDouble("confidence", 0);
            double dir = "上涨".equals(r.optString("direction")) ? 1
                    : "下跌".equals(r.optString("direction")) ? -1 : 0;
            double w = Math.max(conf, 0.05);
            acc += dir * w;
            wsumTotal += w;
        }

        double score = wsumTotal > 0 ? acc / wsumTotal : 0;
        String overall = score >= 0.3 ? "看涨" : score <= -0.3 ? "看跌" : "中性";
        JSONObject ind = Indicators.summary(minute != null && minute.size() >= 30
                ? minute : (daily == null ? new ArrayList<>() : daily));

        try {
            out.put("code", code);
            out.put("price", Indicators.round(price, 3));
            out.put("horizons", horizons);
            out.put("indicators", ind);
            out.put("score", Indicators.round(score, 4));
            out.put("overall", overall);
            out.put("recommendation", recommend(overall, ind, horizons));
            out.put("source", "quant");
            out.put("mode", shortOnly ? "short" : "full");
            out.put("ts", System.currentTimeMillis());
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 从上次结果里取某一档（深拷贝），没有则返回 null。 */
    private static JSONObject reuse(JSONObject prev, String key) {
        if (prev == null) return null;
        JSONArray hs = prev.optJSONArray("horizons");
        if (hs == null) return null;
        for (int i = 0; i < hs.length(); i++) {
            JSONObject h = hs.optJSONObject(i);
            if (h != null && key.equals(h.optString("key"))) {
                try {
                    return new JSONObject(h.toString());
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * 按时间桶聚合成 OHLCV。
     * 短周期（≤60m）桶起点 = ⌊ts / 周期⌋ × 周期（UTC 自然桶）；
     * 4h/8h 按 CST 夜盘 21:00 对齐：t = ts - floorMod(ts - CST_21_00_OFFSET, bucket)。
     * 用时间对齐而不是「每 N 根一组」，否则序列首根错位会让长周期落在奇怪的时刻。
     */
    public static List<Bar> aggregateByTime(List<Bar> src, int minutes) {
        List<Bar> out = new ArrayList<>();
        if (src == null || src.isEmpty()) return out;
        if (minutes <= 1) {
            out.addAll(src);
            return out;
        }
        long bucket = minutes * 60_000L;
        long align = minutes >= 240 ? CST_21_00_OFFSET : 0L;
        long curTs = Long.MIN_VALUE;
        Bar cur = null;
        for (Bar b : src) {
            long t = b.ts - Math.floorMod(b.ts - align, bucket);
            if (cur == null || t != curTs) {
                if (cur != null) out.add(cur);
                curTs = t;
                cur = new Bar(t, b.o, b.h, b.l, b.c, b.v);
            } else {
                cur = new Bar(curTs, cur.o, Math.max(cur.h, b.h),
                        Math.min(cur.l, b.l), b.c, cur.v + b.v);
            }
        }
        if (cur != null) out.add(cur);
        return out;
    }

    /**
     * 可验证性质（高波动概率更保守）：固定 conviction / evidence / horizonBars，
     * sigma 越大，pRaw 越接近 0.5，即 ∂|pRaw-0.5|/∂sigma &lt; 0。
     *
     * <p>关键修复：分子 edge 不含 sigma，sigma 只出现在分母且不会被代数约掉
     * （旧实现 horizonScale=sigma*sqrt(bars)*0.5、pRaw=phi(edge/sigma)，sigma 被约掉，
     * 导致 p_up 与波动率无关）。
     */
    static double pUpFrom(double conviction, double evidence, double sigma, int horizonBars) {
        double hb = Math.sqrt(Math.max(horizonBars, 1));
        double edge = conviction * hb * 0.5 * evidence;      // 不含 sigma
        double denom = sigma * hb + 1e-9;                    // sigma 真正留在分母
        return phi(edge / denom);
    }

    private static JSONObject one(String key, String label, int minutes,
                                  List<Bar> bars, int horizonBars, int lookback,
                                  double fallbackPrice) {
        JSONObject o = new JSONObject();
        // 长周期（4h/8h/1d）样本天然偏少，适度放宽「数据不足」与走前验证的样本阈值，
        // 避免恒被判成「震荡」。短周期仍保持原来的严格阈值。
        boolean longHorizon = minutes >= 240;
        int minBars = longHorizon ? Math.max(horizonBars + 8, 20) : Math.max(horizonBars + 12, 30);
        if (bars == null || bars.size() < minBars) {
            try {
                o.put("key", key);
                o.put("label", label);
                o.put("minutes", minutes);
                o.put("direction", "数据不足");
                o.put("p_up", 0.5);
                o.put("exp_move_pct", 0);
                o.put("target_price", Indicators.round(fallbackPrice, 3));
                o.put("confidence", 0);
                o.put("best_hit", 0);
                o.put("signals", new JSONObject());
                o.put("note", "样本不足，先积累数据");
            } catch (Exception ignored) {
            }
            return o;
        }

        int n = bars.size();
        double[] c = new double[n];
        for (int i = 0; i < n; i++) c[i] = bars.get(i).c;
        double px = c[n - 1];

        double[] trend = trendSignal(c);
        double[] rev = reversionSignal(c);
        double[] boll = bollSignal(c);
        int lb = Math.max(lookback, 1);
        double[] mom = momentumSignal(c, lb);

        String[] names = {"trend", "reversion", "boll", "momentum"};
        double[][] sigs = {trend, rev, boll, mom};
        JSONObject details = new JSONObject();

        int minTotal = longHorizon ? 4 : 8;
        double weighted = 0, wsum = 0, bestHit = 0;
        for (int i = 0; i < names.length; i++) {
            double hit = walkForward(c, sigs[i], horizonBars, minTotal);
            double excess = Math.max(0, hit - 0.5);
            double w = excess >= 0.02 ? excess : 0;
            double sv = sigs[i][n - 1];
            weighted += w * sv;
            wsum += w;
            if (w > 0) bestHit = Math.max(bestHit, hit);
            try {
                JSONObject d = new JSONObject();
                d.put("hit", Indicators.round(hit, 4));
                d.put("weight", Indicators.round(w, 4));
                d.put("signal", (int) sv);
                details.put(names[i], d);
            } catch (Exception ignored) {
            }
        }

        double sigma = 1e-6;
        double sum = 0, sum2 = 0;
        int cnt = 0;
        for (int i = 1; i < n; i++) {
            double r = Math.log(Math.max(c[i], 1e-9)) - Math.log(Math.max(c[i - 1], 1e-9));
            sum += r;
            sum2 += r * r;
            cnt++;
        }
        if (cnt > 1) {
            double mean = sum / cnt;
            sigma = Math.sqrt(Math.max(sum2 / cnt - mean * mean, 1e-12));
        }

        double move = 0, pUp = 0.5;
        if (wsum > 0) {
            double conviction = weighted / wsum;
            double evidence = Math.min(wsum / 0.25, 1.0);
            // p_up：sigma 留在分母（见 pUpFrom 的性质），高波动更保守
            double pRaw = pUpFrom(conviction, evidence, sigma, horizonBars);
            double cap = 0.5 + 0.35 * Math.min(wsum / 0.20, 1.0);
            pUp = Math.min(Math.max(pRaw, 1.0 - cap), cap);
            // 预期对数收益幅度仍带 sigma：保持 exp_move_pct / target_price 的原有量纲
            double hb = Math.sqrt(Math.max(horizonBars, 1));
            move = conviction * (sigma * hb) * 0.5 * evidence;
        }
        double expPct = (Math.exp(move) - 1) * 100;
        double target = px * Math.exp(move);
        double conf = Math.min(Math.abs(pUp - 0.5) * 2, 1);
        String dir = pUp >= 0.55 ? "上涨" : pUp <= 0.45 ? "下跌" : "震荡";

        try {
            o.put("key", key);
            o.put("label", label);
            o.put("minutes", minutes);
            o.put("direction", dir);
            o.put("p_up", Indicators.round(pUp, 4));
            o.put("exp_move_pct", Indicators.round(expPct, 4));
            o.put("target_price", Indicators.round(target, 3));
            o.put("confidence", Indicators.round(conf, 4));
            o.put("best_hit", Indicators.round(bestHit, 4));
            o.put("samples", n);
            o.put("signals", details);
            o.put("note", bestHit < 0.52 ? "信号胜率接近随机，结论偏中性" : "");
        } catch (Exception ignored) {
        }
        return o;
    }

    // ---------- signals ----------

    static double[] trendSignal(double[] c) {
        int n = c.length;
        double[] e5 = Indicators.ema(c, 5);
        double[] e20 = Indicators.ema(c, 20);
        double[] s = new double[n];
        for (int i = 0; i < n; i++) s[i] = e5[i] > e20[i] ? 1 : e5[i] < e20[i] ? -1 : 0;
        return s;
    }

    static double[] reversionSignal(double[] c) {
        int n = c.length;
        double[] r = Indicators.rsi(c, 14);
        double[] s = new double[n];
        for (int i = 0; i < n; i++) s[i] = r[i] > 70 ? -1 : r[i] < 30 ? 1 : 0;
        return s;
    }

    static double[] bollSignal(double[] c) {
        int n = c.length;
        double[] e20 = Indicators.ema(c, 20);
        double[] dev = new double[n];
        for (int i = 0; i < n; i++) dev[i] = c[i] - e20[i];
        double[] sd = Indicators.rollingStd(dev, 20);
        double[] s = new double[n];
        for (int i = 0; i < n; i++) {
            double z = sd[i] > 1e-9 ? dev[i] / sd[i] : 0;
            s[i] = z > 1.5 ? -1 : z < -1.5 ? 1 : 0;
        }
        return s;
    }

    static double[] momentumSignal(double[] c, int lb) {
        int n = c.length;
        double[] s = new double[n];
        for (int i = 0; i < n; i++) {
            int j = i - lb;
            s[i] = j >= 0 ? (c[i] > c[j] ? 1 : c[i] < c[j] ? -1 : 0) : 0;
        }
        return s;
    }

    /** 尾部 45% 段的方向命中率；样本不足返回 0.5。 */
    static double walkForward(double[] c, double[] sig, int step) {
        return walkForward(c, sig, step, 8);
    }

    static double walkForward(double[] c, double[] sig, int step, int minTotal) {
        int n = c.length;
        int from = (int) Math.floor(n * 0.55);
        int hit = 0, total = 0;
        for (int i = from; i < n - step; i++) {
            if (sig[i] == 0) continue;
            double fwd = c[i + step] - c[i];
            if (fwd == 0) continue;
            total++;
            if ((fwd > 0 && sig[i] > 0) || (fwd < 0 && sig[i] < 0)) hit++;
        }
        return total < Math.max(1, minTotal) ? 0.5 : (double) hit / total;
    }

    static double phi(double z) {
        // 标准正态 CDF（Abramowitz-Stegun 近似）
        double t = 1.0 / (1.0 + 0.2316419 * Math.abs(z));
        double d = 0.3989423 * Math.exp(-z * z / 2);
        double p = d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478
                + t * (-1.821256 + t * 1.330274))));
        return z > 0 ? 1 - p : p;
    }

    static String recommend(String overall, JSONObject ind, JSONArray horizons) {
        List<String> parts = new ArrayList<>();
        if ("看涨".equals(overall)) {
            parts.add("短线偏多：回踩短均线不破可轻仓跟随，跌破 EMA20 止损");
        } else if ("看跌".equals(overall)) {
            parts.add("短线偏空：反弹至短均线附近承压可轻仓试空，站上 EMA20 止损");
        } else {
            parts.add("方向不明：以区间思路对待，等突破区间边界再跟随");
        }
        double rsi = ind.optDouble("rsi", 50);
        if (rsi >= 70) parts.add("RSI " + rsi + " 进入超买区，追多需谨慎");
        else if (rsi <= 30) parts.add("RSI " + rsi + " 进入超卖区，追空需谨慎");
        double atrPct = ind.optDouble("atr_pct", 0);
        if (atrPct > 1.2) parts.add("波动率偏高（ATR " + atrPct + "%），建议降低仓位");
        double bollPos = ind.optDouble("boll_pos", 0.5);
        if (bollPos > 0.95) parts.add("价格贴近布林上轨，短线有回落压力");
        else if (bollPos < 0.05) parts.add("价格贴近布林下轨，短线有反抽需求");

        int strong = 0;
        String firstDir = null;
        boolean conflict = false;
        for (int i = 0; i < horizons.length(); i++) {
            JSONObject h = horizons.optJSONObject(i);
            if (h == null) continue;
            if (h.optDouble("confidence", 0) >= 0.2) {
                strong++;
                String d = h.optString("direction");
                if (firstDir == null) firstDir = d;
                else if (!firstDir.equals(d)) conflict = true;
            }
        }
        if (strong >= 2 && conflict) parts.add("多周期方向不一致，优先降低杠杆");
        return String.join("；", parts);
    }

    /** 预测到期时间：按周期分钟数。 */
    public static long dueAt(String key) {
        int minutes = 1;
        for (String[] h : HORIZONS) if (h[0].equals(key)) minutes = Integer.parseInt(h[2]);
        return System.currentTimeMillis() + minutes * 60_000L;
    }

    static {
        SinaClient.UA.isEmpty();
    }
}
