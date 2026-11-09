package com.zpj.futures;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
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

    private static final int BG_DARK = 0xFF05070C;
    private static final int BG_LIGHT = 0xFFF2F5F9;

    private WebView webView;
    private Engine engine;
    private Bridge bridge;
    private Cfg cfg;
    private FrameLayout root;
    private long lastBackPress = 0L;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = new Cfg(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(BG_DARK);

        webView = new WebView(this);
        webView.setBackgroundColor(BG_DARK);
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
        // 首次安装或预置库版本更新时，把 APK 内置的分钟线 / 日线导入本地库
        try {
            com.zpj.futures.db.SeedImporter.importIfNeeded(
                    this, cfg, com.zpj.futures.db.Store.get(this));
        } catch (Exception ignored) {
        }
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
        applyThemeUi();
        engine.start();
    }

    /** 按当前主题设置原生底色、状态栏与导航栏，避免切换时闪黑。 */
    public void applyThemeUi() {
        boolean light = "light".equals(cfg.theme());
        int bg = light ? BG_LIGHT : BG_DARK;
        if (root != null) root.setBackgroundColor(bg);
        if (webView != null) webView.setBackgroundColor(bg);
        try {
            getWindow().setStatusBarColor(bg);
            getWindow().setNavigationBarColor(bg);
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (light) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            decor.setSystemUiVisibility(flags);
        } catch (Exception ignored) {
        }
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
