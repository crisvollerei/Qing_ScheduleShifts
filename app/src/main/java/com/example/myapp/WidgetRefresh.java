package com.example.myapp;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;

/** Native clocks tick in the launcher; non-waking retries repair a stalled host timer. */
final class WidgetRefresh {
    static final String ACTION_TICK = "com.example.myapp.action.WIDGET_TICK";
    static final String ACTION_RECOVER = "com.example.myapp.action.WIDGET_RECOVER";
    static final long RECOVERY_INTERVAL_MS = 60_000L;
    /** 点击刷新按钮：启动转圈动画。 */
    static final String ACTION_SPIN_START = "com.example.myapp.action.WIDGET_SPIN_START";
    /** 刷新按钮转圈动画的每一帧动作。 */
    static final String ACTION_SPIN = "com.example.myapp.action.WIDGET_SPIN";
    private static final int BOUNDARY = 1001;
    private static final int RECOVERY = 1003;
    private static final int LEGACY_SECOND = 1004;
    private static final int HOST_RECOVERY = 1005;
    /** 动画帧的闹钟请求码基数，逐帧 +1，避免互相覆盖。 */
    private static final int SPIN_FRAME_BASE = 2000;

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

    /**
     * 刷新按钮的「转一圈」动画。
     *
     * RemoteViews 不能播放动画，只能设置静态属性，所以这里按帧驱动：
     * 每一帧用 setExact 闹钟唤醒，把按钮的 rotation 设为该帧角度并重绘。
     *
     * 角度曲线不是匀速：先快速转起来，接近一圈时略过头，再回正。
     * 用 cubic-bezier(.22,1.2,.36,1) 类的过冲曲线采样，得到 fps 约 60ms/帧，
     * 总时长约 520ms，终点精确回到 360°（视觉等价于 0°）。
     */
    static void scheduleSpin(Context context) {
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        boolean exact = canScheduleExact(context);
        long base = System.currentTimeMillis();
        float[] frames = spinFrames();
        for (int i = 0; i < frames.length; i++) {
            long when = base + spinFrameDelay(i);
            PendingIntent intent = spinIntent(context, i);
            try {
                if (exact) {
                    alarms.setExact(AlarmManager.RTC, when, intent);
                } else {
                    // 没有精确闹钟权限时退化为普通闹钟；帧会有抖动，但动画仍可播放。
                    alarms.set(AlarmManager.RTC, when, intent);
                }
            } catch (SecurityException revoked) {
                alarms.set(AlarmManager.RTC, when, intent);
                exact = false;
            }
        }
    }

    /** 动画帧数：0..FRAME_COUNT-1 覆盖整段。 */
    static final int SPIN_FRAME_COUNT = 9;

    /** 每帧相对起点的毫秒偏移。前段密集（快）、后段稀疏（缓）。 */
    static long spinFrameDelay(int frame) {
        if (frame <= 0) return 0;
        // 用幂次让帧间隔逐渐拉大，配合缓出曲线。
        double t = frame / (double) (SPIN_FRAME_COUNT - 1);
        return (long) (520 * Math.pow(t, 0.72));
    }

    /**
     * 各帧的旋转角度（度）。
     *
     * 曲线要点：快速启动 → 冲过 360° 一点点（约 372°）→ 回落到 360°。
     * 过冲量小（约 12°），回正占最后两帧，形成「转过头再回正」的手感。
     */
    static float[] spinFrames() {
        return new float[]{0f, 46f, 128f, 214f, 286f, 336f, 366f, 372f, 360f};
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
        for (int frame = 0; frame < SPIN_FRAME_COUNT; frame++) {
            alarms.cancel(spinIntent(context, frame));
        }
    }
}
