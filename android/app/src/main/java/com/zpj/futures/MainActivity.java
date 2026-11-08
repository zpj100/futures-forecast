package com.zpj.futures;

import android.annotation.SuppressLint;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "cfg";
    private static final String KEY_SERVER = "server";
    private static final String VERSION_URL =
            "https://raw.githubusercontent.com/zpj100/futures-forecast/main/release/version.json";

    private WebView webView;
    private SharedPreferences prefs;
    private long lastBackPress = 0L;
    private boolean versionChecked = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#06080D"));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#06080D"));
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView gear = new TextView(this);
        gear.setText("⚙");
        gear.setTextSize(20f);
        gear.setTextColor(Color.parseColor("#8A919F"));
        gear.setGravity(Gravity.CENTER);
        gear.setAlpha(0.55f);
        int pad = dp(10);
        gear.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(dp(44), dp(44));
        gp.gravity = Gravity.BOTTOM | Gravity.END;
        gp.setMargins(0, 0, dp(8), dp(16));
        root.addView(gear, gp);
        gear.setOnClickListener(v -> showServerDialog());

        setContentView(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(new NativeBridge(), "FuturesNative");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                String host = request.getUrl().getHost();
                String serverHost = Uri.parse(serverUrl()).getHost();
                if (host != null && host.equals(serverHost)) {
                    return false;
                }
                try {
                    startActivity(new android.content.Intent(
                            android.content.Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!versionChecked) {
                    versionChecked = true;
                    checkVersion();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    view.loadDataWithBaseURL(null, errorPage(), "text/html", "UTF-8", null);
                }
            }
        });

        if (serverUrl().isEmpty()) {
            showServerDialog();
        } else {
            webView.loadUrl(serverUrl());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null && webView.getUrl() != null
                && webView.getUrl().startsWith("data:")) {
            webView.loadUrl(serverUrl());
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private String serverUrl() {
        String u = prefs.getString(KEY_SERVER, "").trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private void showServerDialog() {
        if (webView != null) {
            webView.onPause();
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, pad, pad, 0);

        final EditText input = new EditText(this);
        input.setHint("http://192.168.1.10:8000");
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(serverUrl());
        box.addView(input);

        TextView hint = new TextView(this);
        hint.setText("填写运行 backend 的电脑地址，同一局域网内可访问即可。\n"
                + "例如 http://192.168.1.10:8000 或 https://your-domain.com");
        hint.setTextColor(Color.parseColor("#8A919F"));
        hint.setTextSize(12f);
        hint.setPadding(0, dp(10), 0, 0);
        box.addView(hint);

        new AlertDialog.Builder(this)
                .setTitle("服务器地址")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    String u = input.getText().toString().trim();
                    if (!u.startsWith("http://") && !u.startsWith("https://")) {
                        Toast.makeText(this, "地址需以 http:// 或 https:// 开头",
                                Toast.LENGTH_LONG).show();
                        showServerDialog();
                        return;
                    }
                    prefs.edit().putString(KEY_SERVER, u).apply();
                    webView.onResume();
                    webView.loadUrl(serverUrl());
                })
                .setNegativeButton("取消", (d, w) -> {
                    webView.onResume();
                    if (serverUrl().isEmpty()) {
                        finish();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void checkVersion() {
        new Thread(() -> {
            String body = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(VERSION_URL).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "FuturesForecastApp");
                if (c.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            sb.append(line);
                        }
                    }
                    body = sb.toString();
                }
                c.disconnect();
            } catch (Exception ignored) {
            }
            if (body == null) {
                return;
            }
            try {
                JSONObject o = new JSONObject(body);
                int remoteCode = o.optInt("versionCode", 0);
                String remoteName = o.optString("version", "");
                String url = o.optString("url", "");
                String notes = o.optString("notes", "");
                if (remoteCode > BuildConfig.VERSION_CODE && url.startsWith("http")) {
                    String finalNotes = notes;
                    String finalRemoteName = remoteName;
                    String finalUrl = url;
                    new Handler(Looper.getMainLooper()).post(() -> new AlertDialog.Builder(this)
                            .setTitle("发现新版本 " + finalRemoteName)
                            .setMessage(finalNotes.isEmpty() ? "建议更新到最新版本。" : finalNotes)
                            .setPositiveButton("前往更新",
                                    (d, w) -> openExternal(finalUrl))
                            .setNegativeButton("稍后", null)
                            .show());
                }
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void openExternal(String url) {
        try {
            startActivity(new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
    }

    private String errorPage() {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>body{margin:0;background:#06080D;color:#E6E8EC;"
                + "font-family:-apple-system,'Noto Sans CJK SC',sans-serif;"
                + "display:flex;flex-direction:column;align-items:center;"
                + "justify-content:center;height:100vh;padding:24px;box-sizing:border-box;text-align:center}"
                + "h2{font-size:18px;margin:0 0 10px}p{color:#8A919F;font-size:13px;"
                + "line-height:1.7;margin:0 0 22px;word-break:break-all}"
                + "button{background:#F6465D;color:#fff;border:0;padding:12px 26px;"
                + "border-radius:10px;font-size:15px;margin:6px}"
                + "button.ghost{background:#161B26;color:#E6E8EC}</style></head><body>"
                + "<h2>无法连接服务器</h2>"
                + "<p>当前地址：" + serverUrl() + "<br>请确认电脑已启动 "
                + "<code>bash run.sh</code>，且手机与电脑在同一网络。</p>"
                + "<button onclick=\"FuturesNative.openSettings()\">修改服务器地址</button>"
                + "<button class=\"ghost\" onclick=\"FuturesNative.retry()\">重试</button>"
                + "</body></html>";
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPress < 2000) {
            super.onBackPressed();
        } else {
            lastBackPress = now;
            Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();
        }
    }

    public class NativeBridge {
        @JavascriptInterface
        public void openSettings() {
            new Handler(Looper.getMainLooper()).post(MainActivity.this::showServerDialog);
        }

        @JavascriptInterface
        public void retry() {
            new Handler(Looper.getMainLooper()).post(() -> webView.loadUrl(serverUrl()));
        }

        @JavascriptInterface
        public String getServer() {
            return serverUrl();
        }

        @JavascriptInterface
        public int getVersionCode() {
            return BuildConfig.VERSION_CODE;
        }
    }
}
