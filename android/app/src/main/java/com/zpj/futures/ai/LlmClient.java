package com.zpj.futures.ai;

import com.zpj.futures.Cfg;
import com.zpj.futures.data.SinaClient;
import com.zpj.futures.db.Bar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 大模型预测客户端：OpenAI 兼容协议，默认 DeepSeek。
 * 只在手机端直连，Key 仅保存在本机（AndroidKeyStore 加密）。
 *
 * <p>输出为「多周期趋势矩阵」：1m/5m/15m/30m/4h/8h/1d 各给方向、置信度、目标区间与依据，
 * 外加一条综合操作建议。周期档位与 {@code Forecaster.HORIZONS} 一一对应。
 */
public class LlmClient {

    public static class Result {
        public boolean ok;
        public String text;      // 原始回复（失败时为错误信息）
        public JSONObject data;  // 结构化结果（可能为空）
    }

    /** 与量化模型一致的七个档位。 */
    public static final String[] KEYS = {"1m", "5m", "15m", "30m", "4h", "8h", "1d"};
    public static final String[] LABELS = {"1分钟", "5分钟", "15分钟", "30分钟", "4小时", "8小时", "1天"};

    private final Cfg cfg;

    public LlmClient(Cfg cfg) {
        this.cfg = cfg;
    }

    public boolean configured() {
        return !cfg.llmKey().isEmpty();
    }

    /** 组装提示词：结构化指标 + 多周期统计 + 采样点，控制 token。 */
    static String buildPrompt(String name, double price, JSONObject forecast,
                              Map<String, List<Bar>> bars, List<double[]> ticks, String unit) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是期货量化分析师。请基于下面的客观数据，判断【")
                .append(name).append("】在 7 个周期上的趋势方向，并给出目标区间。\n\n");
        sb.append("现价：").append(fmt(price)).append(" ").append(unit).append("\n");

        JSONArray hs = forecast.optJSONArray("horizons");
        if (hs != null && hs.length() > 0) {
            sb.append("\n系统量化模型（信号融合+走前验证）的结论：\n");
            for (int i = 0; i < hs.length(); i++) {
                JSONObject h = hs.optJSONObject(i);
                if (h == null) continue;
                sb.append("- ").append(h.optString("label"))
                        .append("：").append(h.optString("direction"))
                        .append("，上涨概率 ").append(fmt(h.optDouble("p_up", 0.5)))
                        .append("，预期幅度 ").append(fmt(h.optDouble("exp_move_pct", 0)))
                        .append("%，目标 ").append(fmt(h.optDouble("target_price", 0)))
                        .append("\n");
            }
            sb.append("综合评分：").append(fmt(forecast.optDouble("score", 0)))
                    .append("（").append(forecast.optString("overall")).append("）\n");
        }

        JSONObject ind = forecast.optJSONObject("indicators");
        if (ind != null && ind.length() > 0) {
            sb.append("\n技术指标（1分钟序列）：RSI=").append(fmt(ind.optDouble("rsi", 50)))
                    .append("，MACD柱=").append(fmt(ind.optDouble("macd_hist", 0)))
                    .append("，ATR占比=").append(fmt(ind.optDouble("atr_pct", 0))).append("%")
                    .append("，布林位置=").append(fmt(ind.optDouble("boll_pos", 0.5)))
                    .append("，均线排列=").append(ind.optString("bias"))
                    .append("，20期波动率=").append(fmt(ind.optDouble("vol20_pct", 0))).append("%")
                    .append("，区间位置=").append(fmt(ind.optDouble("range_pos", 0.5))).append("\n");
        }

        sb.append("\n各周期近期统计（区间涨跌 / 最高 / 最低 / 平均振幅 / 均线关系）：\n");
        appendStats(sb, bars, "1m", "1分钟");
        appendStats(sb, bars, "5m", "5分钟");
        appendStats(sb, bars, "15m", "15分钟");
        appendStats(sb, bars, "30m", "30分钟");
        appendStats(sb, bars, "60m", "60分钟");
        appendStats(sb, bars, "1d", "日线");

        if (ticks != null && ticks.size() > 4) {
            sb.append("\n近期采集点（时间:价格，共 ").append(ticks.size()).append(" 点）：\n");
            SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.US);
            for (double[] p : ticks) {
                sb.append(f.format(new Date((long) p[0]))).append(':')
                        .append(fmt(p[1])).append(' ');
            }
            sb.append("\n");
        }

        sb.append("\n请只输出一个 JSON 对象，不要 markdown 代码块，不要多余文字：\n")
                .append("{\"overall\":\"看涨|看跌|震荡\",\"confidence\":0到1的小数,")
                .append("\"action\":\"一句话综合操作建议\",")
                .append("\"reasons\":[\"依据1\",\"依据2\"],\"risks\":[\"风险1\"],")
                .append("\"horizons\":[")
                .append("{\"key\":\"1m\",\"direction\":\"看涨|看跌|震荡\",\"confidence\":0到1,")
                .append("\"target_low\":数字,\"target_high\":数字,\"note\":\"一句话依据\"},")
                .append("…依次给出 5m、15m、30m、4h、8h、1d 共 7 个对象")
                .append("]}\n")
                .append("规则：\n")
                .append("1) horizons 必须包含全部 7 个 key，顺序固定 1m/5m/15m/30m/4h/8h/1d；\n")
                .append("2) target_low ≤ target_high，且区间要覆盖现价所在的大致范围（不要给出与现价无关的数字）；\n")
                .append("3) 方向必须与给定数据一致，证据不足时给「震荡」，不要强行给方向；\n")
                .append("4) 短线与长线可以相反，但要在 note 里说明；\n")
                .append("5) 不要给出确定性承诺，不构成投资建议。");
        return sb.toString();
    }

    private static void appendStats(StringBuilder sb, Map<String, List<Bar>> bars,
                                    String key, String label) {
        if (bars == null) return;
        List<Bar> list = bars.get(key);
        if (list == null || list.size() < 5) return;
        int n = list.size();
        int from = Math.max(0, n - 120);
        double first = list.get(from).c, last = list.get(n - 1).c;
        double hi = -Double.MAX_VALUE, lo = Double.MAX_VALUE, amp = 0;
        for (int i = from; i < n; i++) {
            Bar b = list.get(i);
            hi = Math.max(hi, b.h);
            lo = Math.min(lo, b.l);
            amp += (b.h - b.l);
        }
        amp = amp / (n - from) / Math.max(last, 1e-9) * 100;
        double e5 = emaLast(list, 5), e20 = emaLast(list, 20);
        String ma = e5 > e20 * 1.0005 ? "多头" : e5 < e20 * 0.9995 ? "空头" : "纠缠";
        sb.append("- ").append(label).append("（").append(n - from).append(" 根）：涨跌 ")
                .append(fmt((last - first) / Math.max(first, 1e-9) * 100)).append("%")
                .append("，最高 ").append(fmt(hi))
                .append("，最低 ").append(fmt(lo))
                .append("，平均振幅 ").append(fmt(amp)).append("%")
                .append("，EMA5/20 ").append(ma).append("\n");
    }

    private static double emaLast(List<Bar> list, int span) {
        double k = 2.0 / (span + 1), v = list.get(0).c;
        for (int i = 1; i < list.size(); i++) v = list.get(i).c * k + v * (1 - k);
        return v;
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    public Result analyze(String name, double price, JSONObject forecast,
                          Map<String, List<Bar>> bars, List<double[]> ticks, String unit) {
        Result r = new Result();
        String key = cfg.llmKey();
        if (key.isEmpty()) {
            r.text = "未配置大模型 API Key，请到「设置」填写";
            return r;
        }
        try {
            String base = cfg.llmBase();
            while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            String url = base + "/chat/completions";

            JSONArray messages = new JSONArray();
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", "你是严谨的期货量化分析师，只依据给定数据作答，"
                    + "承认不确定性，不夸大预测能力。输出必须是合法 JSON。");
            messages.put(sys);
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", buildPrompt(name, price, forecast, bars, ticks, unit));
            messages.put(user);

            JSONObject body = new JSONObject();
            body.put("model", cfg.llmModel());
            body.put("messages", messages);
            body.put("temperature", 0.3);
            body.put("max_tokens", 1600);

            String resp = post(url, key, body.toString());
            JSONObject obj = new JSONObject(resp);
            JSONArray choices = obj.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                r.text = "模型返回为空：" + trim(resp, 200);
                return r;
            }
            String content = choices.optJSONObject(0)
                    .optJSONObject("message").optString("content", "").trim();
            r.text = content;
            r.data = extractJson(content);
            if (r.data != null) normalize(r.data, price);
            r.ok = true;
            return r;
        } catch (Exception e) {
            r.text = "请求失败：" + e.getMessage();
            return r;
        }
    }

    /** 容错：补齐缺失档位、修正目标区间，保证前端能画出完整矩阵。 */
    static void normalize(JSONObject d, double price) {
        try {
            if (!d.has("overall") && d.has("direction")) {
                d.put("overall", d.optString("direction", "震荡"));
            }
            JSONArray hs = d.optJSONArray("horizons");
            if (hs == null) {
                // 模型只给了单周期结论时，退化成一条 1m，其余标缺省
                hs = new JSONArray();
                JSONObject one = new JSONObject();
                one.put("key", "1m");
                one.put("direction", d.optString("overall", d.optString("direction", "震荡")));
                one.put("confidence", d.optDouble("confidence", 0.5));
                one.put("target_low", d.optDouble("target_low", price));
                one.put("target_high", d.optDouble("target_high", price));
                one.put("note", "模型只给出单一周期结论");
                hs.put(one);
            }
            JSONObject byKey = new JSONObject();
            for (int i = 0; i < hs.length(); i++) {
                JSONObject h = hs.optJSONObject(i);
                if (h == null) continue;
                String k = h.optString("key", "");
                if (k.isEmpty()) continue;
                double lo = h.optDouble("target_low", price);
                double hi = h.optDouble("target_high", price);
                if (lo > hi) {
                    h.put("target_low", hi);
                    h.put("target_high", lo);
                }
                byKey.put(k, h);
            }
            JSONArray full = new JSONArray();
            for (int i = 0; i < KEYS.length; i++) {
                JSONObject h = byKey.optJSONObject(KEYS[i]);
                if (h == null) {
                    h = new JSONObject();
                    h.put("key", KEYS[i]);
                    h.put("direction", "—");
                    h.put("confidence", 0);
                    h.put("target_low", price);
                    h.put("target_high", price);
                    h.put("note", "模型未给出该周期");
                }
                h.put("label", LABELS[i]);
                full.put(h);
            }
            d.put("horizons", full);
        } catch (Exception ignored) {
        }
    }

    private static String post(String url, String key, String payload) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("User-Agent", SinaClient.UA);
        try (OutputStream os = c.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = readAll(in);
        c.disconnect();
        if (code >= 400) throw new IllegalStateException("HTTP " + code + " " + trim(text, 300));
        return text;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 从可能带 ```json 包裹的回复里取出 JSON 对象。 */
    static JSONObject extractJson(String s) {
        if (s == null) return null;
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            return new JSONObject(s.substring(a, b + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String trim(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
