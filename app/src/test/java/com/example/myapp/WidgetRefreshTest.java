package com.example.myapp;

import static org.junit.Assert.*;

import android.app.AlarmManager;
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
        assertEquals(2, Shadows.shadowOf(alarms).getScheduledAlarms().size());
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
        assertEquals(2, Shadows.shadowOf(alarms).getScheduledAlarms().size());
        WidgetRefresh.cancel(context);
        assertTrue(Shadows.shadowOf(alarms).getScheduledAlarms().isEmpty());
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
