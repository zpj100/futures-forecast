package com.zpj.futures.data;

import com.zpj.futures.db.Bar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 新浪公开行情接口（无需 Key）。移植自后端 datasource/sina.py：
 * 保留 jsonp 剥离、GBK 解码、未来占位 bar 过滤三处关键处理。
 */
public class SinaClient {

    private static final String RT = "https://hq.sinajs.cn/list=%s";
    private static final String INNER_MIN = "https://stock2.finance.sina.com.cn/futures/"
            + "api/jsonp.php/var%20_=/InnerFuturesNewService.getFewMinLine?symbol=%s&type=%d";
    private static final String INNER_DAY = "https://stock2.finance.sina.com.cn/futures/"
            + "api/jsonp.php/var%20_=/InnerFuturesNewService.getDailyKLine?symbol=%s";
    private static final String GLOBAL_MIN = "https://stock2.finance.sina.com.cn/futures/"
            + "api/jsonp.php/var%20_=/GlobalFuturesService.getGlobalFuturesMinLine?symbol=%s&type=1";
    private static final String GLOBAL_DAY = "https://stock2.finance.sina.com.cn/futures/"
            + "api/jsonp.php/var%20_=/GlobalFuturesService.getGlobalFuturesDailyKLine?symbol=%s";

    public static final String UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    public static class Quote {
        public String code, name;
        public double last, open, high, low, prevClose, bid, ask, volume, oi;
        public long ts;
    }

    /**
     * 仅 HTTPS 的 GET（失败重试一次）。HTTPS 失败即返回错误，绝不降级到明文 HTTP：
     * 明文行情可被中间人投毒，且 manifest/网络安全配置已全局禁用 cleartext。
     */
    private static String http(String url, String charset) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return once(url, charset);
            } catch (Exception e) {
                last = e;
            }
            if (attempt == 0) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }
            }
        }
        throw last != null ? last : new IllegalStateException("HTTPS 行情请求失败");
    }

    private static String once(String url, String charset) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Referer", "https://finance.sina.com.cn/");
        c.setRequestProperty("Accept", "*/*");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return new String(bo.toByteArray(), Charset.forName(charset));
            }
        } finally {
            c.disconnect();
        }
    }

    /** 剥离 jsonp 外壳，取第一段 JSON（数组或对象）。 */
    static String jsonp(String text) {
        if (text == null) return "null";
        int a = text.indexOf('[');
        int o = text.indexOf('{');
        int start;
        char close;
        if (a < 0 && o < 0) return "null";
        if (a >= 0 && (o < 0 || a < o)) {
            start = a;
            close = ']';
        } else {
            start = o;
            close = '}';
        }
        int end = text.lastIndexOf(close);
        if (end <= start) return "null";
        return text.substring(start, end + 1);
    }

    private static double d(JSONObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && !o.isNull(k)) {
                String v = o.optString(k, "");
                try {
                    return Double.parseDouble(v);
                } catch (Exception ignored) {
                }
            }
        }
        return 0;
    }

    private static final ThreadLocal<SimpleDateFormat> FMT = ThreadLocal.withInitial(() -> {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return f;
    });

    static long parseTs(String s) {
        if (s == null) return 0;
        String v = s.trim();
        try {
            if (v.length() == 10) v = v + " 00:00:00";
            Date dt = FMT.get().parse(v);
            return dt == null ? 0 : dt.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 过滤新浪追加的「下一交易时段占位 bar」。 */
    static List<Bar> dropFuture(List<Bar> bars) {
        long limit = System.currentTimeMillis() + 2 * 3600_000L;
        List<Bar> out = new ArrayList<>(bars.size());
        for (Bar b : bars) {
            if (b.ts == 0 || b.ts <= limit) out.add(b);
        }
        return out;
    }

    // ---------------- realtime ----------------

    public Map<String, Quote> realtime() throws Exception {
        String syms = "nf_AL0,nf_AO0,hf_AHD";
        String text = http(String.format(RT, syms), "GBK");
        Map<String, Quote> out = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            line = line.trim();
            if (!line.startsWith("var hq_str_")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String sym = line.substring("var hq_str_".length(), eq).trim();
            String payload = line.substring(eq + 1).trim();
            payload = payload.replace(";", "").replace("\"", "").trim();
            if (payload.isEmpty()) continue;
            String[] p = payload.split(",", -1);
            Quote q = new Quote();
            q.ts = System.currentTimeMillis();
            if (sym.equals("nf_AL0")) {
                q.code = "AL";
                q.name = "沪铝";
            } else if (sym.equals("nf_AO0")) {
                q.code = "AO";
                q.name = "氧化铝";
            } else if (sym.equals("hf_AHD")) {
                q.code = "AHD";
                q.name = "伦铝";
            } else {
                continue;
            }
            try {
                if (sym.startsWith("nf_")) {
                    q.open = num(p, 2);
                    q.high = num(p, 3);
                    q.low = num(p, 4);
                    q.last = num(p, 6);
                    q.prevClose = num(p, 10);
                    q.bid = num(p, 8);
                    q.ask = num(p, 9);
                    q.volume = num(p, 13);
                    q.oi = num(p, 14);
                } else {
                    q.last = num(p, 0);
                    q.bid = num(p, 2);
                    q.ask = num(p, 3);
                    q.high = num(p, 4);
                    q.low = num(p, 5);
                    q.prevClose = num(p, 7);
                    q.open = num(p, 8);
                    q.volume = num(p, 13);
                    if (p.length > 12 && q.name == null) q.name = p[12];
                }
            } catch (Exception ignored) {
            }
            if (q.last > 0) out.put(q.code, q);
        }
        return out;
    }

    private static double num(String[] p, int i) {
        if (i < 0 || i >= p.length) return 0;
        try {
            return Double.parseDouble(p[i]);
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------------- kline ----------------

    public List<Bar> minute(String code) throws Exception {
        return minute(code, 1);
    }

    /** type：1 / 5 / 15 / 30 / 60 分钟。伦铝只有当日分时，type 会被忽略。 */
    public List<Bar> minute(String code, int type) throws Exception {
        List<Bar> bars = new ArrayList<>();
        if (code.equals("AHD")) {
            String raw = jsonp(http(String.format(GLOBAL_MIN, "AHD"), "UTF-8"));
            JSONObject obj = new JSONObject(raw);
            JSONArray keys = obj.names();
            if (keys == null) return bars;
            for (int i = 0; i < keys.length(); i++) {
                JSONArray rows = obj.optJSONArray(keys.getString(i));
                if (rows == null) continue;
                for (int j = 0; j < rows.length(); j++) {
                    JSONArray r = rows.optJSONArray(j);
                    if (r == null || r.length() < 6) continue;
                    long ts = parseTs(r.optString(r.length() - 1));
                    double px = r.optDouble(1, 0);
                    double vol = r.optDouble(2, 0);
                    if (px > 0) bars.add(new Bar(ts, px, px, px, px, vol));
                }
            }
            bars.sort((x, y) -> Long.compare(x.ts, y.ts));
            return dropFuture(bars);
        }
        String sym = code.equals("AL") ? "AL0" : "AO0";
        JSONArray arr = new JSONArray(jsonp(
                http(String.format(INNER_MIN, sym, type), "UTF-8")));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.optJSONObject(i);
            if (r == null) continue;
            long ts = parseTs(r.optString("d"));
            double c = d(r, "c");
            if (ts == 0 || c <= 0) continue;
            bars.add(new Bar(ts, d(r, "o"), d(r, "h"), d(r, "l"), c, d(r, "v")));
        }
        return dropFuture(bars);
    }

    /** 周期名 → type 参数（1m → 1，60m → 60）。 */
    public static int typeOf(String period) {
        if ("5m".equals(period)) return 5;
        if ("15m".equals(period)) return 15;
        if ("30m".equals(period)) return 30;
        if ("60m".equals(period)) return 60;
        return 1;
    }

    public List<Bar> daily(String code) throws Exception {
        List<Bar> bars = new ArrayList<>();
        if (code.equals("AHD")) {
            JSONArray arr = new JSONArray(jsonp(http(String.format(GLOBAL_DAY, "AHD"), "UTF-8")));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r == null) continue;
                long ts = parseTs(r.optString("date"));
                double c = d(r, "close");
                if (ts == 0 || c <= 0) continue;
                bars.add(new Bar(ts, d(r, "open"), d(r, "high"), d(r, "low"), c, d(r, "volume")));
            }
            return dropFuture(bars);
        }
        String sym = code.equals("AL") ? "AL0" : "AO0";
        JSONArray arr = new JSONArray(jsonp(http(String.format(INNER_DAY, sym), "UTF-8")));
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.optJSONObject(i);
            if (r == null) continue;
            long ts = parseTs(r.optString("d"));
            double c = d(r, "c");
            if (ts == 0 || c <= 0) continue;
            bars.add(new Bar(ts, d(r, "o"), d(r, "h"), d(r, "l"), c, d(r, "v")));
        }
        return dropFuture(bars);
    }

    static {
        // 保证默认字符集可用（部分 ROM 裁剪）
        StandardCharsets.UTF_8.name();
    }
}
