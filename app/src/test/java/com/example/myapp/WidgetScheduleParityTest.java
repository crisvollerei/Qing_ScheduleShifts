package com.example.myapp;

import static org.junit.Assert.*;

import android.app.Activity;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** The same fixture is checked against the real web model by widget-parity.test.mjs. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-mdpi", application = android.app.Application.class)
public class WidgetScheduleParityTest {
    private String date(Calendar calendar) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(calendar.getTime());
    }

    @Test public void bridgeConfigAndRenderedScheduleMatchTheWebContract() throws Exception {
        JSONObject fixture;
        try (InputStream stream = getClass().getResourceAsStream("/widget-schedule-contract.json")) {
            assertNotNull(stream);
            fixture = new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        TimeZone original = TimeZone.getDefault();
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup();
                ActivityController<Activity> hostController = Robolectric.buildActivity(Activity.class).setup().visible()) {
            MainActivity activity = controller.get();
            MainActivity.SettingsInterface bridge = activity.new SettingsInterface(activity);
            JSONArray samples = fixture.getJSONArray("cases");
            for (int index = 0; index < samples.length(); index++) {
                JSONObject sample = samples.getJSONObject(index);
                TimeZone.setDefault(TimeZone.getTimeZone(sample.getString("timezone")));
                JSONObject config = sample.getJSONObject("config");
                JSONArray rawCycle = config.getJSONArray("cycle");
                bridge.saveSettings(rawCycle.toString(), config.getString("anchor"), config.getJSONObject("shiftDefs").toString());
                String[] cycle = MyWidgetProvider.readCycle(activity);
                assertEquals(sample.getString("id"), rawCycle.length(), cycle.length);
                for (int i = 0; i < cycle.length; i++) assertEquals(rawCycle.getString(i), cycle[i]);
                Calendar anchor = MyWidgetProvider.parseAnchor(config.getString("anchor"));
                Map<String, MyWidgetProvider.ShiftDef> defs = MyWidgetProvider.readShiftDefs(activity);
                JSONObject normalized = sample.getJSONObject("normalized").getJSONObject("shiftDefs");
                Iterator<String> names = normalized.keys();
                while (names.hasNext()) {
                    String name = names.next();
                    JSONObject expected = normalized.getJSONObject(name);
                    MyWidgetProvider.ShiftDef actual = MyWidgetProvider.defOf(name, defs);
                    String tag = sample.getString("id") + " / " + name;
                    assertEquals(tag, expected.getString("emoji"), actual.emoji);
                    assertEquals(tag, expected.getString("cssClass"), actual.cssClass);
                    assertEquals(tag, expected.getBoolean("isWork"), actual.isWork);
                    assertEquals(tag, expected.getBoolean("crossDay"), actual.crossDay);
                    assertEquals(tag, expected.isNull("startTime") ? null : expected.getString("startTime"), actual.startTime);
                    assertEquals(tag, expected.isNull("endTime") ? null : expected.getString("endTime"), actual.endTime);
                }
                JSONArray checks = sample.getJSONArray("checks");
                for (int i = 0; i < checks.length(); i++) {
                    JSONObject check = checks.getJSONObject(i);
                    JSONObject expected = check.getJSONObject("expected");
                    String tag = sample.getString("id") + " / " + check.getString("now");
                    Calendar now = Calendar.getInstance();
                    now.setTimeInMillis(expected.getLong("instant"));
                    android.os.SystemClock.setCurrentTimeMillis(now.getTimeInMillis());
                    MyWidgetProvider.ShiftSlot slot = MyWidgetProvider.debugEffectiveSlot(now, anchor, cycle, defs);
                    assertEquals(tag, expected.getString("name"), slot.name);
                    assertEquals(tag, expected.getString("day"), date(slot.day));
                    assertEquals(tag, expected.getLong("start"), slot.def.isWork ? slot.start.getTimeInMillis() : 0);
                    assertEquals(tag, expected.getLong("end"), slot.def.isWork ? slot.end.getTimeInMillis() : 0);
                    assertEquals(tag, expected.getInt("cycleIndex"), MyWidgetProvider.cycleIndexOf(now, anchor, cycle));
                    MyWidgetProvider.Countdown countdown = MyWidgetProvider.countdownAt(now, slot, anchor, cycle, defs);
                    JSONObject wanted = expected.getJSONObject("countdown");
                    assertEquals(tag, wanted.getLong("target"), countdown.target);
                    assertEquals(tag, wanted.getString("name"), countdown.shiftName);
                    int label = wanted.getString("kind").equals("off") ? R.string.widget_countdown_to_off
                            : wanted.getString("kind").equals("work") ? R.string.widget_countdown_to_work : R.string.widget_status_rest;
                    assertEquals(tag, label, countdown.label);
                    assertTrue(tag, MyWidgetProvider.nextStateChange(now, slot, anchor, cycle, defs) > now.getTimeInMillis());
                    RemoteViews views = WidgetRenderer.build(activity, WidgetRenderer.Form.FULL,
                            304, 320, now, slot, anchor, cycle, defs, countdown, 28);
                    WidgetTestClock.alignCountdown(views, now, countdown);
                    FrameLayout host = new FrameLayout(hostController.get());
                    hostController.get().setContentView(host);
                    View card = views.apply(hostController.get(), host);
                    host.addView(card);
                    WidgetRenderFixture.measure(host, 304, 320);
                    assertEquals(tag, slot.name, ((TextView) card.findViewById(R.id.shift_name)).getText().toString());
                    JSONObject tomorrow = expected.getJSONObject("tomorrow");
                    TextView tomorrowText = card.findViewById(R.id.tomorrow_value);
                    assertTrue(tag, tomorrowText.getText().toString().contains(tomorrow.getString("name")));
                    assertEquals(tag, WidgetShiftAppearance.color(activity, tomorrow.getString("cssClass")), tomorrowText.getCurrentTextColor());
                    JSONArray week = expected.getJSONArray("week");
                    for (int day = 0; day < 7; day++) {
                        JSONObject future = week.getJSONObject(day);
                        TextView cell = card.findViewById(WidgetRenderer.WEEK_DAYS[day]);
                        int number = Integer.parseInt(future.getString("date").substring(8));
                        assertEquals(tag, number + "\n" + future.getString("emoji"), cell.getText().toString());
                        assertTrue(tag, cell.getContentDescription().toString().contains(future.getString("name")));
                        assertEquals(tag, WidgetShiftAppearance.color(activity, future.getString("cssClass")), cell.getCurrentTextColor());
                    }
                }
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
