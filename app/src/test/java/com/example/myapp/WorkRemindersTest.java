package com.example.myapp;

import static org.junit.Assert.*;
import android.Manifest;
import android.app.AlarmManager;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.TimeZone;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34}, application = Application.class)
public class WorkRemindersTest {
    Context context;
    AlarmManager alarms;
    TimeZone originalZone;
    long now;
    static long at(String date) throws Exception {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").parse(date).getTime();
    }
    @Before public void prepare() throws Exception {
        originalZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Shadows.shadowOf(alarms).setCanScheduleExactAlarms(true);
        WorkReminders.prefs(context).edit().clear().commit();
        schedule("[\"白班\",\"夜班\",\"休息\"]");
        now = at("2026-10-24 21:00");
    }
    void schedule(String cycle) {
        context.getSharedPreferences("WidgetSettings", Context.MODE_PRIVATE).edit().clear()
                .putString("cycle", cycle).putString("anchorDate", "2026-10-24").commit();
    }
    JSONObject settings(boolean enabled, int lead) throws Exception {
        return new JSONObject().put("enabled", enabled).put("lead", lead).put("sound", true).put("vibrate", true);
    }
    List<ShadowAlarmManager.ScheduledAlarm> scheduled() { return Shadows.shadowOf(alarms).getScheduledAlarms(); }
    Intent fire(ShadowAlarmManager.ScheduledAlarm alarm) { return new Intent(Shadows.shadowOf(alarm.operation).getSavedIntent()); }
    @After public void restoreZone() { TimeZone.setDefault(originalZone); }

    @Test public void enableSchedulesEightExactAlarmsWithoutAnyWidgetsAndReconcileDoesNotDuplicate() throws Exception {
        WorkReminders.save(context, settings(true, 60), now);
        assertEquals(8, scheduled().size());
        assertEquals(at("2026-10-25 19:00"), scheduled().get(0).triggerAtTime);
        for (ShadowAlarmManager.ScheduledAlarm alarm : scheduled()) {
            assertNotNull(alarm.getAlarmClockInfo()); assertEquals(0, alarm.interval);
            assertEquals(WorkReminders.FIRE, fire(alarm).getAction());
        }
        WorkReminders.reconcile(context, now);
        assertEquals(8, scheduled().size());
        assertEquals("已安排", WorkReminders.state(context, now).getString("status"));
    }
    @Test public void disablingCancelsOwnedAndTestAlarmsButNotWidgetRefresh() throws Exception {
        WorkReminders.save(context, settings(true, 30), now);
        WorkReminders.test(context, now);
        WidgetRefresh.schedule(context, now + 120000);
        int before = scheduled().size();
        WorkReminders.save(context, settings(false, 30), now);
        assertEquals(before - 9, scheduled().size());
        assertFalse(scheduled().stream().anyMatch(alarm -> WorkReminders.FIRE.equals(fire(alarm).getAction())));
    }
    @Test public void widgetRemovalDoesNotCancelWorkAlarms() throws Exception {
        WorkReminders.save(context, settings(true, 60), now);
        WidgetRefresh.cancel(context);
        assertEquals(8, scheduled().size());
    }
    @Test public void deniedExactAccessNeverSilentlyFallsBackToInexactAlarms() throws Exception {
        if (Build.VERSION.SDK_INT < 31) return;
        Shadows.shadowOf(alarms).setCanScheduleExactAlarms(false);
        WorkReminders.save(context, settings(true, 60), now);
        assertTrue(scheduled().isEmpty());
        assertEquals("待授权", WorkReminders.state(context, now).getString("status"));
        Shadows.shadowOf(alarms).setCanScheduleExactAlarms(true);
        WorkReminders.reconcile(context, now);
        assertEquals(8, scheduled().size());
    }
    @Test public void deniedNotificationsAreVisibleAndPreventRegistration() throws Exception {
        if (Build.VERSION.SDK_INT < 33) return;
        Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        WorkReminders.save(context, settings(true, 60), now);
        assertTrue(scheduled().isEmpty());
        assertEquals("待授权", WorkReminders.state(context, now).getString("status"));
    }
    @Test public void staleAlarmCannotRingAfterChangingScheduleOrDisabling() throws Exception {
        WorkReminders.save(context, settings(true, 60), now);
        Intent old = fire(scheduled().get(0));
        schedule("[\"休息\",\"白班\"]");
        WorkReminders.reconcile(context, now);
        assertNull(WorkReminders.consume(context, old, old.getLongExtra("fire", 0)));
        Intent current = fire(scheduled().get(0));
        WorkReminders.save(context, settings(false, 60), now);
        assertNull(WorkReminders.consume(context, current, current.getLongExtra("fire", 0)));
    }
    @Test public void consumingAlarmSchedulesSuccessorsAndDuplicateDeliveryCannotRingTwice() throws Exception {
        WorkReminders.save(context, settings(true, 60), now);
        Intent alarm = fire(scheduled().get(0)); long time = alarm.getLongExtra("fire", 0);
        assertNotNull(WorkReminders.consume(context, alarm, time));
        assertNull(WorkReminders.consume(context, alarm, time));
        assertEquals(8, scheduled().size());
        assertTrue(scheduled().get(0).triggerAtTime > time);
    }
    @Test public void grosslyDelayedAlarmIsConsumedWithoutUnexpectedCatchupSound() throws Exception {
        WorkReminders.save(context, settings(true, 60), now);
        Intent alarm = fire(scheduled().get(0));
        assertNull(WorkReminders.consume(context, alarm, alarm.getLongExtra("fire", 0) + WorkReminders.MAX_LATENESS + 1));
        assertNull(WorkReminders.consume(context, alarm, alarm.getLongExtra("fire", 0)));
    }
    @Test public void changingAnAlreadyRemindedFutureShiftToAnEarlierTimeDoesNotSuppressTheNewNode() throws Exception {
        WorkReminders.save(context, settings(true, 1440), at("2026-10-24 19:00"));
        Intent old = fire(scheduled().get(0));
        assertNotNull(WorkReminders.consume(context, old, at("2026-10-24 20:00")));
        context.getSharedPreferences("WidgetSettings", Context.MODE_PRIVATE).edit()
                .putString("shiftDefs", "{\"夜班\":{\"isWork\":true,\"startTime\":\"19:00\",\"endTime\":\"08:00\",\"crossDay\":true}}").commit();
        WorkReminders.save(context, settings(true, 60), at("2026-10-24 20:05"));
        assertEquals(at("2026-10-25 18:00"), scheduled().get(0).triggerAtTime);
    }
    @Test public void rebootRestoresWorkAlarmsAndDropsTheOldTest() throws Exception {
        WorkReminders.save(context, settings(true, 60), System.currentTimeMillis());
        WorkReminders.test(context, System.currentTimeMillis());
        new WorkReminderReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertEquals(8, scheduled().size());
        assertFalse(WorkReminders.prefs(context).contains("testFire"));
    }
    @Test public void allRestCycleIsNotReportedAsScheduled() throws Exception {
        schedule("[\"休息\"]");
        WorkReminders.save(context, settings(true, 60), now);
        assertEquals("暂无可安排的工作班次", WorkReminders.state(context, now).getString("status"));
        assertTrue(scheduled().isEmpty());
    }
    @Test public void leadCanCrossMidnightAndMissedReminderIsSkipped() throws Exception {
        context.getSharedPreferences("WidgetSettings", Context.MODE_PRIVATE).edit().putString("cycle", "[\"早班\"]")
                .putString("shiftDefs", "{\"早班\":{\"startTime\":\"00:30\",\"endTime\":\"08:00\",\"isWork\":true}}").commit();
        List<ReminderSchedule.Entry> entries = ReminderSchedule.upcoming(context, at("2026-10-24 22:00"), 60, 1);
        assertEquals(at("2026-10-24 23:30"), entries.get(0).fire);
        assertEquals(at("2026-10-25 00:30"), entries.get(0).start);
        entries = ReminderSchedule.upcoming(context, at("2026-10-24 23:45"), 60, 1);
        assertEquals(at("2026-10-25 23:30"), entries.get(0).fire);
    }
    @Test public void calendarRangeIsBasedOnStartDatesAndNightShiftRetainsNextDayEnd() throws Exception {
        List<ReminderSchedule.Entry> entries = ReminderSchedule.range(context, now, 60, 1);
        assertEquals(1, entries.size());
        assertEquals(at("2026-10-25 20:00"), entries.get(0).start);
        assertEquals(at("2026-10-26 08:00"), entries.get(0).end);
        assertEquals(5, ReminderSchedule.range(context, now, 60, 7).size());
    }
    @Test public void invalidSettingsAreRejectedBeforeChangingOrCancellingExistingAlarms() throws Exception {
        WorkReminders.save(context, settings(true, 60), now);
        for (Object lead : new Object[]{-1, 1441, 1.5, "60"}) {
            try { WorkReminders.save(context, settings(true, 60).put("lead", lead), now); fail(); }
            catch (IllegalArgumentException expected) { assertEquals(60, WorkReminders.lead(context)); }
        }
        assertEquals(8, scheduled().size());
        try { ReminderSchedule.range(context, now, 60, 91); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("90")); }
    }
    @Test public void daylightSavingUsesCivilDatesButLeadIsElapsedMinutes() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        schedule("[\"白班\"]");
        List<ReminderSchedule.Entry> entries = ReminderSchedule.range(context, at("2026-03-07 12:00"), 60, 2);
        assertEquals(at("2026-03-08 08:00"), entries.get(0).start);
        assertEquals(3600000L, entries.get(0).start - entries.get(0).fire);
        assertEquals(at("2026-03-09 08:00"), entries.get(1).start);
    }
}
