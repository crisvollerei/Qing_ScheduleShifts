package com.example.myapp;

import static org.junit.Assert.*;
import android.Manifest;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.CalendarContract;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ReminderCalendarTest {
    Context context;
    CalendarStore store;
    long now;
    TimeZone zone;
    @Before public void prepare() throws Exception {
        zone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf((Application) context).grantPermissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR);
        WorkReminders.prefs(context).edit().clear().putInt("lead", 60).commit();
        context.getSharedPreferences("WidgetSettings", Context.MODE_PRIVATE).edit().clear()
                .putString("cycle", "[\"白班\"]").putString("anchorDate", "2026-10-24").commit();
        now = new SimpleDateFormat("yyyy-MM-dd HH:mm").parse("2026-10-24 22:00").getTime();
        store = new CalendarStore();
        ShadowContentResolver.registerProviderInternal(CalendarContract.AUTHORITY, store);
    }
    @After public void restore() { TimeZone.setDefault(zone); }
    JSONObject export(int days) throws Exception {
        JSONObject preview = ReminderCalendar.preview(context, now, 3, days);
        return ReminderCalendar.export(context, now, 3, days, preview.getString("token"));
    }
    @Test public void repeatExportUpdatesExistingEventsInsteadOfDuplicatingThem() throws Exception {
        assertEquals(7, export(7).getInt("created"));
        assertEquals(7, store.events.size()); assertEquals(7, store.reminders.size());
        assertEquals(7, export(7).getInt("updated"));
        assertEquals(7, store.events.size()); assertEquals(7, store.reminders.size());
        assertEquals(7, ReminderCalendar.records(context).length());
    }
    @Test public void clearingOnlyDeletesTrackedEventsWithTheirOwnershipMarker() throws Exception {
        export(1);
        ContentValues personal = new ContentValues(); personal.put("title", "自己的约会"); personal.put("calendar_id", 3);
        personal.put("description", "私人日程");
        Uri other = store.insert(CalendarContract.Events.CONTENT_URI, personal);
        assertEquals(1, ReminderCalendar.clear(context));
        assertEquals(1, store.events.size()); assertTrue(store.events.containsKey(ContentUris.parseId(other)));
        assertEquals(0, ReminderCalendar.records(context).length());
    }
    @Test public void removedOwnershipMarkerProtectsManuallyChangedEventFromCleanup() throws Exception {
        export(1); store.events.values().iterator().next().put("description", "用户自己改过的日程");
        assertEquals(0, ReminderCalendar.clear(context)); assertEquals(1, store.events.size());
    }
    @Test public void newlyRestingDatesAreRemovedOnlyInsideTheExplicitlySelectedRange() throws Exception {
        export(7);
        context.getSharedPreferences("WidgetSettings", Context.MODE_PRIVATE).edit().putString("cycle", "[\"休息\"]").commit();
        assertEquals(1, export(1).getInt("removed"));
        assertEquals(6, store.events.size()); assertEquals(6, ReminderCalendar.records(context).length());
    }
    @Test public void changingAdvanceInvalidatesPreviewBeforeAnyCalendarWrite() throws Exception {
        JSONObject preview = ReminderCalendar.preview(context, now, 3, 7);
        WorkReminders.prefs(context).edit().putInt("lead", 30).commit();
        try { ReminderCalendar.export(context, now, 3, 7, preview.getString("token")); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("重新预览")); }
        assertTrue(store.events.isEmpty());
    }
    @Test public void permissionAndWritableCalendarAreCheckedBeforePreview() throws Exception {
        try { ReminderCalendar.preview(context, now, 9, 7); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("不可写")); }
        Shadows.shadowOf((Application) context).denyPermissions(Manifest.permission.WRITE_CALENDAR);
        try { ReminderCalendar.preview(context, now, 3, 7); fail(); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("日历")); }
    }
    @Test public void changingShiftTimeUpdatesTheSameCivilDayRecord() throws Exception {
        export(1);
        long id = store.events.keySet().iterator().next();
        context.getSharedPreferences("WidgetSettings", Context.MODE_PRIVATE).edit()
                .putString("shiftDefs", "{\"白班\":{\"isWork\":true,\"startTime\":\"09:00\",\"endTime\":\"18:00\"}}").commit();
        assertEquals(1, export(1).getInt("updated"));
        assertTrue(store.events.containsKey(id));
        assertEquals(new SimpleDateFormat("yyyy-MM-dd HH:mm").parse("2026-10-25 09:00").getTime(),
                store.events.get(id).getAsLong("dtstart").longValue());
    }

    /** Minimal local provider for exercising real ContentProviderOperation back references. */
    static class CalendarStore extends ContentProvider {
        final Map<Long, ContentValues> events = new LinkedHashMap<>(), reminders = new LinkedHashMap<>();
        long serial = 10;
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return null; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            MatrixCursor cursor = new MatrixCursor(projection);
            if (uri.getPath().startsWith("/calendars")) { cursor.addRow(new Object[]{3L,"本机日历","local"}); return cursor; }
            ContentValues value = events.get(ContentUris.parseId(uri));
            if (value != null) {
                Object[] row = new Object[projection.length];
                for (int i = 0; i < row.length; i++) row[i] = value.get(projection[i]);
                cursor.addRow(row);
            }
            return cursor;
        }
        @Override public Uri insert(Uri uri, ContentValues values) {
            long id = ++serial;
            (uri.getPath().startsWith("/events") ? events : reminders).put(id, new ContentValues(values));
            return ContentUris.withAppendedId(uri, id);
        }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            ContentValues event = events.get(ContentUris.parseId(uri));
            if (event == null) return 0; event.putAll(values); return 1;
        }
        @Override public int delete(Uri uri, String selection, String[] args) {
            if (uri.getPath().startsWith("/reminders")) {
                int before = reminders.size();
                reminders.values().removeIf(value -> value.getAsLong("event_id") == Long.parseLong(args[0]));
                return before - reminders.size();
            }
            return events.remove(ContentUris.parseId(uri)) == null ? 0 : 1;
        }
    }
}
