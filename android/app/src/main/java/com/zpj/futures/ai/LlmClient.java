package com.zpj.futures.ai;

import com.zpj.futures.Cfg;
import com.zpj.futures.data.SinaClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 大模型预测客户端：OpenAI 兼容协议，默认 DeepSeek。
 * 只在手机端直连，Key 仅保存在本机（AndroidKeyStore 加密）。
 */
public class LlmClient {

    public static class Result {
        public boolean ok;
        public String text;      // 原始回复（失败时为错误信息）
        public JSONObject data;  // 结构化结果（可能为空）
    }

    private final Cfg cfg;

    public LlmClient(Cfg cfg) {
        this.cfg = cfg;
    }

    public boolean configured() {
        return !cfg.llmKey().isEmpty();
    }

    /** 组装提示词：只喂结构化指标与采样后的曲线，控制 token。 */
    static String buildPrompt(String name, double price, JSONObject forecast,
                              List<double[]> recent, String unit) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是期货量化分析师。请基于下面的客观数据，对【")
                .append(name).append("】未来 1 小时内的方向做出判断。\n\n");
        sb.append("现价：").append(price).append(" ").append(unit).append("\n");

        JSONArray hs = forecast.optJSONArray("horizons");
        if (hs != null) {
            sb.append("系统量化模型的分周期结论：\n");
            for (int i = 0; i < hs.length(); i++) {
                JSONObject h = hs.optJSONObject(i);
                if (h == null) continue;
                sb.append("- ").append(h.optString("label"))
                        .append("：").append(h.optString("direction"))
                        .append("，上涨概率 ").append(h.optDouble("p_up", 0.5))
                        .append("，预期幅度 ").append(h.optDouble("exp_move_pct", 0))
                        .append("%\n");
            }
            sb.append("综合评分：").append(forecast.optDouble("score", 0))
                    .append("（").append(forecast.optString("overall")).append("）\n");
        }

        JSONObject ind = forecast.optJSONObject("indicators");
        if (ind != null && ind.length() > 0) {
            sb.append("技术指标：RSI=").append(ind.optDouble("rsi", 50))
                    .append("，MACD柱=").append(ind.optDouble("macd_hist", 0))
                    .append("，ATR占比=").append(ind.optDouble("atr_pct", 0)).append("%")
                    .append("，布林位置=").append(ind.optDouble("boll_pos", 0.5))
                    .append("，均线排列=").append(ind.optString("bias"))
                    .append("，20期波动率=").append(ind.optDouble("vol20_pct", 0)).append("%")
                    .append("，区间位置=").append(ind.optDouble("range_pos", 0.5)).append("\n");
        }

        if (recent != null && recent.size() > 4) {
            sb.append("近期价格采样（时间/价格，共 ").append(recent.size()).append(" 点）：\n");
            for (double[] p : recent) {
                sb.append(String.format(java.util.Locale.US, "%.0f:%.2f ", p[0], p[1]));
            }
            sb.append("\n");
        }

        sb.append("\n只输出一个 JSON 对象，不要 markdown 代码块，字段如下：\n")
                .append("{\"direction\":\"看涨|看跌|震荡\",\"confidence\":0-1的小数,")
                .append("\"target_low\":数字,\"target_high\":数字,")
                .append("\"reasons\":[\"理由1\",\"理由2\"],\"risks\":[\"风险1\"],")
                .append("\"action\":\"一句话操作建议\"}\n")
                .append("要求：结论必须与数据一致；数据不足以支撑方向时给震荡；")
                .append("不要给出确定性承诺，不构成投资建议。");
        return sb.toString();
    }

    public Result analyze(String name, double price, JSONObject forecast,
                          List<double[]> recent, String unit) {
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
                    + "承认不确定性，不夸大预测能力。");
            messages.put(sys);
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", buildPrompt(name, price, forecast, recent, unit));
            messages.put(user);

            JSONObject body = new JSONObject();
            body.put("model", cfg.llmModel());
            body.put("messages", messages);
            body.put("temperature", 0.3);
            body.put("max_tokens", 800);

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
            r.ok = true;
            return r;
        } catch (Exception e) {
            r.text = "请求失败：" + e.getMessage();
            return r;
        }
    }

    private static String post(String url, String key, String payload) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(90000);
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
