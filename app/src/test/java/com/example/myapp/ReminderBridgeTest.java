package com.example.myapp;

import static org.junit.Assert.*;
import android.Manifest;
import android.app.AlarmManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.provider.AlarmClock;
import android.webkit.WebView;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.util.Calendar;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ReminderBridgeTest {
    JSONObject request(MainActivity activity, String id, String action, JSONObject data) throws Exception {
        activity.new SettingsInterface(activity).reminderRequest(id, action, data.toString());
        WebView view = activity.findViewById(R.id.webView);
        for (int i = 0; i < 200; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            String script = Shadows.shadowOf(view).getLastEvaluatedJavascript();
            if (script != null && script.contains("receive(\"" + id + "\","))
                return new JSONObject(script.substring(script.indexOf(',') + 1, script.length() - 1));
            Thread.sleep(10);
        }
        throw new AssertionError("No native response: " + id);
    }
    @Test public void savedWebScheduleReschedulesAlarmsEvenWithoutAnyWidgetInstances() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        WorkReminders.prefs(context).edit().clear().commit();
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        Shadows.shadowOf((AlarmManager) context.getSystemService(Context.ALARM_SERVICE)).setCanScheduleExactAlarms(true);
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            JSONObject result = request(activity, "enable", "save", new JSONObject()
                    .put("enabled", true).put("lead", 60).put("sound", true).put("vibrate", true));
            assertTrue(result.getBoolean("ok"));
            activity.new SettingsInterface(activity).saveSettings("[\"外勤\"]", "2026-10-24",
                    "{\"外勤\":{\"isWork\":true,\"startTime\":\"09:00\",\"endTime\":\"18:00\"}}");
            JSONObject state = request(activity, "state", "state", new JSONObject()).getJSONObject("data");
            assertEquals("外勤", state.getJSONObject("scheduled").getString("name"));
            assertEquals("已安排", state.getString("status"));
            assertEquals(0, MyWidgetProvider.widgetIds(activity).length);
        }
    }
    @Test public void clockHandoffAlwaysShowsTheSystemUiAndOnlyClaimsAHandoff() throws Exception {
        Application context = RuntimeEnvironment.getApplication();
        WorkReminders.prefs(context).edit().clear().commit();
        context.getSharedPreferences("WidgetSettings", Context.MODE_PRIVATE).edit().clear().commit();
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            JSONObject preview = request(activity, "preview", "clockPreview", new JSONObject()).getJSONObject("data");
            JSONObject reply = request(activity, "clock", "clock", new JSONObject().put("token", preview.getString("token")));
            assertTrue(reply.getBoolean("ok"));
            Intent intent = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(intent); assertEquals(AlarmClock.ACTION_SET_ALARM, intent.getAction());
            assertFalse(intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, true));
            assertFalse(intent.hasExtra(AlarmClock.EXTRA_DAYS));
            Calendar at = Calendar.getInstance(); at.setTimeInMillis(preview.getJSONObject("entry").getLong("fire"));
            assertEquals(at.get(Calendar.HOUR_OF_DAY), intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1));
            assertEquals(at.get(Calendar.MINUTE), intent.getIntExtra(AlarmClock.EXTRA_MINUTES, -1));
        }
    }
    @Test public void invalidNativeSettingsReturnAnErrorRatherThanOptimisticSuccess() throws Exception {
        try (var controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            JSONObject reply = request(controller.get(), "invalid", "save", new JSONObject()
                    .put("enabled", true).put("lead", 1.5).put("sound", true).put("vibrate", true));
            assertFalse(reply.getBoolean("ok")); assertTrue(reply.getString("error").contains("整数"));
        }
    }
}
