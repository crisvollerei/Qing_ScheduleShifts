package com.example.myapp;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;

/** Bounded native groups; the host measures text, clocks remain live RemoteViews. */
final class WidgetRenderer {
    enum Form {
        MINIMUM(128, 128, 12, R.layout.widget_handbook_minimum_v2),
        // Keep the old provider alive, but migrate its instances to the smaller design.
        COMPACT(184, 184, 14, R.layout.widget_handbook_minimum_v2),
        WIDE(360, 128, 14, R.layout.widget_handbook_wide_v2),
        FULL(304, 320, 16, R.layout.widget_handbook_full_v2);

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
        final float width, height, inset, innerWidth, innerHeight;
        final boolean portrait, small, shortFull;

        Geometry(Form form, float width, float height, float radius) {
            this.form = form;
            this.width = Math.max(1, width);
            this.height = Math.max(1, height);
            // An actual applied safe area, not merely a typography calculation.
            inset = Math.max(form == Form.WIDE && height < 110 ? 12 : form.inset, CornerInsets.safeInset(
                    Math.min(radius, Math.min(width, height) / 2)) + 3);
            innerWidth = Math.max(1, width - inset * 2);
            innerHeight = Math.max(1, height - inset * 2);
            portrait = form != Form.WIDE || width >= 320 && height >= 104;
            small = form == Form.MINIMUM || form == Form.COMPACT;
            shortFull = form == Form.FULL && height < 280;
        }
    }

    static RemoteViews build(Context context, Form form, float width, float height, Calendar now,
            MyWidgetProvider.ShiftSlot slot, Calendar anchor, String[] cycle,
            Map<String, MyWidgetProvider.ShiftDef> defs, MyWidgetProvider.Countdown countdown,
            float radius) {
        Geometry g = new Geometry(form, width, height, radius);
        boolean darkTheme = (context.getResources().getConfiguration().uiMode & 0x30) == 0x20;
        int layout = darkTheme ? form == Form.WIDE ? R.layout.widget_handbook_wide_v2_night
                : form == Form.FULL ? R.layout.widget_handbook_full_v2_night
                : R.layout.widget_handbook_minimum_v2_night : form.layout;
        // A distinct ID forces theme reinflation, including XML colors and panel drawables.
        RemoteViews views = new RemoteViews(context.getPackageName(), layout);
        pad(context, views, R.id.card_content, g.inset);
        views.setTextViewText(R.id.today_date, new SimpleDateFormat(
                form == Form.FULL ? "M月d日 EEE" : "M/d EEE", Locale.CHINA).format(now.getTime()));
        views.setContentDescription(R.id.today_date,
                new SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA).format(now.getTime()));
        bindShift(context, views, g, slot, now);
        bindTomorrow(context, views, g, now, anchor, cycle, defs);
        if (form == Form.WIDE) {
            visible(views, R.id.portrait_position, g.portrait);
            // Only the bounded portrait bitmap is composited, never text or the live timer.
            views.setImageViewBitmap(R.id.companion_portrait, WidgetPortrait.window(context));
        } else if (form == Form.FULL) {
            views.setImageViewResource(R.id.companion_portrait, 0);
            views.setImageViewResource(R.id.companion_portrait, R.drawable.widget_companion);
            visible(views, R.id.portrait_note, !g.shortFull);
            boolean dark = (context.getResources().getConfiguration().uiMode & 0x30) == 0x20;
            views.setTextViewText(R.id.portrait_note, context.getString(dark
                    ? R.string.widget_companion_night : R.string.widget_companion_day));
            bindWeek(context, views, now, anchor, cycle, defs);
        }

        // Replace -> size/visibility -> start. Reusing a stopped host instance does not recover it.
        boolean nativeTimer = Build.VERSION.SDK_INT >= 24 && countdown.target > 0;
        views.removeAllViews(R.id.countdown_container);
        views.addView(R.id.countdown_container,
                new RemoteViews(context.getPackageName(), R.layout.widget_countdown_timer));
        visible(views, R.id.countdown_container, nativeTimer);
        visible(views, R.id.countdown_chronometer, nativeTimer);
        visible(views, R.id.countdown_text, !nativeTimer);
        bindCountdownText(context, views, countdown);
        visible(views, R.id.countdown_label, g.small ? g.height >= 120
                : g.form != Form.WIDE || g.height >= 110);
        if (g.form == Form.WIDE) pad(context, views, R.id.countdown_block, g.height < 110 ? 2 : 7);
        // API 26+ XML auto-size fits the measured bounds, including unreported host padding.
        // Older TextViews need a conservative size derived from the advertised rectangle.
        if (Build.VERSION.SDK_INT < 26) bindLegacySizes(context, views, g, slot, countdown, now);

        PendingIntent open = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        views.setOnClickPendingIntent(R.id.widget_container, open);
        startTimer(views, countdown, nativeTimer);
        return views;
    }

    private static void bindShift(Context context, RemoteViews views, Geometry g,
            MyWidgetProvider.ShiftSlot slot, Calendar now) {
        views.setTextViewText(R.id.shift_name, slot.name);
        views.setTextViewText(R.id.shift_emoji, slot.def.emoji);
        int symbol = WidgetShiftAppearance.symbol(slot.def.emoji);
        int color = WidgetShiftAppearance.color(context, slot.def.cssClass);
        views.setTextColor(R.id.shift_name, color);
        views.setTextColor(R.id.shift_emoji, color);
        visible(views, R.id.shift_emoji, symbol == 0 && !slot.def.emoji.isEmpty());
        visible(views, R.id.shift_symbol, symbol != 0);
        if (symbol != 0) {
            views.setImageViewResource(R.id.shift_symbol, 0);
            views.setImageViewResource(R.id.shift_symbol, symbol);
            views.setInt(R.id.shift_symbol, "setColorFilter", color);
        }
        views.setContentDescription(R.id.shift_emoji, slot.name);
        // 状态行只在"上班前"和"下班后"出现，用来提示还没到 / 已经过了。
        //   上班中：下面那行就是「距下班」，正在上班这件事已由它说明，不重复；
        //   休息日：时段行会写「全天休息」，状态行再说一次也是重复。
        boolean working = slot.def.isWork && !now.before(slot.start) && now.before(slot.end);
        String status = !slot.def.isWork || working ? ""
                : context.getString(now.before(slot.start)
                        ? R.string.widget_compact_status_before : R.string.widget_compact_status_after);
        String full = workRange(context, now, slot);
        String range = g.form == Form.WIDE && g.width < 300 ? nextTimeNode(context, now, slot) : full;
        views.setTextViewText(R.id.shift_status, status);
        views.setTextViewText(R.id.shift_time, range);
        // 状态为空时跳过，避免读屏念出「夜班，，20:00–08:00」这样的双逗号。
        views.setContentDescription(R.id.shift_name,
                status.isEmpty() ? slot.name + "，" + full : slot.name + "，" + status + "，" + full);
        views.setContentDescription(R.id.shift_time, full);
        visible(views, R.id.shift_status,
                !status.isEmpty() && !g.small && !(g.form == Form.WIDE && g.height < 108));
        visible(views, R.id.shift_time, !g.small);
        // 「现在」这行标签按尺寸决定去留。
        //
        // 标签能消除"裸数字是什么"的歧义，但它占 42dp 固定宽度；
        // 卡片被压到 120dp 以下时，这 42dp 会把时间本身挤到裁切边缘，
        // 得不偿失。所以：太小就回到裸时间，正常尺寸一律带标签。
        boolean showNowLabel = g.width >= 120
                && !(g.form == Form.WIDE && g.width < 400);
        visible(views, R.id.clock_label, showNowLabel);
        views.setTextViewText(R.id.clock_label, context.getString(R.string.widget_now_label));
        if (g.small) visible(views, R.id.header_ornament, g.width >= 124);
    }

    private static void bindTomorrow(Context context, RemoteViews views, Geometry g, Calendar now, Calendar anchor,
            String[] cycle, Map<String, MyWidgetProvider.ShiftDef> defs) {
        if (g.form == Form.WIDE) return;
        Calendar day = (Calendar) now.clone();
        day.add(Calendar.DAY_OF_YEAR, 1);
        String name = MyWidgetProvider.shiftNameAt(day, anchor, cycle);
        MyWidgetProvider.ShiftDef def = MyWidgetProvider.defOf(name, defs);
        String range = def.isWork ? def.startTime + "–" + (MyWidgetProvider.isCrossDay(def) ? "次日 " : "")
                + def.endTime : context.getString(R.string.widget_rest_all_day);
        views.setTextViewText(R.id.tomorrow_label, g.small ? "明日" : "明日 · "
                + new SimpleDateFormat("M/d", Locale.CHINA).format(day.getTime()));
        views.setTextViewText(R.id.tomorrow_value, def.emoji + " " + name + (g.small ? "" : " · " + range));
        views.setTextColor(R.id.tomorrow_value, WidgetShiftAppearance.color(context, def.cssClass));
        views.setContentDescription(R.id.tomorrow_value, new SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
                .format(day.getTime()) + "，" + name + "，" + range);
        visible(views, R.id.tomorrow_row, !g.shortFull && (!g.small || g.height >= 144 && g.width >= 128));
    }

    private static void bindWeek(Context context, RemoteViews views, Calendar now, Calendar anchor,
            String[] cycle, Map<String, MyWidgetProvider.ShiftDef> defs) {
        int position = MyWidgetProvider.cycleIndexOf(now, anchor, cycle) + 1;
        views.setTextViewText(R.id.cycle_info, context.getString(R.string.widget_cycle_compact, position, cycle.length));
        views.setContentDescription(R.id.cycle_info,
                context.getString(R.string.widget_cycle_position, position, cycle.length));
        views.setProgressBar(R.id.progress_bar, cycle.length, position, false);
        for (int i = 0; i < WEEK_DAYS.length; i++) {
            Calendar day = (Calendar) now.clone();
            day.add(Calendar.DAY_OF_YEAR, i + 1);
            String name = MyWidgetProvider.shiftNameAt(day, anchor, cycle);
            MyWidgetProvider.ShiftDef def = MyWidgetProvider.defOf(name, defs);
            views.setTextViewText(WEEK_DAYS[i], day.get(Calendar.DAY_OF_MONTH) + "\n" + def.emoji);
            views.setTextColor(WEEK_DAYS[i], WidgetShiftAppearance.color(context, def.cssClass));
            String range = def.isWork ? def.startTime + "–"
                    + (MyWidgetProvider.isCrossDay(def) ? "次日 " : "") + def.endTime
                    : context.getString(R.string.widget_rest_all_day);
            views.setContentDescription(WEEK_DAYS[i], new SimpleDateFormat("M月d日 EEE", Locale.CHINA)
                    .format(day.getTime()) + " " + name + "，" + range);
        }
    }

    private static void bindCountdownText(Context context, RemoteViews views, MyWidgetProvider.Countdown countdown) {
        int label = countdown.label == R.string.widget_countdown_to_work ? R.string.widget_countdown_work_compact
                : countdown.label == R.string.widget_countdown_to_off ? R.string.widget_countdown_off_compact : countdown.label;
        views.setTextViewText(R.id.countdown_label, context.getString(label));
        views.setContentDescription(R.id.countdown_label, context.getString(countdown.label));
        String detail = countdown.target == 0 ? context.getString(R.string.widget_no_upcoming)
                : new SimpleDateFormat("M/d HH:mm", Locale.CHINA).format(countdown.target) + " · " + countdown.shiftName;
        views.setTextViewText(R.id.countdown_detail, detail);
        visible(views, R.id.countdown_detail, false);
        views.setContentDescription(R.id.countdown_chronometer, detail);
        views.setContentDescription(R.id.countdown_text, detail);
    }

    private static void startTimer(RemoteViews views, MyWidgetProvider.Countdown countdown, boolean nativeTimer) {
        if (nativeTimer && Build.VERSION.SDK_INT >= 24) {
            long wall = System.currentTimeMillis();
            views.setChronometerCountDown(R.id.countdown_chronometer, true);
            views.setChronometer(R.id.countdown_chronometer, SystemClock.elapsedRealtime() + countdown.target - wall,
                    MyWidgetProvider.countdownFormat(wall, countdown.target), true);
        } else {
            if (Build.VERSION.SDK_INT >= 24) views.setChronometerCountDown(R.id.countdown_chronometer, false);
            views.setChronometer(R.id.countdown_chronometer, SystemClock.elapsedRealtime(), null, false);
            views.setTextViewText(R.id.countdown_text, countdown.target == 0 ? "—"
                    : MyWidgetProvider.formatHms(countdown.target - System.currentTimeMillis()));
        }
    }

    private static void bindLegacySizes(Context context, RemoteViews views, Geometry g,
            MyWidgetProvider.ShiftSlot slot, MyWidgetProvider.Countdown countdown, Calendar now) {
        float font = Math.min(1.3f, context.getResources().getConfiguration().fontScale);
        // Reserve 40dp for hosts that subtract undocumented padding (pre-auto-size Android).
        float width = Math.max(24, g.innerWidth - 40), height = Math.max(24, g.innerHeight - 40);
        float scale = Math.min(1.3f, Math.min(width / (g.small ? 100 : g.form == Form.WIDE ? 290 : 230),
                height / (g.small ? 100 : g.form == Form.WIDE ? 85 : 245)));
        float support = Math.max(4, 10 * scale * font);
        for (int id : new int[]{R.id.today_date, R.id.clock_label, R.id.live_clock, R.id.shift_status,
                R.id.shift_time, R.id.countdown_label}) text(views, id, support);
        float nameRoom = width * (g.form == Form.WIDE ? .38f : g.form == Form.FULL ? .5f : 1) - 29;
        text(views, R.id.shift_name, Math.max(4, Math.min(24 * scale * font,
                nameRoom / Math.max(2, Math.min(8, slot.name.length())))));
        text(views, R.id.shift_emoji, Math.max(5, Math.min(18, 18 * scale)));
        float timerRoom = width * (g.form == Form.WIDE ? (g.portrait ? .34f : .5f)
                : g.form == Form.FULL ? .5f : 1) - (g.small ? 8 : 14);
        String sample = MyWidgetProvider.formatHms(Math.max(0, countdown.target - now.getTimeInMillis()));
        float timerSize = Math.max(3, Math.min(28 * scale * font,
                timerRoom / (Math.max(8, sample.length()) * .64f)));
        text(views, R.id.countdown_chronometer, timerSize);
        text(views, R.id.countdown_text, timerSize);
        if (g.form != Form.WIDE) {
            text(views, R.id.tomorrow_label, support);
            text(views, R.id.tomorrow_value, support);
        }
        if (g.form == Form.FULL) {
            for (int id : WEEK_DAYS) text(views, id, support);
            for (int id : new int[]{R.id.week_title, R.id.cycle_info, R.id.portrait_note}) text(views, id, support);
        }
    }

    /**
     * 窄尺寸下的时段简写。
     *
     * 只写"下一次变化发生在几点"。上班前用「→ 20:00」表示还没到点；上班中用
     * 「至 08:00」表示这个班到几点结束——后者带个"至"字才有落点，单写时间会像
     * 另一个待办。已下班则直接说明状态。
     */
    private static String nextTimeNode(Context context, Calendar now, MyWidgetProvider.ShiftSlot slot) {
        if (!slot.def.isWork) return context.getString(R.string.widget_rest_all_day);
        if (now.before(slot.start)) return "→ " + slot.def.startTime;
        if (now.before(slot.end)) return "至 " + slot.def.endTime;
        return "已下班";
    }

    private static String workRange(Context context, Calendar now, MyWidgetProvider.ShiftSlot slot) {
        if (!slot.def.isWork) return context.getString(R.string.widget_rest_all_day);
        boolean previous = slot.day.get(Calendar.YEAR) != now.get(Calendar.YEAR)
                || slot.day.get(Calendar.DAY_OF_YEAR) != now.get(Calendar.DAY_OF_YEAR);
        boolean cross = MyWidgetProvider.isCrossDay(slot.def);
        return (previous ? "昨 " : "") + slot.def.startTime + "–"
                + (cross ? previous ? "今 " : "次日 " : "") + slot.def.endTime;
    }

    private static void visible(RemoteViews views, int id, boolean show) {
        views.setViewVisibility(id, show ? View.VISIBLE : View.GONE);
    }

    private static void text(RemoteViews views, int id, float dp) {
        views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_DIP, dp);
    }

    private static void pad(Context context, RemoteViews views, int id, float dp) {
        int px = Math.round(dp * context.getResources().getDisplayMetrics().density);
        views.setViewPadding(id, px, px, px, px);
    }
}
