package com.example.myapp;

import android.annotation.SuppressLint;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;

import androidx.appcompat.app.AlertDialog;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 承载网页内核（app/src/main/assets/index.html）的原生外壳。
 *
 * 页面本身包含完整的品牌区、日历、统计与设置界面，原生层只负责：
 * 1. 以 file:///android_asset/ 加载本地页面（无需网络权限）；
 * 2. 把排班设置同步给桌面小部件（SharedPreferences "WidgetSettings"）；
 * 3. 处理返回键（先关弹窗，再考虑页面后退）与外链跳转。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String ASSET_BASE = "file:///android_asset/";
    private static final String HOME_URL = ASSET_BASE + "index.html";

    private WebView webView;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);   // 页面使用 localStorage 保存配置
        webSettings.setAllowFileAccess(true);     // API 30+ 默认关闭，读取 assets 必需
        webSettings.setAllowContentAccess(false);
        webSettings.setAllowFileAccessFromFileURLs(false);
        webSettings.setAllowUniversalAccessFromFileURLs(false);
        webSettings.setSupportZoom(false);
        webSettings.setBuiltInZoomControls(false);
        // 页面用 meta viewport 自适应，禁止系统再叠加缩放。
        webSettings.setTextZoom(100);

        webView.addJavascriptInterface(new SettingsInterface(this), "Android");
        webView.setWebViewClient(new LocalAssetClient());
        // Context#getColor 需要 API 23，项目 minSdk 为 21，改用兼容方法。
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.bg));

        webView.loadUrl(HOME_URL);
        registerBackHandling();
    }

    /**
     * 返回键优先级：先关闭页面内弹窗，再尝试网页历史，最后退出应用。
     * 弹窗状态由页面自身维护，这里通过可见的 .modal 元素判断，避免修改网页资源。
     */
    private void registerBackHandling() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                webView.evaluateJavascript(
                        "(function(){var m=document.querySelector('.modal:not([hidden])');"
                                + "return m ? (m.id === 'settingsModal' ? 'settings' : 'picker') : '';})()",
                        value -> {
                            String dialog = value == null ? "" : value.replace("\"", "").trim();
                            if ("settings".equals(dialog)) {
                                webView.evaluateJavascript("closeSettings()", null);
                            } else if ("picker".equals(dialog)) {
                                webView.evaluateJavascript("closePicker()", null);
                            } else if (webView.canGoBack()) {
                                webView.goBack();
                            } else {
                                finish();
                            }
                        });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        MyWidgetProvider.refreshAll(this);
        if (Build.VERSION.SDK_INT >= 31 && WidgetRefresh.hasWidgets(this)
                && !WidgetRefresh.canScheduleExact(this)) {
            SharedPreferences preferences = getSharedPreferences("WidgetSettings", MODE_PRIVATE);
            if (!preferences.getBoolean("alarmPermissionExplained", false)) {
                preferences.edit().putBoolean("alarmPermissionExplained", true).apply();
                new AlertDialog.Builder(this)
                        .setTitle(R.string.widget_alarm_title)
                        .setMessage(R.string.widget_alarm_message)
                        .setNegativeButton(R.string.widget_alarm_later, null)
                        .setPositiveButton(R.string.widget_alarm_allow, (dialog, which) -> {
                            try {
                                startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        Uri.parse("package:" + getPackageName())));
                            } catch (android.content.ActivityNotFoundException unavailable) {
                                Log.w(TAG, "Exact alarm settings unavailable", unavailable);
                            }
                        }).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("Android");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    /**
     * 只允许加载本地 assets；外部链接交给系统浏览器，避免在 WebView 内打开网页。
     */
    private class LocalAssetClient extends WebViewClient {
        @Override
        public void onPageFinished(WebView view, String url) {
            if (!HOME_URL.equals(url)) return;
            // Reconcile existing WebView storage on upgrade, not only after the next manual save.
            view.evaluateJavascript("(function(){try {"
                    + "if(typeof CYCLE!=='undefined' && typeof SHIFT_DEFS!=='undefined'"
                    + " && typeof formatDateISO==='function')"
                    + "Android.saveSettings(JSON.stringify(CYCLE),formatDateISO(ANCHOR_DATE),"
                    + "JSON.stringify(SHIFT_DEFS));}catch(e){}})()", null);
        }

        /** API 24+ 的入口。 */
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleNavigation(request.getUrl());
        }

        /** API 21–23 的旧入口，保留以覆盖低版本设备。 */
        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleNavigation(Uri.parse(url));
        }

        private boolean handleNavigation(Uri uri) {
            String scheme = uri.getScheme() == null ? "" : uri.getScheme();
            String url = uri.toString();

            if ("file".equals(scheme)) {
                // 页面内的 <a href="./"> 会指向资产目录本身，不构成有效页面，直接忽略。
                boolean isAssetPage = url.startsWith(HOME_URL);
                if (!isAssetPage) {
                    Log.d(TAG, "Ignoring in-app navigation to " + url);
                }
                return !isAssetPage;
            }
            if ("http".equals(scheme) || "https".equals(scheme)) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception e) {
                    Log.w(TAG, "No activity to open " + url, e);
                }
                return true;
            }
            return true;
        }
    }

    /** 网页与桌面小部件之间的设置桥。键名与 MyWidgetProvider 保持一致。 */
    public class SettingsInterface {
        private final Context context;

        SettingsInterface(Context context) {
            this.context = context;
        }

        @JavascriptInterface
        public void saveSettings(String cycle, String anchorDate) {
            saveSettings(cycle, anchorDate, "");
        }

        @JavascriptInterface
        public void saveSettings(String cycle, String anchorDate, String shiftDefs) {
            Log.d(TAG, "Saving settings: cycle=" + cycle + ", anchorDate=" + anchorDate);
            SharedPreferences.Editor editor = preferences().edit();
            editor.putString("cycle", cycle);
            editor.putString("anchorDate", anchorDate);
            if (shiftDefs != null && !shiftDefs.isEmpty()) {
                editor.putString("shiftDefs", shiftDefs);
            }
            // apply 异步落盘（lint 建议），但内存值立即生效；
            // 小部件在主线程之后读取到的已是新值，无需同步阻塞主线程。
            editor.apply();
            refreshWidgets();
        }

        /** 设置变更后立即重绘小部件，并重置分钟对齐的刷新闹钟。 */
        private void refreshWidgets() {
            try {
                AppWidgetManager manager = AppWidgetManager.getInstance(context);
                int[] ids = manager.getAppWidgetIds(new ComponentName(context, MyWidgetProvider.class));
                if (ids != null && ids.length > 0) {
                    context.sendBroadcast(new Intent(context, MyWidgetProvider.class)
                            .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids));
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to refresh widgets", e);
            }
        }

        @JavascriptInterface
        public String getSettings() {
            String cycle = preferences().getString("cycle", "");
            String anchorDate = preferences().getString("anchorDate", "");
            String shiftDefs = preferences().getString("shiftDefs", "");
            Log.d(TAG, "Getting settings: cycle=" + cycle + ", anchorDate=" + anchorDate);
            try {
                JSONObject json = new JSONObject();
                json.put("cycle", cycle);
                json.put("anchorDate", anchorDate);
                json.put("shiftDefs", shiftDefs);
                return json.toString();
            } catch (JSONException e) {
                return "{\"cycle\":\"\",\"anchorDate\":\"\",\"shiftDefs\":\"\"}";
            }
        }

        private SharedPreferences preferences() {
            return context.getSharedPreferences("WidgetSettings", Context.MODE_PRIVATE);
        }
    }
}
