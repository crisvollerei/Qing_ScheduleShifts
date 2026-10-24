package com.example.myapp;

import android.Manifest;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.CalendarContract;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

/** Explicit calendar exports, never an automatic side effect of changing the schedule. */
final class ReminderCalendar {
    static boolean allowed(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }
    static JSONArray records(Context context) {
        try { return new JSONArray(WorkReminders.prefs(context).getString("calendarRecords", "[]")); }
        catch (Exception invalid) { return new JSONArray(); }
    }
    static JSONArray calendars(Context context) throws Exception {
        if (!allowed(context)) throw new IllegalStateException("请先允许日历读写权限");
        JSONArray result = new JSONArray();
        try (Cursor cursor = context.getContentResolver().query(CalendarContract.Calendars.CONTENT_URI,
                new String[]{"_id", "calendar_displayName", "account_name"},
                "calendar_access_level >= ? AND visible = 1", new String[]{"500"}, null)) {
            if (cursor != null) while (cursor.moveToNext()) result.put(new JSONObject().put("id", cursor.getLong(0))
                    .put("name", cursor.getString(1)).put("account", cursor.getString(2)));
        }
        return result;
    }
    private static void requireCalendar(Context context, long id) throws Exception {
        JSONArray available = calendars(context);
        for (int i = 0; i < available.length(); i++) if (available.getJSONObject(i).getLong("id") == id) return;
        throw new IllegalArgumentException("所选日历不可写或已不存在，请重新选择");
    }
    static JSONObject preview(Context context, long now, long calendar, int days) throws Exception {
        requireCalendar(context, calendar);
        int lead = WorkReminders.lead(context);
        List<ReminderSchedule.Entry> entries = ReminderSchedule.range(context, now, lead, days);
        JSONArray future = new JSONArray();
        for (ReminderSchedule.Entry entry : entries) if (entry.fire > now) future.put(entry.json());
        return new JSONObject().put("entries", future).put("skipped", entries.size() - future.length())
                .put("token", ReminderSchedule.fingerprint(entries, calendar, days, lead)).put("lead", lead);
    }
    private static String day(long time) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(time);
    }
    private static boolean owned(Context context, JSONObject record) throws Exception {
        try (Cursor cursor = context.getContentResolver().query(ContentUris.withAppendedId(
                CalendarContract.Events.CONTENT_URI, record.getLong("id")),
                new String[]{"description", "calendar_id"}, null, null, null)) {
            return cursor != null && cursor.moveToFirst() && cursor.getLong(1) == record.getLong("calendar")
                    && cursor.getString(0) != null && cursor.getString(0).contains(record.getString("marker"));
        }
    }
    private static String owner(Context context) {
        SharedPreferences p = WorkReminders.prefs(context);
        String id = p.getString("calendarOwner", "");
        if (id.isEmpty()) { id = UUID.randomUUID().toString(); p.edit().putString("calendarOwner", id).commit(); }
        return id;
    }

    static synchronized JSONObject export(Context context, long now, long calendar, int days, String token) throws Exception {
        JSONObject plan = preview(context, now, calendar, days);
        if (!plan.getString("token").equals(token)) throw new IllegalStateException("排班或日期已变化，请重新预览后生成");
        JSONArray entries = plan.getJSONArray("entries"), old = records(context), keep = new JSONArray();
        Calendar first = Calendar.getInstance(); first.setTimeInMillis(now); first.add(Calendar.DAY_OF_YEAR, 1);
        first.set(Calendar.HOUR_OF_DAY, 0); first.set(Calendar.MINUTE, 0); first.set(Calendar.SECOND, 0); first.set(Calendar.MILLISECOND, 0);
        Calendar last = (Calendar) first.clone(); last.add(Calendar.DAY_OF_YEAR, days);
        Map<String, JSONObject> existing = new HashMap<>();
        for (int i = 0; i < old.length(); i++) {
            JSONObject record = old.getJSONObject(i);
            long start = record.getLong("start");
            if (record.getLong("calendar") == calendar && start >= first.getTimeInMillis() && start < last.getTimeInMillis()) {
                if (owned(context, record)) existing.put(day(start), record);
            } else keep.put(record);
        }
        String marker = "[qingzheng:" + owner(context) + "]";
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        List<JSONObject> updated = new ArrayList<>();
        Map<Integer, JSONObject> inserted = new HashMap<>();
        Set<String> desired = new HashSet<>();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String key = day(entry.getLong("start")); desired.add(key);
            JSONObject record = existing.get(key);
            ContentProviderOperation.Builder event = record == null
                    ? ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                    : ContentProviderOperation.newUpdate(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, record.getLong("id")));
            event.withValue("calendar_id", calendar).withValue("title", "上班 · " + entry.getString("name"))
                    .withValue("dtstart", entry.getLong("start")).withValue("dtend", entry.getLong("end"))
                    .withValue("eventTimezone", TimeZone.getDefault().getID()).withValue("hasAlarm", 1)
                    .withValue("description", "清蒸排班生成的上班提醒；排班变更后请重新生成所选范围。\n" + marker);
            int eventIndex = operations.size(); operations.add(event.build());
            JSONObject tracked = new JSONObject().put("calendar", calendar).put("start", entry.getLong("start")).put("marker", marker);
            ContentProviderOperation.Builder reminder = ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                    .withValue("minutes", plan.getInt("lead")).withValue("method", CalendarContract.Reminders.METHOD_ALERT);
            if (record == null) {
                inserted.put(eventIndex, tracked);
                reminder.withValueBackReference("event_id", eventIndex);
            } else {
                tracked.put("id", record.getLong("id")); updated.add(tracked);
                operations.add(ContentProviderOperation.newDelete(CalendarContract.Reminders.CONTENT_URI)
                        .withSelection("event_id = ?", new String[]{record.getString("id")}).build());
                reminder.withValue("event_id", record.getLong("id"));
            }
            operations.add(reminder.build());
        }
        int removed = 0;
        for (Map.Entry<String, JSONObject> entry : existing.entrySet()) if (!desired.contains(entry.getKey())) {
            operations.add(ContentProviderOperation.newDelete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,
                    entry.getValue().getLong("id"))).build()); removed++;
        }
        if (!operations.isEmpty()) {
            ContentProviderResult[] results = context.getContentResolver().applyBatch(CalendarContract.AUTHORITY, operations);
            for (Map.Entry<Integer, JSONObject> insert : inserted.entrySet()) {
                insert.getValue().put("id", ContentUris.parseId(results[insert.getKey()].uri)); keep.put(insert.getValue());
            }
        }
        for (JSONObject record : updated) keep.put(record);
        if (!WorkReminders.prefs(context).edit().putString("calendarRecords", keep.toString()).commit())
            throw new IllegalStateException("日历已写入，但管理记录保存失败，请在系统日历中核对");
        return new JSONObject().put("created", inserted.size()).put("updated", updated.size()).put("removed", removed);
    }

    static synchronized int clear(Context context) throws Exception {
        if (!allowed(context)) throw new IllegalStateException("请先允许日历读写权限");
        JSONArray records = records(context);
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            if (owned(context, record)) operations.add(ContentProviderOperation.newDelete(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, record.getLong("id"))).build());
        }
        if (!operations.isEmpty()) context.getContentResolver().applyBatch(CalendarContract.AUTHORITY, operations);
        WorkReminders.prefs(context).edit().putString("calendarRecords", "[]").commit();
        return operations.size();
    }
}
