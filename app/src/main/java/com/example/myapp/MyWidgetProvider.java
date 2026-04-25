package com.example.myapp;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.widget.RemoteViews;
import androidx.core.content.ContextCompat;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class MyWidgetProvider extends AppWidgetProvider {

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Runnable updateRunnable;

    private static String[] getCycle(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("WidgetSettings", Context.MODE_PRIVATE);
        String cycleStr = preferences.getString("cycle", "");
        if (!cycleStr.isEmpty()) {
            try {
                JSONArray jsonArray = new JSONArray(cycleStr);
                String[] cycle = new String[jsonArray.length()];
                for (int i = 0; i < jsonArray.length(); i++) {
                    cycle[i] = jsonArray.getString(i);
                }
                return cycle;
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return new String[]{"白班", "夜班", "休息", "白班", "夜班", "休息", "休息", "休息"};
    }

    private static Calendar getAnchorDate(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("WidgetSettings", Context.MODE_PRIVATE);
        String anchorDateStr = preferences.getString("anchorDate", "");
        if (!anchorDateStr.isEmpty()) {
            try {
                String[] parts = anchorDateStr.split("-");
                if (parts.length == 3) {
                    int year = Integer.parseInt(parts[0]);
                    int month = Integer.parseInt(parts[1]) - 1;
                    int day = Integer.parseInt(parts[2]);
                    Calendar anchor = Calendar.getInstance();
                    anchor.set(year, month, day, 0, 0, 0);
                    anchor.set(Calendar.MILLISECOND, 0);
                    return anchor;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        Calendar anchor = Calendar.getInstance();
        anchor.set(2026, Calendar.APRIL, 18, 0, 0, 0);
        anchor.set(Calendar.MILLISECOND, 0);
        return anchor;
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        updateWidget(context, appWidgetManager, appWidgetIds);
        startUpdateTimer(context, appWidgetManager, appWidgetIds);
    }

    @Override
    public void onEnabled(Context context) {
    }

    @Override
    public void onDisabled(Context context) {
        if (updateRunnable != null) {
            handler.removeCallbacks(updateRunnable);
        }
    }

    private void startUpdateTimer(final Context context, final AppWidgetManager appWidgetManager, final int[] appWidgetIds) {
        if (updateRunnable != null) {
            handler.removeCallbacks(updateRunnable);
        }

        updateRunnable = new Runnable() {
            @Override
            public void run() {
                // 动态获取当前所有widget实例，确保新增/删除widget后更新逻辑依然正确
                ComponentName componentName = new ComponentName(context, MyWidgetProvider.class);
                int[] allWidgetIds = appWidgetManager.getAppWidgetIds(componentName);
                if (allWidgetIds != null && allWidgetIds.length > 0) {
                    updateWidget(context, appWidgetManager, allWidgetIds);
                    handler.postDelayed(this, 1000);
                }
            }
        };

        handler.post(updateRunnable);
    }

    private void updateWidget(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);

            Calendar now = Calendar.getInstance();
            Date currentDate = now.getTime();

            CharSequence todayDateCharSeq = DateFormat.format("yyyy年MM月dd日 EEEE", currentDate);
            String todayDate = todayDateCharSeq != null ? todayDateCharSeq.toString() : "";
            views.setTextViewText(R.id.today_date, todayDate);

            int cycleIndex = getCycleIndex(context, now);
            String[] cycle = getCycle(context);
            String shift = cycle[cycleIndex];

            if (shift.equals("白班")) {
                views.setTextViewText(R.id.shift_emoji, "☀️");
                views.setTextViewText(R.id.shift_name, "白班");
                views.setTextColor(R.id.shift_name, ContextCompat.getColor(context, R.color.day_color));
                views.setTextViewText(R.id.shift_time, "08:00 - 20:00");
            } else if (shift.equals("夜班")) {
                views.setTextViewText(R.id.shift_emoji, "🌙");
                views.setTextViewText(R.id.shift_name, "夜班");
                views.setTextColor(R.id.shift_name, ContextCompat.getColor(context, R.color.night_color));
                views.setTextViewText(R.id.shift_time, "20:00 - 次日08:00");
            } else {
                views.setTextViewText(R.id.shift_emoji, "🏖️");
                views.setTextViewText(R.id.shift_name, "休息");
                views.setTextColor(R.id.shift_name, ContextCompat.getColor(context, R.color.rest_color));
                views.setTextViewText(R.id.shift_time, "全天休息");
            }

            views.setTextViewText(R.id.cycle_info, "本周期第 " + (cycleIndex + 1) + " 天");

            int nextDay = calculateNextShift(context, "白班", now);
            int nextNight = calculateNextShift(context, "夜班", now);
            views.setTextViewText(R.id.next_day, nextDay + "天");
            views.setTextViewText(R.id.next_night, nextNight + "天");

            CharSequence timeCharSeq = DateFormat.format("HH:mm:ss", now);
            String time = timeCharSeq != null ? timeCharSeq.toString() : "";
            views.setTextViewText(R.id.live_clock, time);

            CountdownResult countdown = calculateCountdown(context, shift, now);
            views.setTextViewText(R.id.countdown_work, countdown.workCountdown);
            views.setTextViewText(R.id.countdown_off, countdown.offCountdown);

            // 检查countdown_label和countdown_value视图是否存在
            try {
                views.setTextViewText(R.id.countdown_label, countdown.nextEventLabel);
                views.setTextViewText(R.id.countdown_value, countdown.nextEventCountdown);
            } catch (Exception e) {
                // 如果视图不存在，忽略错误
            }

            // 设置点击小部件打开MainActivity的PendingIntent（Android 12要求指定FLAG_IMMUTABLE或FLAG_MUTABLE）
            Intent clickIntent = new Intent(context, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, clickIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            views.setOnClickPendingIntent(R.id.widget_container, pendingIntent);

            appWidgetManager.updateAppWidget(appWidgetId, views);
        }
    }

    private int getCycleIndex(Context context, Calendar date) {
        Calendar anchor = getAnchorDate(context);
        long diffMillis = date.getTimeInMillis() - anchor.getTimeInMillis();
        long diffDays = diffMillis / (1000 * 60 * 60 * 24);
        String[] cycle = getCycle(context);
        int index = (int) (diffDays % cycle.length);
        if (index < 0) {
            index += cycle.length;
        }
        return index;
    }

    private int calculateNextShift(Context context, String targetShift, Calendar from) {
        Calendar check = (Calendar) from.clone();
        check.set(Calendar.HOUR_OF_DAY, 0);
        check.set(Calendar.MINUTE, 0);
        check.set(Calendar.SECOND, 0);
        check.set(Calendar.MILLISECOND, 0);

        for (int i = 1; i <= 30; i++) {
            check.add(Calendar.DAY_OF_YEAR, 1);
            int idx = getCycleIndex(context, check);
            String[] cycle = getCycle(context);
            if (cycle[idx].equals(targetShift)) {
                return i;
            }
        }
        return -1;
    }

    private static class CountdownResult {
        String workCountdown;
        String offCountdown;
        String nextEventLabel;
        String nextEventCountdown;

        CountdownResult(String work, String off, String label, String value) {
            this.workCountdown = work;
            this.offCountdown = off;
            this.nextEventLabel = label;
            this.nextEventCountdown = value;
        }
    }

    private CountdownResult calculateCountdown(Context context, String shift, Calendar now) {
        Calendar workStart = getWorkStartTime(now, shift);
        Calendar workEnd = getWorkEndTime(now, shift);

        long nowMillis = now.getTimeInMillis();

        String workCountdown;
        String offCountdown;
        String nextEventLabel;
        String nextEventCountdown;

        if (shift.equals("休息")) {
            workCountdown = "今日休息";
            offCountdown = "今日休息";

            Calendar nextWork = findNextWorkStart(context, now);
            if (nextWork != null) {
                long diffMillis = nextWork.getTimeInMillis() - nowMillis;
                if (diffMillis > 0) {
                    nextEventLabel = "距离上班";
                    nextEventCountdown = formatCountdownHoursMinutes(diffMillis);
                } else {
                    nextEventLabel = "距离上班";
                    nextEventCountdown = "已过";
                }
            } else {
                nextEventLabel = "距离上班";
                nextEventCountdown = "N/A";
            }
        } else {
            if (nowMillis < workStart.getTimeInMillis()) {
                workCountdown = formatCountdownHoursMinutes(workStart.getTimeInMillis() - nowMillis);
            } else if (nowMillis < workEnd.getTimeInMillis()) {
                workCountdown = "已上班";
            } else {
                workCountdown = "已下班";
            }

            if (nowMillis < workEnd.getTimeInMillis()) {
                offCountdown = formatCountdownHoursMinutes(workEnd.getTimeInMillis() - nowMillis);
            } else {
                offCountdown = "已下班";
            }

            if (nowMillis < workStart.getTimeInMillis()) {
                nextEventLabel = "距离上班";
                nextEventCountdown = formatCountdownHoursMinutes(workStart.getTimeInMillis() - nowMillis);
            } else if (nowMillis < workEnd.getTimeInMillis()) {
                nextEventLabel = "距离下班";
                nextEventCountdown = formatCountdownHoursMinutes(workEnd.getTimeInMillis() - nowMillis);
            } else {
                nextEventLabel = "已下班";
                nextEventCountdown = "等待中";
            }
        }

        return new CountdownResult(workCountdown, offCountdown, nextEventLabel, nextEventCountdown);
    }

    private Calendar getWorkStartTime(Calendar now, String shift) {
        Calendar start = (Calendar) now.clone();
        if (shift.equals("白班")) {
            start.set(Calendar.HOUR_OF_DAY, 8);
            start.set(Calendar.MINUTE, 0);
            start.set(Calendar.SECOND, 0);
            start.set(Calendar.MILLISECOND, 0);
            if (now.getTimeInMillis() > start.getTimeInMillis()) {
                return start;
            }
        } else if (shift.equals("夜班")) {
            start.set(Calendar.HOUR_OF_DAY, 20);
            start.set(Calendar.MINUTE, 0);
            start.set(Calendar.SECOND, 0);
            start.set(Calendar.MILLISECOND, 0);
            if (now.getTimeInMillis() > start.getTimeInMillis()) {
                return start;
            }
        }
        return start;
    }

    private Calendar getWorkEndTime(Calendar now, String shift) {
        Calendar end = (Calendar) now.clone();
        if (shift.equals("白班")) {
            end.set(Calendar.HOUR_OF_DAY, 20);
            end.set(Calendar.MINUTE, 0);
            end.set(Calendar.SECOND, 0);
            end.set(Calendar.MILLISECOND, 0);
        } else if (shift.equals("夜班")) {
            end.add(Calendar.DAY_OF_YEAR, 1);
            end.set(Calendar.HOUR_OF_DAY, 8);
            end.set(Calendar.MINUTE, 0);
            end.set(Calendar.SECOND, 0);
            end.set(Calendar.MILLISECOND, 0);
        }
        return end;
    }

    private Calendar findNextWorkStart(Context context, Calendar from) {
        Calendar check = (Calendar) from.clone();
        check.set(Calendar.HOUR_OF_DAY, 0);
        check.set(Calendar.MINUTE, 0);
        check.set(Calendar.SECOND, 0);
        check.set(Calendar.MILLISECOND, 0);

        for (int i = 1; i <= 30; i++) {
            check.add(Calendar.DAY_OF_YEAR, 1);
            int idx = getCycleIndex(context, check);
            String[] cycle = getCycle(context);
            String s = cycle[idx];
            if (!s.equals("休息")) {
                Calendar workStart = (Calendar) check.clone();
                if (s.equals("白班")) {
                    workStart.set(Calendar.HOUR_OF_DAY, 8);
                } else if (s.equals("夜班")) {
                    workStart.set(Calendar.HOUR_OF_DAY, 20);
                }
                workStart.set(Calendar.MINUTE, 0);
                workStart.set(Calendar.SECOND, 0);
                workStart.set(Calendar.MILLISECOND, 0);
                return workStart;
            }
        }
        return null;
    }

    private String formatCountdownHoursMinutes(long ms) {
        if (ms <= 0) return "00:00:00";
        long totalSeconds = ms / 1000;
        long totalHours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", totalHours, minutes, seconds);
    }
}