package com.example.myapp;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;

/** Fixed forms with proportional anchors/bands and a uniform scale for their contents. */
final class WidgetRenderer {
    enum Form {
        MINIMUM(128, 128, 12, R.layout.widget_fixed_minimum),
        COMPACT(184, 184, 16, R.layout.widget_fixed_compact),
        WIDE(360, 128, 16, R.layout.widget_proportional_wide),
        FULL(304, 320, 20, R.layout.widget_proportional_full);

        final float width, height, inset;
        final int layout;
        Form(float width, float height, float inset, int layout) {
            this.width = width; this.height = height; this.inset = inset; this.layout = layout;
        }
    }

    static final int[] WEEK_DAYS = {R.id.week_day_0, R.id.week_day_1, R.id.week_day_2,
            R.id.week_day_3, R.id.week_day_4, R.id.week_day_5, R.id.week_day_6};

    private WidgetRenderer() { }

    static final class Geometry {
        final Form form;
        final float width, height, edge, scale, support, name, timer, emoji, column;

        Geometry(Form form, float width, float height, float radius) {
            this.form = form; this.width = width; this.height = height;
            float safe = CornerInsets.safeInset(Math.min(radius, Math.min(width, height) / 2)) + 1;
            // Reserve any extra host corner inset before applying the same fit to every group.
            edge = Math.max(0, Math.max((safe - form.inset * width / form.width) / (1 - 2 * form.inset / form.width),
                    (safe - form.inset * height / form.height) / (1 - 2 * form.inset / form.height)));
            scale = Math.max(0.01f, Math.min((width - 2 * edge) / form.width, (height - 2 * edge) / form.height));
            support = (form == Form.MINIMUM ? 10 : 12) * scale;
            name = (form == Form.MINIMUM ? 18 : form == Form.FULL ? 28 : 24) * scale;
            timer = (form == Form.MINIMUM ? 20 : form == Form.FULL ? 32 : 28) * scale;
            emoji = (form == Form.MINIMUM ? 14 : 18) * scale;
            column = form == Form.FULL ? (width - 2 * edge) * 264 / form.width
                    : (form == Form.MINIMUM ? 104 : form == Form.WIDE ? 132 : 152) * scale;
        }

        float[] box(float x, float y, float w, float h) {
            float cx = edge + (x + w / 2) / form.width * (width - 2 * edge);
            float cy = edge + (y + h / 2) / form.height * (height - 2 * edge);
            return new float[]{cx - w * scale / 2, cy - h * scale / 2, w * scale, h * scale};
        }

        void group(Context context, RemoteViews views, int id, int sizeId, float w, float h) {
            // Weighted XML cells locate centers using the measured parent, not launcher estimates.
            pad(context, views, id, edge, edge, edge, edge);
            // Full-card rows fill proportional bands. Their contents and heights stay bounded.
            boolean band = form == Form.FULL && sizeId != R.id.portrait_size;
            size(context, views, sizeId, band ? -1 : w * scale, h * scale);
        }

        void apply(Context context, RemoteViews views) {
            switch (form) {
                case MINIMUM:
                    group(context, views, R.id.header_position, R.id.header_size, 104, 14);
                    group(context, views, R.id.hero_position, R.id.hero_size, 104, 26);
                    group(context, views, R.id.countdown_position, R.id.countdown_size, 104, 38);
                    group(context, views, R.id.clock_position, R.id.clock_size, 104, 14);
                    break;
                case COMPACT:
                    group(context, views, R.id.header_position, R.id.header_size, 152, 18);
                    group(context, views, R.id.hero_position, R.id.hero_size, 152, 36);
                    group(context, views, R.id.countdown_position, R.id.countdown_size, 152, 52);
                    group(context, views, R.id.clock_position, R.id.clock_size, 152, 18);
                    break;
                case WIDE:
                    group(context, views, R.id.header_position, R.id.header_size, 124, 18);
                    group(context, views, R.id.clock_position, R.id.clock_size, 132, 18);
                    group(context, views, R.id.hero_position, R.id.hero_size, 124, 56);
                    group(context, views, R.id.countdown_position, R.id.countdown_size, 132, 56);
                    group(context, views, R.id.divider_position, R.id.divider_size, 1, 48);
                    group(context, views, R.id.portrait_position, R.id.portrait_size, 48, 48);
                    break;
                case FULL:
                    group(context, views, R.id.header_position, R.id.header_size, 264, 42);
                    group(context, views, R.id.hero_position, R.id.hero_size, 264, 80);
                    group(context, views, R.id.countdown_position, R.id.countdown_size, 264, 58);
                    group(context, views, R.id.week_position, R.id.week_size, 264, 58);
                    group(context, views, R.id.portrait_position, R.id.portrait_size, 80, 80);
                    break;
            }
            float side = Math.max(48, 48 * scale);
            side = Math.min(side, Math.min(width, height));
            pad(context, views, R.id.card_header, 0, 0,
                    (form == Form.COMPACT ? 40 : form == Form.FULL ? 44 : 0) * scale, 0);
            float[] face = box(form.width - 42, 12, 26, 26);
            float cx = Math.max(side / 2, Math.min(width - side / 2, face[0] + face[2] / 2));
            float cy = Math.max(side / 2, Math.min(height - side / 2, face[1] + face[3] / 2));
            pad(context, views, R.id.refresh_position, 0, cy - side / 2, width - cx - side / 2, 0);
            size(context, views, R.id.refresh_size, 26 * scale, 26 * scale);
            int hitSize = px(context, side), faceSize = px(context, 26 * scale);
            int start = (hitSize - faceSize) / 2, end = hitSize - faceSize - start;
            views.setViewPadding(R.id.refresh_touch_target, start, start, end, end);
            pad(context, views, R.id.refresh_button, 5 * scale, 5 * scale, 5 * scale, 5 * scale);
        }
    }

    static RemoteViews build(Context context, Form form, float width, float height, Calendar now,
            MyWidgetProvider.ShiftSlot slot, Calendar anchor, String[] cycle,
            Map<String, MyWidgetProvider.ShiftDef> defs, MyWidgetProvider.Countdown countdown,
            float radius) {
        Geometry g = new Geometry(form, width, height, radius);
        RemoteViews views = new RemoteViews(context.getPackageName(), form.layout);
        g.apply(context, views);

        String date = new SimpleDateFormat(form == Form.MINIMUM ? "M/d" : form == Form.FULL ? "M月d日 EEE" : "M/d EEE",
                Locale.CHINA).format(now.getTime());
        views.setTextViewText(R.id.today_date, date);
        views.setContentDescription(R.id.today_date,
                new SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA).format(now.getTime()));
        views.setTextViewText(R.id.shift_name, slot.name);
        views.setTextViewText(R.id.shift_emoji, slot.def.emoji);
        String status = context.getString(!slot.def.isWork ? R.string.widget_compact_status_rest
                : now.before(slot.start) ? R.string.widget_compact_status_before
                : now.before(slot.end) ? R.string.widget_compact_status_work
                : R.string.widget_compact_status_after);
        String range = workRange(context, now, slot);
        views.setTextViewText(R.id.shift_status, status);
        views.setContentDescription(R.id.shift_name, slot.name + "，" + status + "，" + range);
        views.setContentDescription(R.id.shift_time, range);

        for (int id : new int[]{R.id.today_date, R.id.clock_label, R.id.live_clock,
                R.id.shift_status, R.id.shift_time, R.id.countdown_label, R.id.countdown_detail}) {
            text(views, id, g.support);
        }
        text(views, R.id.shift_name, g.name);
        text(views, R.id.shift_emoji, g.emoji);
        float heroWidth = (form == Form.MINIMUM ? 56 : form == Form.FULL ? 172 : form == Form.WIDE ? 124 : 152) * g.scale;
        boolean showStatus = form == Form.COMPACT || form == Form.WIDE;
        String rangeLine = showStatus ? range : status + " · " + range;
        if (measure(rangeLine, g.support, false) > heroWidth) rangeLine = range;
        views.setTextViewText(R.id.shift_time, rangeLine);
        float iconWidth = (form == Form.MINIMUM ? 20 : 28) * g.scale;
        float statusWidth = 42 * g.scale;
        views.setViewVisibility(R.id.shift_status, showStatus ? View.VISIBLE : View.GONE);
        views.setInt(R.id.shift_name, "setMaxWidth", px(context,
                Math.max(g.name, heroWidth - iconWidth - (showStatus ? statusWidth : 0))));
        views.setInt(R.id.shift_emoji, "setMaxWidth", px(context, (form == Form.MINIMUM ? 20 : 24) * g.scale));
        pad(context, views, R.id.shift_name, (form == Form.MINIMUM ? 0 : 4) * g.scale, 0, 0, 0);
        pad(context, views, R.id.shift_status, 6 * g.scale, 0, 0, 0);
        pad(context, views, R.id.live_clock, 4 * g.scale, 0, 0, 0);
        pad(context, views, R.id.countdown_detail, 12 * g.scale, 0, 0, 0);

        bindCountdown(context, views, countdown);
        long remaining = Math.max(0, countdown.target - now.getTimeInMillis());
        int hours = Math.max(2, String.valueOf(remaining / 3600000).length());
        StringBuilder sample = new StringBuilder();
        for (int i = 0; i < hours; i++) sample.append('8');
        sample.append(":88:88");
        float timer = Math.min(g.timer, g.timer * (g.column - 1) / measure(sample.toString(), g.timer, true));
        text(views, R.id.countdown_chronometer, timer);
        text(views, R.id.countdown_text, timer);
        views.setViewPadding(R.id.countdown_chronometer, 0, 0, 0, 0);

        if (g.form == Form.WIDE || g.form == Form.FULL) {
            views.setImageViewResource(R.id.companion_portrait, 0);
            views.setImageViewResource(R.id.companion_portrait, R.drawable.widget_companion);
        }
        if (g.form == Form.FULL) {
            text(views, R.id.week_title, g.support);
            pad(context, views, R.id.progress_position, 6 * g.scale, 7.5f * g.scale, 0, 7.5f * g.scale);
            int position = MyWidgetProvider.cycleIndexOf(slot.day, anchor, cycle) + 1;
            String cycleText = context.getString(R.string.widget_cycle_compact, position, cycle.length);
            views.setTextViewText(R.id.cycle_info, cycleText);
            text(views, R.id.cycle_info, 12 * g.scale);
            views.setInt(R.id.cycle_info, "setMaxWidth", px(context, 140 * g.scale));
            views.setContentDescription(R.id.cycle_info,
                    context.getString(R.string.widget_cycle_position, position, cycle.length));
            views.setProgressBar(R.id.progress_bar, cycle.length, position, false);
            for (int i = 0; i < WEEK_DAYS.length; i++) {
                Calendar day = (Calendar) now.clone();
                day.add(Calendar.DAY_OF_YEAR, i + 1);
                String name = MyWidgetProvider.shiftNameAt(day, anchor, cycle);
                MyWidgetProvider.ShiftDef def = MyWidgetProvider.defOf(name, defs);
                views.setTextViewText(WEEK_DAYS[i], day.get(Calendar.DAY_OF_MONTH) + "\n" + def.emoji);
                views.setContentDescription(WEEK_DAYS[i],
                        new SimpleDateFormat("M月d日 EEE", Locale.CHINA).format(day.getTime()) + " " + name);
                text(views, WEEK_DAYS[i], 12 * g.scale);
            }
        }
        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        views.setOnClickPendingIntent(R.id.widget_container, open);
        PendingIntent refresh = PendingIntent.getBroadcast(context, 1002,
                new Intent(context, MyWidgetProvider.class).setAction(WidgetRefresh.ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        views.setOnClickPendingIntent(R.id.refresh_touch_target, refresh);
        views.setOnClickPendingIntent(R.id.refresh_button, refresh);
        return views;
    }

    private static String workRange(Context context, Calendar now, MyWidgetProvider.ShiftSlot slot) {
        if (!slot.def.isWork) return context.getString(R.string.widget_rest_all_day);
        boolean previous = slot.day.get(Calendar.YEAR) != now.get(Calendar.YEAR)
                || slot.day.get(Calendar.DAY_OF_YEAR) != now.get(Calendar.DAY_OF_YEAR);
        boolean cross = MyWidgetProvider.isCrossDay(slot.def);
        return (previous ? "昨 " : "") + slot.def.startTime + "–"
                + (cross ? previous ? "今 " : "次日 " : "") + slot.def.endTime;
    }

    private static void bindCountdown(Context context, RemoteViews views, MyWidgetProvider.Countdown countdown) {
        int label = countdown.label == R.string.widget_countdown_to_work ? R.string.widget_countdown_work_compact
                : countdown.label == R.string.widget_countdown_to_off ? R.string.widget_countdown_off_compact
                : countdown.label;
        views.setTextViewText(R.id.countdown_label, context.getString(label));
        views.setContentDescription(R.id.countdown_label, context.getString(countdown.label));
        String detail = countdown.target == 0 ? context.getString(R.string.widget_no_upcoming)
                : new SimpleDateFormat("M/d HH:mm", Locale.CHINA).format(countdown.target) + " · " + countdown.shiftName;
        views.setTextViewText(R.id.countdown_detail, detail);
        views.setContentDescription(R.id.countdown_chronometer, detail);
        boolean nativeTimer = Build.VERSION.SDK_INT >= 24 && countdown.target > 0;
        // Do not recycle the Chronometer: a reused timer can retain a lost callback or visibility state.
        views.removeAllViews(R.id.countdown_container);
        views.addView(R.id.countdown_container,
                new RemoteViews(context.getPackageName(), R.layout.widget_countdown_timer));
        views.setViewVisibility(R.id.countdown_container, nativeTimer ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.countdown_chronometer, nativeTimer ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.countdown_text, nativeTimer ? View.GONE : View.VISIBLE);
        if (nativeTimer) {
            long wall = System.currentTimeMillis();
            views.setChronometerCountDown(R.id.countdown_chronometer, true);
            views.setChronometer(R.id.countdown_chronometer,
                    SystemClock.elapsedRealtime() + countdown.target - wall,
                    MyWidgetProvider.countdownFormat(wall, countdown.target), true);
        } else {
            if (Build.VERSION.SDK_INT >= 24) views.setChronometerCountDown(R.id.countdown_chronometer, false);
            views.setChronometer(R.id.countdown_chronometer, SystemClock.elapsedRealtime(), null, false);
            views.setTextViewText(R.id.countdown_text, countdown.target == 0 ? "—"
                    : MyWidgetProvider.formatHms(countdown.target - System.currentTimeMillis()));
        }
    }

    private static void text(RemoteViews views, int id, float size) {
        views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_DIP, size);
    }

    private static void size(Context context, RemoteViews views, int id, float width, float height) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (width >= 0) views.setViewLayoutWidth(id, width, TypedValue.COMPLEX_UNIT_DIP);
            views.setViewLayoutHeight(id, height, TypedValue.COMPLEX_UNIT_DIP);
        } else {
            // TextView sizing is remotable on old Android too; siblings align to this invisible box.
            if (width >= 0) views.setInt(id, "setWidth", px(context, width));
            views.setInt(id, "setHeight", px(context, height));
        }
    }

    private static float measure(String value, float size, boolean medium) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        paint.setTextSize(size);
        paint.setFontFeatureSettings("tnum");
        return paint.measureText(value);
    }

    private static int px(Context context, float dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    private static void pad(Context context, RemoteViews views, int id, float l, float t, float r, float b) {
        views.setViewPadding(id, px(context, l), px(context, t), px(context, r), px(context, b));
    }
}
