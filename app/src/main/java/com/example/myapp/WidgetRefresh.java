package com.example.myapp;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;

/** Native clocks tick in the launcher; non-waking retries repair a stalled host timer. */
final class WidgetRefresh {
    static final String ACTION_TICK = "com.example.myapp.action.WIDGET_TICK";
    static final String ACTION_RECOVER = "com.example.myapp.action.WIDGET_RECOVER";
    static final String ACTION_REFRESH = "com.example.myapp.action.WIDGET_REFRESH";
    static final long RECOVERY_INTERVAL_MS = 60_000L;
    // Retain old identities to accept installed widget clicks and cancel queued alarms after upgrade.
    static final String ACTION_SPIN_START = "com.example.myapp.action.WIDGET_SPIN_START";
    static final String ACTION_SPIN = "com.example.myapp.action.WIDGET_SPIN";
    private static final int BOUNDARY = 1001;
    private static final int RECOVERY = 1003;
    private static final int LEGACY_SECOND = 1004;
    private static final int HOST_RECOVERY = 1005;
    private static final int SPIN_FRAME_BASE = 2000;
    private static final int SPIN_FRAME_COUNT = 9;

    private WidgetRefresh() { }

    private static PendingIntent tickIntent(Context context, int code) {
        return PendingIntent.getBroadcast(context, code,
                new Intent(context, MyWidgetProvider.class).setAction(ACTION_TICK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent recoveryIntent(Context context) {
        return PendingIntent.getBroadcast(context, HOST_RECOVERY,
                new Intent(context, MyWidgetProvider.class).setAction(ACTION_RECOVER),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent spinIntent(Context context, int frame) {
        return PendingIntent.getBroadcast(context, SPIN_FRAME_BASE + frame,
                new Intent(context, MyWidgetProvider.class)
                        .setAction(ACTION_SPIN)
                        .putExtra("frame", frame),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void cancelLegacySpin(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        for (int frame = 0; frame < SPIN_FRAME_COUNT; frame++) {
            alarms.cancel(spinIntent(context, frame));
        }
    }

    static boolean canScheduleExact(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return alarms != null && (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms());
    }

    static boolean hasWidgets(Context context) {
        return MyWidgetProvider.widgetIds(context).length > 0;
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
        if (Build.VERSION.SDK_INT >= 24) scheduleRecovery(context);
        else scheduleLegacySecond(context, alarms);
    }

    static void scheduleRecovery(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        long target = System.currentTimeMillis() + RECOVERY_INTERVAL_MS;
        PendingIntent event = recoveryIntent(context);
        try {
            if (canScheduleExact(context)) alarms.setExact(AlarmManager.RTC, target, event);
            else alarms.set(AlarmManager.RTC, target, event);
        } catch (SecurityException revokedWhileScheduling) {
            alarms.set(AlarmManager.RTC, target, event);
        }
    }

    static void recover(Context context) {
        if (!hasWidgets(context)) {
            cancel(context);
            return;
        }
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null || power.isInteractive()) {
            MyWidgetProvider.refreshAll(context);
        } else {
            // Keep a process-independent retry pending, but never wake or redraw a sleeping device.
            scheduleRecovery(context);
        }
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
        alarms.cancel(recoveryIntent(context));
        cancelLegacySpin(context);
    }
}
