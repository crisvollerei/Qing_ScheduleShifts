package com.example.myapp;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import java.util.Calendar;

/**
 * 为 Collection App Widget（如 ListView、GridView、StackView 等）预留的 RemoteViewsService。
 * 当前项目中未实际引用，保留以备后续扩展列表型小部件时使用。
 */
public class MyWidgetService extends RemoteViewsService {
    private static final String[] CYCLE = {"白班", "夜班", "休息", "白班", "夜班", "休息", "休息", "休息"};

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new MyWidgetRemoteViewsFactory(this.getApplicationContext(), intent);
    }

    /**
     * 使用 static 内部类，避免持有外部 Service 引用导致内存泄漏。
     */
    private static class MyWidgetRemoteViewsFactory implements RemoteViewsService.RemoteViewsFactory {
        private final Context context;
        private final int appWidgetId;

        MyWidgetRemoteViewsFactory(Context context, Intent intent) {
            this.context = context;
            this.appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        }

        @Override
        public void onCreate() {
        }

        @Override
        public void onDataSetChanged() {
        }

        @Override
        public void onDestroy() {
        }

        @Override
        public int getCount() {
            return 1;
        }

        @Override
        public RemoteViews getViewAt(int position) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_layout);

            Calendar now = Calendar.getInstance();
            int cycleIndex = getCycleIndex(now);
            String shift = CYCLE[cycleIndex];

            views.setTextViewText(R.id.shift_title, "今日班次");
            views.setTextViewText(R.id.today_date, "");
            views.setTextViewText(R.id.shift_emoji, shift.equals("白班") ? "☀️" : (shift.equals("夜班") ? "🌙" : "🏖️"));
            views.setTextViewText(R.id.shift_name, shift);
            views.setTextViewText(R.id.shift_time, shift.equals("白班") ? "08:00 - 20:00" : (shift.equals("夜班") ? "20:00 - 次日08:00" : "全天休息"));
            views.setTextViewText(R.id.cycle_info, "本周期第 " + (cycleIndex + 1) + " 天");
            views.setTextViewText(R.id.next_day, "");
            views.setTextViewText(R.id.next_night, "");
            views.setTextViewText(R.id.live_clock, "");
            views.setTextViewText(R.id.countdown_work, "");
            views.setTextViewText(R.id.countdown_off, "");

            // 检查countdown_label和countdown_value视图是否存在
            try {
                views.setTextViewText(R.id.countdown_label, "");
                views.setTextViewText(R.id.countdown_value, "");
            } catch (Exception e) {
                // 如果视图不存在，忽略错误
            }

            return views;
        }

        private int getCycleIndex(Calendar date) {
            Calendar anchor = Calendar.getInstance();
            anchor.set(2026, Calendar.APRIL, 18, 0, 0, 0);
            anchor.set(Calendar.MILLISECOND, 0);
            long diffMillis = date.getTimeInMillis() - anchor.getTimeInMillis();
            long diffDays = diffMillis / (1000 * 60 * 60 * 24);
            int index = (int) (diffDays % CYCLE.length);
            if (index < 0) {
                index += CYCLE.length;
            }
            return index;
        }

        @Override
        public RemoteViews getLoadingView() {
            return null;
        }

        @Override
        public int getViewTypeCount() {
            return 1;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }
    }
}