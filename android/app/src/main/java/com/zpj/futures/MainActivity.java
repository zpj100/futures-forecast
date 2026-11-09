package com.zpj.futures;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.zpj.futures.data.Engine;

/**
 * 手机端主界面：加载打包在 assets 里的单页应用，
 * 数据由本机 Engine 采集并存入 SQLite，不依赖任何外部后端。
 */
public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private Engine engine;
    private Bridge bridge;
    private Cfg cfg;
    private long lastBackPress = 0L;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = new Cfg(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF05070C);

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF05070C);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());

        engine = new Engine(this, cfg);
        bridge = new Bridge(this, cfg, engine);
        bridge.attach(webView);
        engine.setListener(() -> {
            if (webView != null) {
                webView.evaluateJavascript(
                        "window.FF && window.FF.onData && window.FF.onData();", null);
            }
        });
        webView.addJavascriptInterface(bridge, "Native");

        webView.loadUrl("file:///android_asset/index.html");
        engine.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (engine != null) engine.refresh(false);
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) bridge.detach();
        if (engine != null) engine.stop();
        if (webView != null) {
            webView.removeJavascriptInterface("Native");
            webView.destroy();
        }
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
                return true;
            }
            long now = System.currentTimeMillis();
            if (now - lastBackPress < 2000) {
                finish();
            } else {
                lastBackPress = now;
                Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
