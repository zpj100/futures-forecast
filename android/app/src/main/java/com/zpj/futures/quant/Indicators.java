package com.zpj.futures.quant;

import com.zpj.futures.db.Bar;
import com.zpj.futures.data.SinaClient;

import org.json.JSONObject;

import java.util.List;

/** 技术指标（移植自 backend/app/model/features.py，逻辑保持一致）。 */
public class Indicators {

    public static double[] closes(List<Bar> bars) {
        double[] a = new double[bars.size()];
        for (int i = 0; i < a.length; i++) a[i] = bars.get(i).c;
        return a;
    }

    public static double emaLast(double[] x, int span) {
        if (x.length == 0) return 0;
        double alpha = 2.0 / (span + 1.0);
        double v = x[0];
        for (int i = 1; i < x.length; i++) v = alpha * x[i] + (1 - alpha) * v;
        return v;
    }

    public static double[] ema(double[] x, int span) {
        double[] out = new double[x.length];
        if (x.length == 0) return out;
        double alpha = 2.0 / (span + 1.0);
        out[0] = x[0];
        for (int i = 1; i < x.length; i++) out[i] = alpha * x[i] + (1 - alpha) * out[i - 1];
        return out;
    }

    public static double[] rsi(double[] x, int period) {
        int n = x.length;
        double[] out = new double[n];
        if (n < 2) {
            java.util.Arrays.fill(out, 50.0);
            return out;
        }
        double ag = 0, al = 0;
        double a = 1.0 / period;
        for (int i = 0; i < n; i++) {
            double diff = i == 0 ? 0 : x[i] - x[i - 1];
            double gain = diff > 0 ? diff : 0;
            double loss = diff < 0 ? -diff : 0;
            if (i == 0) {
                ag = gain;
                al = loss;
            } else {
                ag = a * gain + (1 - a) * ag;
                al = a * loss + (1 - a) * al;
            }
            double rs = al > 1e-12 ? ag / al : 1e9;
            out[i] = 100.0 - 100.0 / (1.0 + rs);
        }
        return out;
    }

    public static double[] atr(double[] h, double[] l, double[] c, int period) {
        int n = c.length;
        double[] tr = new double[n];
        for (int i = 0; i < n; i++) {
            double prev = i == 0 ? c[0] : c[i - 1];
            tr[i] = Math.max(h[i] - l[i],
                    Math.max(Math.abs(h[i] - prev), Math.abs(l[i] - prev)));
        }
        return ema(tr, period);
    }

    public static double[] rollingStd(double[] x, int period) {
        int n = x.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            int lo = Math.max(0, i - period + 1);
            int cnt = i - lo + 1;
            double mean = 0;
            for (int j = lo; j <= i; j++) mean += x[j];
            mean /= cnt;
            double var = 0;
            for (int j = lo; j <= i; j++) {
                double d = x[j] - mean;
                var += d * d;
            }
            out[i] = Math.sqrt(var / cnt);
        }
        return out;
    }

    public static JSONObject summary(List<Bar> bars) {
        JSONObject o = new JSONObject();
        if (bars == null || bars.size() < 30) return o;
        int n = bars.size();
        double[] c = new double[n], h = new double[n], l = new double[n], v = new double[n];
        for (int i = 0; i < n; i++) {
            Bar b = bars.get(i);
            c[i] = b.c;
            h[i] = b.h > 0 ? b.h : b.c;
            l[i] = b.l > 0 ? b.l : b.c;
            v[i] = b.v;
        }
        double e5 = emaLast(c, 5), e10 = emaLast(c, 10);
        double e20 = emaLast(c, 20), e60 = emaLast(c, 60);
        double[] macdArr = new double[n];
        double[] f12 = ema(c, 12), f26 = ema(c, 26);
        for (int i = 0; i < n; i++) macdArr[i] = f12[i] - f26[i];
        double sig = emaLast(macdArr, 9);
        double r14 = rsi(c, 14)[n - 1];
        double a14 = atr(h, l, c, 14)[n - 1];
        double px = c[n - 1];
        // 布林：用 EMA20 与偏差标准差（一次算好，避免 O(n^2)）
        double[] ema20Arr = ema(c, 20);
        double[] dev20 = new double[n];
        for (int i = 0; i < n; i++) dev20[i] = c[i] - ema20Arr[i];
        double sd20 = rollingStd(dev20, 20)[n - 1];
        double upper = e20 + 2 * sd20;
        double lower = e20 - 2 * sd20;
        double lo120 = l[Math.max(0, n - 120)];
        double hi120 = h[Math.max(0, n - 120)];
        for (int i = Math.max(0, n - 120); i < n; i++) {
            lo120 = Math.min(lo120, l[i]);
            hi120 = Math.max(hi120, h[i]);
        }
        double volStd = rollingStd(diffLog(c), 20)[n - 1];
        double vMean = 0;
        int vc = 0;
        for (int i = Math.max(0, n - 21); i < n - 1; i++) {
            vMean += v[i];
            vc++;
        }
        vMean = vc > 0 ? vMean / vc : v[n - 1];

        try {
            o.put("price", round(px, 4));
            o.put("ema5", round(e5, 2));
            o.put("ema10", round(e10, 2));
            o.put("ema20", round(e20, 2));
            o.put("ema60", round(e60, 2));
            o.put("rsi", round(r14, 1));
            o.put("macd", round(macdArr[n - 1], 4));
            o.put("macd_signal", round(sig, 4));
            o.put("macd_hist", round(macdArr[n - 1] - sig, 4));
            o.put("atr", round(a14, 4));
            o.put("atr_pct", round(px != 0 ? a14 / px * 100 : 0, 3));
            o.put("boll_upper", round(upper, 3));
            o.put("boll_lower", round(lower, 3));
            o.put("boll_pos", round(upper - lower > 1e-9 ? (px - lower) / (upper - lower) : 0.5, 3));
            o.put("vol20_pct", round(volStd * 100, 3));
            o.put("range_low", round(lo120, 3));
            o.put("range_high", round(hi120, 3));
            o.put("range_pos", round(hi120 - lo120 > 1e-9 ? (px - lo120) / (hi120 - lo120) : 0.5, 3));
            o.put("vol_change", round(vMean > 1e-9 ? v[n - 1] / vMean : 1.0, 2));
            o.put("bias", (e5 > e20 && e20 > e60) ? "偏多"
                    : (e5 < e20 && e20 < e60) ? "偏空" : "震荡");
        } catch (Exception ignored) {
        }
        return o;
    }

    static double[] diffLog(double[] c) {
        double[] out = new double[c.length];
        for (int i = 0; i < c.length; i++) {
            double prev = i == 0 ? c[0] : c[i - 1];
            out[i] = Math.log(Math.max(c[i], 1e-9)) - Math.log(Math.max(prev, 1e-9));
        }
        return out;
    }

    public static double round(double v, int nd) {
        double p = Math.pow(10, nd);
        return Math.round(v * p) / p;
    }
}
