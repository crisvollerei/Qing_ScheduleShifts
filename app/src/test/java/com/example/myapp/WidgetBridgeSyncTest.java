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
    @Test public void loadingExistingWebProfileAlsoPushesItsActiveScheduleToWidgets() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            WebView view = controller.get().findViewById(R.id.webView);
            view.getWebViewClient().onPageFinished(view, "file:///android_asset/index.html");
            String script = Shadows.shadowOf(view).getLastEvaluatedJavascript();
            assertTrue(script.contains("Android.saveSettings(JSON.stringify(CYCLE)"));
            assertTrue(script.contains("formatDateISO(ANCHOR_DATE)"));
            assertTrue(script.contains("JSON.stringify(SHIFT_DEFS)"));
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
