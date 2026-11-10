package com.zpj.futures;

import android.annotation.SuppressLint;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
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
    private static final String ASSET_PREFIX = "file:///android_asset/";
    private static final String REFRESH_JS =
            "window.FF && window.FF.onData && window.FF.onData();";

    private WebView webView;
    private Engine engine;
    private Bridge bridge;
    private Cfg cfg;
    private FrameLayout root;
    private long lastBackPress = 0L;
    /** Activity 销毁后不再向已 destroy 的 WebView 触发刷新。 */
    private volatile boolean destroyed = false;

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
        // 必须保留：index.html / app.js / app.css 都从 file:///android_asset/ 加载
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        // WebView 加固：禁止 file:// 页面跨源读取、禁止通用跨源、禁止混合内容
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setWebChromeClient(new WebChromeClient());
        // 只放行 assets 内部导航，http(s)/外部 file 等一律拦截，避免 JS 桥暴露给外部页面
        webView.setWebViewClient(new AssetOnlyWebViewClient());

        // 引擎与桥提前构造（对象构造很轻，不做 SQLite/网络等耗时操作）：
        // 1) addJavascriptInterface 必须在 loadUrl 之前，否则页面拿不到 window.Native
        //    （app.js 以 window.Native 是否存在决定是否进入 DEMO 假数据模式）；
        // 2) 引擎对象存在但尚未 start() 时，Bridge.state() -> Engine.state() 返回安全空态，
        //    JS 在引擎就绪前调用 Native.state() 不会崩溃。
        engine = new Engine(this, cfg);
        bridge = new Bridge(this, cfg, engine);
        engine.setListener(this::triggerRefresh);   // 回调注册保持在 start() 之前
        bridge.attach(webView);
        webView.addJavascriptInterface(bridge, "Native");

        webView.loadUrl("file:///android_asset/index.html");
        applyThemeUi();

        // 1.5MB 预置库导入 + engine.start()（18 次缓存查询 + 首屏七档预测）移到后台线程，
        // 避免冷启动在主线程同步执行造成 ANR。
        new Thread(this::initInBackground, "ff-init").start();
    }

    /** 后台线程：导入预置库并启动引擎。engine.start() 内部顺序原样保留，不调整。 */
    private void initInBackground() {
        if (destroyed) return;
        try {
            com.zpj.futures.db.SeedImporter.importIfNeeded(
                    this, cfg, com.zpj.futures.db.Store.get(this));
        } catch (Exception ignored) {
        }
        if (destroyed) return;
        engine.start();
        // 完成后回主线程触发一次前端刷新，让页面用导入后的缓存/预测重绘
        runOnUiThread(this::triggerRefresh);
    }

    /** 触发一次前端刷新（Engine 已通过 main.post 保证在主线程回调）。 */
    private void triggerRefresh() {
        if (destroyed || webView == null) return;
        try {
            webView.evaluateJavascript(REFRESH_JS, null);
        } catch (Exception ignored) {
        }
    }

    /** 仅允许 file:///android_asset/ 内部导航；其余 URL 返回 true 拦截、不加载。 */
    private static final class AssetOnlyWebViewClient extends WebViewClient {
        private static boolean allowed(String url) {
            return url != null && url.startsWith(ASSET_PREFIX);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return !allowed(request.getUrl() == null ? null : request.getUrl().toString());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return !allowed(url);
        }
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
        destroyed = true;
        // close() 关闭桥线程池并释放 web 引用，必须在 webView.destroy() 之前
        if (bridge != null) bridge.close();
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
