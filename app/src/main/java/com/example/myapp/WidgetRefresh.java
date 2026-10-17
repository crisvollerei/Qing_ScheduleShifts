package com.example.myapp;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;

/** Native clocks tick in the launcher; only schedule data needs an application update. */
final class WidgetRefresh {
    static final String ACTION_TICK = "com.example.myapp.action.WIDGET_TICK";
    private static final int BOUNDARY = 1001;
    private static final int RECOVERY = 1003;
    private static final int LEGACY_SECOND = 1004;

    private WidgetRefresh() { }

    private static PendingIntent tickIntent(Context context, int code) {
        return PendingIntent.getBroadcast(context, code,
                new Intent(context, MyWidgetProvider.class).setAction(ACTION_TICK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static boolean canScheduleExact(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return alarms != null && (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms());
    }

    static boolean hasWidgets(Context context) {
        return AppWidgetManager.getInstance(context).getAppWidgetIds(
                new ComponentName(context, MyWidgetProvider.class)).length > 0;
    }

    static void schedule(Context context, long boundary) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        long target = Math.max(System.currentTimeMillis() + 50, boundary);
        PendingIntent event = tickIntent(context, BOUNDARY);
        try {
            if (canScheduleExact(context)) {
                if (Build.VERSION.SDK_INT >= 23) {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, event);
                } else {
                    alarms.setExact(AlarmManager.RTC_WAKEUP, target, event);
                }
            } else if (Build.VERSION.SDK_INT >= 23) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, event);
            } else {
                alarms.set(AlarmManager.RTC, target, event);
            }
        } catch (SecurityException revokedWhileScheduling) {
            alarms.set(AlarmManager.RTC, target, event);
        }
        // Recovery is inexact and non-waking; never poll once per second on modern Android.
        alarms.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + 1800000L,
                1800000L, tickIntent(context, RECOVERY));
        if (Build.VERSION.SDK_INT < 24) scheduleLegacySecond(context, alarms);
    }

    private static void scheduleLegacySecond(Context context, AlarmManager alarms) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power != null && power.isInteractive()) {
            long second = (System.currentTimeMillis() / 1000L + 1) * 1000L;
            alarms.setExact(AlarmManager.RTC, second, tickIntent(context, LEGACY_SECOND));
        } else {
            alarms.cancel(tickIntent(context, LEGACY_SECOND));
        }
    }

    static void cancel(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        for (int code : new int[]{BOUNDARY, RECOVERY, LEGACY_SECOND}) {
            alarms.cancel(tickIntent(context, code));
        }
    }
}
