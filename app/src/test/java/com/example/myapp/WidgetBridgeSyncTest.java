package com.example.myapp;

import static org.junit.Assert.*;

import android.webkit.WebView;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WidgetBridgeSyncTest {

    /** 页面改为 ES module 后经 https 虚拟域加载，同步入口是小部件桥的 syncNow。 */
    @Test public void loadingExistingWebProfileAlsoPushesItsActiveScheduleToWidgets() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            WebView view = controller.get().findViewById(R.id.webView);
            // 只对首页触发，URL 必须与 MainActivity 使用的虚拟域一致。
            view.getWebViewClient().onPageFinished(
                    view, "https://appassets.androidplatform.net/index.html");
            String script = Shadows.shadowOf(view).getLastEvaluatedJavascript();
            assertNotNull("首页加载完成应注入同步脚本", script);
            assertTrue(script.contains("QingZhengAppBridge"));
            assertTrue(script.contains("syncNow"));
        }
    }

    /** 非首页的加载不应触发同步脚本。 */
    @Test public void otherPagesDoNotTriggerScheduleSync() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            WebView view = controller.get().findViewById(R.id.webView);
            view.getWebViewClient().onPageFinished(
                    view, "https://appassets.androidplatform.net/other.html");
            assertNull(Shadows.shadowOf(view).getLastEvaluatedJavascript());
        }
    }

    @Test public void nativeBridgeStoresAllThreeFieldsAtomicallyAndReadsThemBack() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            MainActivity.SettingsInterface bridge = activity.new SettingsInterface(activity);
            bridge.saveSettings("[\"外勤\",\"年假\"]", "2026-10-17", "{}");
            JSONObject result = new JSONObject(bridge.getSettings());
            assertEquals("[\"外勤\",\"年假\"]", result.getString("cycle"));
            assertEquals("2026-10-17", result.getString("anchorDate"));
            assertEquals("{}", result.getString("shiftDefs"));
        }
    }
}
