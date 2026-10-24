package com.example.myapp;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Uses the same shift definitions and civil-date rules as the widgets. */
final class ReminderSchedule {
    static final int MAX_DAYS = 90;
    static final int MAX_LEAD = 1440;

    static final class Entry {
        final String name;
        final long start, end, fire;
        Entry(MyWidgetProvider.ShiftSlot slot, int minutes) {
            name = slot.name;
            start = slot.start.getTimeInMillis();
            end = slot.end.getTimeInMillis();
            fire = start - minutes * 60000L;
        }
        JSONObject json() throws JSONException {
            return new JSONObject().put("name", name).put("start", start).put("end", end)
                    .put("fire", fire).put("startText", format(start)).put("fireText", format(fire));
        }
    }

    static String format(long millis) {
        return new SimpleDateFormat("MM月dd日 HH:mm", Locale.SIMPLIFIED_CHINESE).format(millis);
    }

    static List<Entry> upcoming(Context context, long now, int minutes, int count) {
        String[] cycle = MyWidgetProvider.readCycle(context);
        Calendar anchor = MyWidgetProvider.readAnchor(context);
        Map<String, MyWidgetProvider.ShiftDef> defs = MyWidgetProvider.readShiftDefs(context);
        Calendar from = Calendar.getInstance();
        from.setTimeInMillis(now + minutes * 60000L);
        List<Entry> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MyWidgetProvider.ShiftSlot slot = MyWidgetProvider.nextWorkSlot(from, anchor, cycle, defs);
            if (slot == null) break;
            result.add(new Entry(slot, minutes));
            from = (Calendar) slot.start.clone();
        }
        return result;
    }

    /** Range is tomorrow through tomorrow + days - 1, by shift-start date. */
    static List<Entry> range(Context context, long now, int minutes, int days) {
        if (days < 1 || days > MAX_DAYS) throw new IllegalArgumentException("天数须为 1–90 的整数");
        String[] cycle = MyWidgetProvider.readCycle(context);
        Calendar anchor = MyWidgetProvider.readAnchor(context);
        Map<String, MyWidgetProvider.ShiftDef> defs = MyWidgetProvider.readShiftDefs(context);
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(now);
        day.set(Calendar.HOUR_OF_DAY, 0); day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0); day.set(Calendar.MILLISECOND, 0);
        List<Entry> result = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            day.add(Calendar.DAY_OF_YEAR, 1);
            String name = MyWidgetProvider.shiftNameAt(day, anchor, cycle);
            MyWidgetProvider.ShiftDef def = MyWidgetProvider.defOf(name, defs);
            if (def.isWork) result.add(new Entry(MyWidgetProvider.slotOf(day, name, def), minutes));
        }
        return result;
    }

    static JSONArray json(List<Entry> entries) throws JSONException {
        JSONArray result = new JSONArray();
        for (Entry entry : entries) result.put(entry.json());
        return result;
    }

    static String fingerprint(List<Entry> entries, long calendarId, int days, int lead) throws Exception {
        String value = calendarId + ":" + days + ":" + lead + ":" + json(entries);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }
}
