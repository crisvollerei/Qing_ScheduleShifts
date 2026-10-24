package com.example.myapp;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

/** Independent of widget instances and their non-waking refresh/recovery alarms. */
final class WorkReminders {
    static final String FIRE = "com.example.myapp.WORK_REMINDER";
    static final String CHANNEL = "work_alarms_v1";
    static final int COUNT = 8, TEST_ID = 1099;
    static final long MAX_LATENESS = 10 * 60000L;

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("WorkReminders", Context.MODE_PRIVATE);
    }
    static int lead(Context context) { return prefs(context).getInt("lead", 60); }

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "上班闹钟", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("上班前持续响铃及停止操作；声音与振动由提醒设置控制");
            channel.setSound(null, null);
            channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }
    static boolean exactAllowed(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms();
    }
    static boolean notificationsAllowed(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = context.getSystemService(NotificationManager.class).getNotificationChannel(CHANNEL);
            return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }
    static boolean fullScreenAllowed(Context context) {
        return Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager.class).canUseFullScreenIntent();
    }
    private static PendingIntent pending(Context context, int request, Intent intent) {
        return PendingIntent.getBroadcast(context, request,
                intent == null ? new Intent(context, WorkReminderReceiver.class).setAction(FIRE) : intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private static void cancelScheduled(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        for (int i = 0; i < COUNT; i++) manager.cancel(pending(context, 1000 + i, null));
    }
    static synchronized void cancelTest(Context context) {
        ((AlarmManager) context.getSystemService(Context.ALARM_SERVICE)).cancel(pending(context, TEST_ID, null));
        prefs(context).edit().remove("testFire").apply();
    }

    static synchronized void save(Context context, JSONObject data, long now) throws Exception {
        Object minutes = data.get("lead");
        if (!(minutes instanceof Number) || ((Number) minutes).doubleValue() != ((Number) minutes).intValue()
                || ((Number) minutes).intValue() < 0 || ((Number) minutes).intValue() > ReminderSchedule.MAX_LEAD)
            throw new IllegalArgumentException("提前量须为 0–1440 分钟的整数");
        if (!(data.get("enabled") instanceof Boolean) || !(data.get("sound") instanceof Boolean)
                || !(data.get("vibrate") instanceof Boolean)) throw new IllegalArgumentException("提醒设置无效");
        if (!data.getBoolean("sound") && !data.getBoolean("vibrate"))
            throw new IllegalArgumentException("请至少保留铃声或振动中的一项");
        boolean saved = prefs(context).edit().putBoolean("enabled", data.getBoolean("enabled"))
                .putInt("lead", ((Number) minutes).intValue()).putBoolean("sound", data.getBoolean("sound"))
                .putBoolean("vibrate", data.getBoolean("vibrate"))
                .remove("lastFailure")
                .putString("theme", "dark".equals(data.optString("theme")) ? "dark" : "light").commit();
        if (!saved) throw new IllegalStateException("设置保存失败，请检查设备存储后重试");
        cancelTest(context);
        context.stopService(new Intent(context, WorkAlarmService.class));
        reconcile(context, now);
    }

    static synchronized void reconcile(Context context, long now) {
        ensureChannel(context);
        SharedPreferences p = prefs(context);
        long generation = p.getLong("generation", 0) + 1;
        cancelScheduled(context);
        if (!p.edit().putLong("generation", generation).putString("pending", "[]").remove("error").commit()) {
            p.edit().putString("error", "闹钟状态保存失败，未登记提醒").apply();
            return;
        }
        if (!p.getBoolean("enabled", false) || !exactAllowed(context) || !notificationsAllowed(context)) return;
        JSONArray scheduled = new JSONArray();
        try {
            List<ReminderSchedule.Entry> entries = ReminderSchedule.upcoming(context, now, lead(context), COUNT + 1);
            AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            for (ReminderSchedule.Entry entry : entries) {
                if (entry.start == p.getLong("lastFiredStart", 0)) continue;
                if (scheduled.length() == COUNT) break;
                Intent fire = new Intent(context, WorkReminderReceiver.class).setAction(FIRE)
                        .putExtra("generation", generation).putExtra("start", entry.start)
                        .putExtra("fire", entry.fire).putExtra("name", entry.name);
                manager.setAlarmClock(new AlarmManager.AlarmClockInfo(entry.fire, open(context)),
                        pending(context, 1000 + scheduled.length(), fire));
                scheduled.put(entry.json());
            }
            if (!p.edit().putString("pending", scheduled.toString()).commit())
                throw new IllegalStateException("Unable to persist pending alarms");
        } catch (Exception unavailable) {
            cancelScheduled(context);
            p.edit().putString("error", "闹钟未能登记，请检查权限后重试").apply();
        }
    }
    static PendingIntent open(Context context) {
        return PendingIntent.getActivity(context, 1800,
                new Intent(context, MainActivity.class).putExtra("openReminders", true)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    static synchronized long test(Context context, long now) {
        ensureChannel(context);
        if (!exactAllowed(context) || !notificationsAllowed(context))
            throw new IllegalStateException("请先允许精确闹钟和通知，再测试");
        long fireAt = now + 30000L;
        Intent fire = new Intent(context, WorkReminderReceiver.class).setAction(FIRE)
                .putExtra("test", true).putExtra("fire", fireAt).putExtra("name", "测试闹钟");
        ((AlarmManager) context.getSystemService(Context.ALARM_SERVICE)).setAlarmClock(
                new AlarmManager.AlarmClockInfo(fireAt, open(context)), pending(context, TEST_ID, fire));
        if (!prefs(context).edit().putLong("testFire", fireAt).commit()) {
            cancelTest(context);
            throw new IllegalStateException("测试状态保存失败，已取消测试");
        }
        return fireAt;
    }

    /** Validate stale broadcasts and persist consumption before starting sound. */
    static synchronized Intent consume(Context context, Intent intent, long now) {
        SharedPreferences p = prefs(context);
        boolean test = intent.getBooleanExtra("test", false);
        long fire = intent.getLongExtra("fire", 0), start = intent.getLongExtra("start", 0);
        if (test) {
            if (fire == 0 || fire != p.getLong("testFire", 0)) return null;
        } else {
            if (!p.getBoolean("enabled", false) || intent.getLongExtra("generation", -1) != p.getLong("generation", 0)
                    || start == p.getLong("lastFiredStart", 0)) return null;
        }
        if (now < fire) return null;
        if (test) p.edit().remove("testFire").commit();
        else {
            if (!p.edit().putLong("lastFiredStart", start).commit()) return null;
            reconcile(context, now);
        }
        if (now - fire > MAX_LATENESS || !notificationsAllowed(context) || !exactAllowed(context)) return null;
        return new Intent(context, WorkAlarmService.class).putExtra("name", intent.getStringExtra("name"))
                .putExtra("start", start).putExtra("test", test);
    }

    static JSONObject state(Context context, long now) throws Exception {
        ensureChannel(context);
        SharedPreferences p = prefs(context);
        List<ReminderSchedule.Entry> upcoming = ReminderSchedule.upcoming(context, now, lead(context), 1);
        JSONArray pending = new JSONArray(p.getString("pending", "[]"));
        boolean enabled = p.getBoolean("enabled", false), exact = exactAllowed(context), notices = notificationsAllowed(context);
        String status = !enabled ? "未启用" : !exact || !notices ? "待授权" : p.contains("error") ? "安排失败"
                : pending.length() == 0 ? "暂无可安排的工作班次" : "已安排";
        return new JSONObject().put("enabled", enabled).put("lead", lead(context))
                .put("sound", p.getBoolean("sound", true)).put("vibrate", p.getBoolean("vibrate", true))
                .put("exact", exact).put("notifications", notices).put("fullScreen", fullScreenAllowed(context))
                .put("status", status).put("error", p.getString("error", ""))
                .put("lastFailure", p.getString("lastFailure", ""))
                .put("next", upcoming.isEmpty() ? JSONObject.NULL : upcoming.get(0).json())
                .put("scheduled", pending.length() == 0 ? JSONObject.NULL : pending.getJSONObject(0))
                .put("testFire", p.getLong("testFire", 0)).put("calendarAllowed", ReminderCalendar.allowed(context))
                .put("calendarCount", ReminderCalendar.records(context).length());
    }
}
