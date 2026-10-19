package com.example.myapp;

import static org.junit.Assert.*;

import android.app.AlarmManager;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.TextView;
import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class WidgetRefreshTest {
    private Context context;
    private AlarmManager alarms;
    private SharedPreferences preferences;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        preferences = context.getSharedPreferences("WidgetSettings", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        WidgetRefresh.cancel(context);
    }

    @Test public void exactBoundaryAndNonWakingRecoveryCoexistWithoutMinuteOverwrite() {
        Shadows.shadowOf(alarms).setCanScheduleExactAlarms(true);
        long boundary = System.currentTimeMillis() + 123000;
        WidgetRefresh.schedule(context, boundary);
        assertEquals(3, Shadows.shadowOf(alarms).getScheduledAlarms().size());
        ShadowAlarmManager.ScheduledAlarm event = Shadows.shadowOf(alarms).getScheduledAlarms().stream()
                .filter(alarm -> alarm.triggerAtTime == boundary).findFirst().orElseThrow(AssertionError::new);
        assertEquals(AlarmManager.RTC_WAKEUP, event.type);
        assertEquals(0, event.getWindowLengthMs());
        assertTrue(event.allowWhileIdle);
        assertTrue(Shadows.shadowOf(alarms).getScheduledAlarms().stream()
                .anyMatch(alarm -> alarm.type == AlarmManager.RTC && alarm.interval == 1800000L));
    }

    @Test public void deniedPermissionDegradesWithoutCrashingAndRemovalCancelsBothAlarms() {
        Shadows.shadowOf(alarms).setCanScheduleExactAlarms(false);
        assertFalse(WidgetRefresh.canScheduleExact(context));
        WidgetRefresh.schedule(context, System.currentTimeMillis() + 123000);
        assertEquals(3, Shadows.shadowOf(alarms).getScheduledAlarms().size());
        WidgetRefresh.cancel(context);
        assertTrue(Shadows.shadowOf(alarms).getScheduledAlarms().isEmpty());
    }

    @Test public void hostRecoveryIsNonWakingAndDoesNotOverwriteTheStateBoundary() {
        Shadows.shadowOf(alarms).setCanScheduleExactAlarms(true);
        long now = System.currentTimeMillis();
        long boundary = now + 123_000;
        WidgetRefresh.schedule(context, boundary);
        WidgetRefresh.scheduleRecovery(context);
        assertEquals(3, Shadows.shadowOf(alarms).getScheduledAlarms().size());
        ShadowAlarmManager.ScheduledAlarm retry = Shadows.shadowOf(alarms).getScheduledAlarms().stream()
                .filter(alarm -> WidgetRefresh.ACTION_RECOVER.equals(
                        Shadows.shadowOf(alarm.operation).getSavedIntent().getAction()))
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals(AlarmManager.RTC, retry.type);
        assertFalse(retry.allowWhileIdle);
        assertEquals(0, retry.interval);
        assertEquals(0, retry.getWindowLengthMs());
        assertEquals(now + WidgetRefresh.RECOVERY_INTERVAL_MS, retry.triggerAtTime);
        assertTrue(Shadows.shadowOf(alarms).getScheduledAlarms().stream()
                .anyMatch(alarm -> alarm.triggerAtTime == boundary && alarm.type == AlarmManager.RTC_WAKEUP));
    }

    @Test public void deniedExactAccessAlsoDegradesTheRecoveryAlarmWithoutWaking() {
        Shadows.shadowOf(alarms).setCanScheduleExactAlarms(false);
        WidgetRefresh.scheduleRecovery(context);
        ShadowAlarmManager.ScheduledAlarm retry = Shadows.shadowOf(alarms).getScheduledAlarms().get(0);
        assertEquals(AlarmManager.RTC, retry.type);
        assertFalse(retry.allowWhileIdle);
        assertNotEquals(0, retry.getWindowLengthMs());
    }

    @Test public void recoveryWithoutWidgetsCancelsAllOutstandingAlarms() {
        WidgetRefresh.schedule(context, System.currentTimeMillis() + 123_000);
        WidgetRefresh.recover(context);
        assertTrue(Shadows.shadowOf(alarms).getScheduledAlarms().isEmpty());
    }

    @Test public void pendingRecoveryBroadcastRebindsVisibleWidgetsAndSchedulesAnotherRetry() throws Exception {
        Shadows.shadowOf(alarms).setCanScheduleExactAlarms(true);
        Shadows.shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE)).setIsInteractive(true);
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int id = Shadows.shadowOf(manager).createWidget(MyWidgetProvider.class, R.layout.widget_layout_mini);
        ((TextView) Shadows.shadowOf(manager).getViewFor(id).findViewById(R.id.countdown_label))
                .setText("stale countdown");
        ShadowAlarmManager.ScheduledAlarm retry = recoveryAlarm();
        retry.operation.send();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNotEquals("stale countdown", ((TextView) Shadows.shadowOf(manager).getViewFor(id)
                .findViewById(R.id.countdown_label)).getText().toString());
        assertNotSame(retry, recoveryAlarm());
        assertEquals(3, Shadows.shadowOf(alarms).getScheduledAlarms().size());
    }

    @Test public void recoveryWhileScreenIsOffKeepsARetryWithoutRedrawingWidgets() throws Exception {
        Shadows.shadowOf(alarms).setCanScheduleExactAlarms(true);
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int id = Shadows.shadowOf(manager).createWidget(MyWidgetProvider.class, R.layout.widget_layout_mini);
        ((TextView) Shadows.shadowOf(manager).getViewFor(id).findViewById(R.id.countdown_label))
                .setText("screen off");
        Shadows.shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE)).setIsInteractive(false);
        ShadowAlarmManager.ScheduledAlarm retry = recoveryAlarm();
        retry.operation.send();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("screen off", ((TextView) Shadows.shadowOf(manager).getViewFor(id)
                .findViewById(R.id.countdown_label)).getText().toString());
        assertNotSame(retry, recoveryAlarm());
        assertEquals(3, Shadows.shadowOf(alarms).getScheduledAlarms().size());
    }

    @Test public void manualRefreshDoesNotWaitForTheLastAnimationAlarm() {
        Shadows.shadowOf(alarms).setCanScheduleExactAlarms(false);
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int id = Shadows.shadowOf(manager).createWidget(MyWidgetProvider.class, R.layout.widget_layout_mini);
        ((TextView) Shadows.shadowOf(manager).getViewFor(id).findViewById(R.id.countdown_label))
                .setText("stale countdown");
        new MyWidgetProvider().onReceive(context, new Intent(context, MyWidgetProvider.class)
                .setAction(WidgetRefresh.ACTION_SPIN_START));
        assertNotEquals("stale countdown", ((TextView) Shadows.shadowOf(manager).getViewFor(id)
                .findViewById(R.id.countdown_label)).getText().toString());
        assertTrue(Shadows.shadowOf(alarms).getScheduledAlarms().size() > 3);
    }

    private ShadowAlarmManager.ScheduledAlarm recoveryAlarm() {
        return Shadows.shadowOf(alarms).getScheduledAlarms().stream()
                .filter(alarm -> WidgetRefresh.ACTION_RECOVER.equals(
                        Shadows.shadowOf(alarm.operation).getSavedIntent().getAction()))
                .findFirst().orElseThrow(AssertionError::new);
    }

    @Test public void emptyDefinitionsDoNotReintroduceDeletedDefaultShiftDefinitions() {
        preferences.edit().putString("shiftDefs", "{}").commit();
        assertTrue(MyWidgetProvider.readShiftDefs(context).isEmpty());
    }

    @Test public void malformedCycleEntriesDoNotBecomeCoercedShiftNames() {
        preferences.edit().putString("cycle", "[123]").commit();
        assertEquals(8, MyWidgetProvider.readCycle(context).length);
        preferences.edit().putString("cycle", "[\"带 空格\"]").commit();
        assertEquals(8, MyWidgetProvider.readCycle(context).length);
    }

    @Test public void nativeDefinitionNormalizationMatchesWebRestAndTimeFallbacks() {
        preferences.edit().putString("shiftDefs", "{\"年假\":{},\"外勤\":{\"startTime\":\"99:00\",\"endTime\":\"bad\"}}").commit();
        java.util.Map<String, MyWidgetProvider.ShiftDef> defs = MyWidgetProvider.readShiftDefs(context);
        assertFalse(defs.get("年假").isWork);
        assertEquals("rest", defs.get("年假").cssClass);
        assertEquals("09:00", defs.get("外勤").startTime);
        assertEquals("17:00", defs.get("外勤").endTime);
    }
}
