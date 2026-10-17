package com.example.myapp;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 桌面小部件。
 *
 * 数据来自网页内核保存到 SharedPreferences("WidgetSettings") 的
 * cycle / anchorDate / shiftDefs，与网页的排班规则保持一致：
 * <ul>
 *   <li>周期位置按民用日期的 UTC 日序号计算，不受时区与夏令时影响；</li>
 *   <li>跨日判定与网页一致：isWork 且 (crossDay 为真 或 endTime &lt;= startTime)；</li>
 *   <li>仍在进行中的昨日跨日班次按昨日班次显示，结束时刻取该班次自己的 endTime，
 *       不再写死早上 8 点；</li>
 *   <li>“距下次某班次”按周期里实际出现的工作班次动态生成，不再写死白班/夜班。</li>
 * </ul>
 *
 * 时钟和倒计时由桌面原生控件逐秒更新；排班仅在状态边界重新计算。
 */
public class MyWidgetProvider extends AppWidgetProvider {

    private static final int API_CHRONOMETER_COUNTDOWN = 24;

    // ==================== 数据模型 ====================

    static class ShiftDef {
        String emoji = "📋";
        String cssClass = "day";
        String startTime;
        String endTime;
        boolean isWork = true;
        boolean crossDay = false;
    }

    /** 某一天的排班结果，含实际生效的起止时刻。 */
    static class ShiftSlot {
        String name;
        ShiftDef def;
        Calendar start;
        Calendar end;
        Calendar day;
    }

    private static ShiftDef defaultDef(String name) {
        boolean rest = name.contains("休") || name.contains("假");
        ShiftDef def = new ShiftDef();
        def.emoji = rest ? "🌿" : "📋";
        def.cssClass = rest ? "rest" : "day";
        def.isWork = !rest;
        if (def.isWork) {
            def.startTime = "09:00";
            def.endTime = "17:00";
        }
        return def;
    }

    private static Map<String, ShiftDef> defaultDefs() {
        Map<String, ShiftDef> defs = new LinkedHashMap<>();
        ShiftDef day = new ShiftDef();
        day.emoji = "☀️"; day.cssClass = "day"; day.startTime = "08:00"; day.endTime = "20:00"; day.isWork = true;
        defs.put("白班", day);

        ShiftDef noon = new ShiftDef();
        noon.emoji = "🌤️"; noon.cssClass = "noon"; noon.startTime = "14:00"; noon.endTime = "22:00"; noon.isWork = true;
        defs.put("中班", noon);

        ShiftDef night = new ShiftDef();
        night.emoji = "🌙"; night.cssClass = "night"; night.startTime = "20:00"; night.endTime = "08:00";
        night.isWork = true; night.crossDay = true;
        defs.put("夜班", night);

        ShiftDef rest = new ShiftDef();
        rest.emoji = "🌿"; rest.cssClass = "rest"; rest.isWork = false;
        defs.put("休息", rest);

        return defs;
    }

    // ==================== 读取网页保存的配置 ====================

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("WidgetSettings", Context.MODE_PRIVATE);
    }

    /**
     * 读取班次定义。网页只保存用户改过的班次时，其余按名称推断，
     * 与网页 schedule-core.js 的 defaultDef 规则保持一致。
     */
    static Map<String, ShiftDef> readShiftDefs(Context context) {
        String raw = prefs(context).getString("shiftDefs", "");
        Map<String, ShiftDef> result = new LinkedHashMap<>();
        if (raw != null && !raw.isEmpty()) {
            try {
                JSONObject json = new JSONObject(raw);
                Iterator<String> keys = json.keys();
                while (keys.hasNext()) {
                    String name = keys.next();
                    JSONObject obj = json.optJSONObject(name);
                    if (obj == null) continue;
                    if (!validName(name)) continue;
                    ShiftDef def = defaultDef(name);
                    String css = obj.optString("cssClass", def.cssClass);
                    def.cssClass = css.matches("day|night|rest|noon|morning|evening") ? css : def.cssClass;
                    String[] styles = {"day", "night", "rest", "noon", "morning", "evening"};
                    String[] icons = {"☀️", "🌙", "🌿", "🌤️", "🌅", "🌆"};
                    String icon = "📋";
                    for (int i = 0; i < styles.length; i++) if (styles[i].equals(def.cssClass)) icon = icons[i];
                    Object emoji = obj.opt("emoji");
                    def.emoji = emoji instanceof String && ((String) emoji).length() <= 16 ? (String) emoji : icon;
                    Object work = obj.opt("isWork");
                    if (work instanceof Boolean) def.isWork = (Boolean) work;
                    def.crossDay = def.isWork && Boolean.TRUE.equals(obj.opt("crossDay"));
                    def.startTime = optionalTime(obj, "startTime");
                    def.endTime = optionalTime(obj, "endTime");
                    if (def.isWork) {
                        if (def.startTime == null) def.startTime = "09:00";
                        if (def.endTime == null) def.endTime = "17:00";
                    }
                    result.put(name, def);
                }
                return result;
            } catch (JSONException ignored) {
                // 配置损坏时退回默认定义，小部件仍可显示。
            }
        }
        return result.isEmpty() ? defaultDefs() : result;
    }

    private static String optionalTime(JSONObject obj, String key) {
        if (obj.isNull(key)) return null;
        String value = obj.optString(key, null);
        return isValidTime(value) ? value : null;
    }

    private static boolean isValidTime(String value) {
        return value != null && value.matches("([01]\\d|2[0-3]):[0-5]\\d");
    }

    private static ShiftDef defOf(String name, Map<String, ShiftDef> defs) {
        ShiftDef def = defs.get(name);
        return def != null ? def : defaultDef(name);
    }

    static String[] readCycle(Context context) {
        String raw = prefs(context).getString("cycle", "");
        if (raw != null && !raw.isEmpty()) {
            try {
                JSONArray array = new JSONArray(raw);
                List<String> list = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    Object entry = array.opt(i);
                    if (!(entry instanceof String)) return defaultCycle();
                    String name = (String) entry;
                    if (!validName(name) || array.length() > 3660) return defaultCycle();
                    list.add(name);
                }
                if (!list.isEmpty()) return list.toArray(new String[0]);
            } catch (JSONException ignored) {
                // 退回默认周期
            }
        }
        return defaultCycle();
    }

    private static String[] defaultCycle() {
        return new String[]{"白班", "夜班", "休息", "白班", "夜班", "休息", "休息", "休息"};
    }

    private static boolean validName(String name) {
        return name != null && !name.isEmpty() && name.length() <= 40
                && !java.util.regex.Pattern.compile("[\\s\\p{Z}\\uFEFF]")
                    .matcher(name).find();
    }

    private static Calendar readAnchor(Context context) {
        Calendar anchor = parseAnchor(prefs(context).getString("anchorDate", ""));
        if (anchor != null) return anchor;
        anchor = midnight(Calendar.getInstance());
        anchor.set(2026, Calendar.APRIL, 18);
        return anchor;
    }

    static Calendar parseAnchor(String raw) {
        if (raw == null || !raw.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        String[] parts = raw.split("-");
        int year = Integer.parseInt(parts[0]);
        if (year < 1900 || year > 2100) return null;
        Calendar date = Calendar.getInstance();
        date.clear();
        date.setLenient(true);
        date.set(year, Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]));
        try {
            if (date.get(Calendar.YEAR) != year || date.get(Calendar.MONTH) != Integer.parseInt(parts[1]) - 1
                    || date.get(Calendar.DAY_OF_MONTH) != Integer.parseInt(parts[2])) return null;
            return date;
        } catch (IllegalArgumentException invalidDate) {
            return null;
        }
    }

    // ==================== 日期与周期计算 ====================

    private static Calendar midnight(Calendar source) {
        Calendar value = (Calendar) source.clone();
        value.set(Calendar.HOUR_OF_DAY, 0);
        value.set(Calendar.MINUTE, 0);
        value.set(Calendar.SECOND, 0);
        value.set(Calendar.MILLISECOND, 0);
        return value;
    }

    private static Calendar addDays(Calendar source, int days) {
        Calendar value = (Calendar) source.clone();
        value.add(Calendar.DAY_OF_YEAR, days);
        return midnight(value);
    }

    /** 民用日期的日序号；用 UTC 计算以避开夏令时与午夜边界偏差。 */
    private static long dayNumber(Calendar date) {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.clear();
        utc.set(date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
        return utc.getTimeInMillis() / 86400000L;
    }

    private static int cycleIndexOf(Calendar date, Calendar anchor, String[] cycle) {
        long diff = dayNumber(date) - dayNumber(anchor);
        int index = (int) (diff % cycle.length);
        if (index < 0) index += cycle.length;
        return index;
    }

    private static String shiftNameAt(Calendar date, Calendar anchor, String[] cycle) {
        return cycle[cycleIndexOf(date, anchor, cycle)];
    }

    // ==================== 跨日判定 ====================

    private static boolean isCrossDay(ShiftDef def) {
        if (def == null || !def.isWork) return false;
        if (!isValidTime(def.startTime) || !isValidTime(def.endTime)) return false;
        if (def.crossDay) return true;
        return def.endTime.compareTo(def.startTime) <= 0;
    }

    /** 构造某一天的班次起止时刻；跨日班次的结束时刻落在次日。 */
    private static ShiftSlot slotOf(Calendar day, String name, ShiftDef def) {
        ShiftSlot slot = new ShiftSlot();
        slot.name = name;
        slot.def = def;
        slot.day = midnight(day);
        if (def == null || !def.isWork || !isValidTime(def.startTime) || !isValidTime(def.endTime)) {
            slot.start = (Calendar) slot.day.clone();
            slot.end = addDays(slot.day, 1);
            return slot;
        }
        slot.start = (Calendar) slot.day.clone();
        applyTime(slot.start, def.startTime);
        slot.end = (Calendar) slot.day.clone();
        if (isCrossDay(def)) slot.end.add(Calendar.DAY_OF_YEAR, 1);
        applyTime(slot.end, def.endTime);
        return slot;
    }

    private static void applyTime(Calendar target, String time) {
        String[] parts = time.split(":");
        target.set(Calendar.HOUR_OF_DAY, Integer.parseInt(parts[0]));
        target.set(Calendar.MINUTE, Integer.parseInt(parts[1]));
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
    }

    /**
     * 当前实际生效的班次。
     * 若仍在昨天的跨日班次时段内，则延续昨天那一班；结束时刻取自该班次自身的 endTime。
     */
    private static ShiftSlot effectiveSlot(Calendar now, Calendar anchor, String[] cycle,
                                           Map<String, ShiftDef> defs) {
        Calendar today = midnight(now);
        Calendar yesterday = addDays(today, -1);

        String yesterdayName = shiftNameAt(yesterday, anchor, cycle);
        ShiftDef yesterdayDef = defOf(yesterdayName, defs);
        if (isCrossDay(yesterdayDef)) {
            ShiftSlot previous = slotOf(yesterday, yesterdayName, yesterdayDef);
            if (!now.before(previous.start) && now.before(previous.end)) return previous;
        }

        String todayName = shiftNameAt(today, anchor, cycle);
        return slotOf(today, todayName, defOf(todayName, defs));
    }

    /** 下一个尚未开始的工作班次；正在进行的班次由 countdownAt 单独处理。 */
    private static ShiftSlot nextWorkSlot(Calendar from, Calendar anchor, String[] cycle,
                                          Map<String, ShiftDef> defs) {
        Calendar cursor = midnight(from);
        long fromMillis = from.getTimeInMillis();
        // 周期最长 3660 天，按整个周期查找，不设固定天数上限。
        int limit = Math.min(cycle.length, 3660);
        for (int offset = 0; offset <= limit; offset++) {
            Calendar day = addDays(cursor, offset);
            String name = shiftNameAt(day, anchor, cycle);
            ShiftDef def = defOf(name, defs);
            if (!def.isWork || !isValidTime(def.startTime)) continue;
            ShiftSlot slot = slotOf(day, name, def);
            if (slot.start.getTimeInMillis() > fromMillis) return slot;
        }
        return null;
    }

    // Every layout exposes the same IDs, including both live native time widgets.
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        refreshAll(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int id, Bundle options) {
        refreshAll(context);
    }

    @Override
    public void onEnabled(Context context) {
        refreshAll(context);
    }

    @Override
    public void onDisabled(Context context) {
        WidgetRefresh.cancel(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (WidgetRefresh.ACTION_TICK.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_DATE_CHANGED.equals(action)
                || AlarmManagerAction.EXACT_PERMISSION_CHANGED.equals(action)) {
            refreshAll(context);
        }
    }

    // Keep this constant separate so older Android releases never resolve newer APIs.
    private static final class AlarmManagerAction {
        static final String EXACT_PERMISSION_CHANGED =
                "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED";
    }

    static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, MyWidgetProvider.class));
        if (ids.length == 0) {
            WidgetRefresh.cancel(context);
            return;
        }
        Calendar now = Calendar.getInstance();
        Calendar anchor = readAnchor(context);
        String[] cycle = readCycle(context);
        Map<String, ShiftDef> defs = readShiftDefs(context);
        ShiftSlot slot = effectiveSlot(now, anchor, cycle, defs);
        Countdown countdown = countdownAt(now, slot, anchor, cycle, defs);
        for (int id : ids) {
            manager.updateAppWidget(id, responsiveViews(context, manager.getAppWidgetOptions(id),
                    now, slot, anchor, cycle, defs, countdown));
        }
        // Schedule ONCE, after rendering; a generic update must not replace this boundary.
        WidgetRefresh.schedule(context, nextStateChange(now, slot, anchor, cycle, defs));
    }

    static long nextStateChange(Calendar now, ShiftSlot slot, Calendar anchor,
                                String[] cycle, Map<String, ShiftDef> defs) {
        long current = now.getTimeInMillis();
        long next = addDays(now, 1).getTimeInMillis();
        for (long event : new long[]{slot.start.getTimeInMillis(), slot.end.getTimeInMillis()}) {
            if (event > current) next = Math.min(next, event);
        }
        ShiftSlot work = nextWorkSlot(now, anchor, cycle, defs);
        if (work != null) next = Math.min(next, work.start.getTimeInMillis());
        return next;
    }

    private static RemoteViews responsiveViews(Context context, Bundle options, Calendar now,
            ShiftSlot slot, Calendar anchor, String[] cycle, Map<String, ShiftDef> defs,
            Countdown countdown) {
        if (Build.VERSION.SDK_INT >= 31) {
            ArrayList<SizeF> sizes = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);
            Map<SizeF, RemoteViews> variants = new LinkedHashMap<>();
            if (sizes != null) {
                for (SizeF size : sizes) {
                    if (size.getWidth() <= 0 || size.getHeight() <= 0) continue;
                    variants.put(size, buildViews(context, size.getWidth(), size.getHeight(),
                            now, slot, anchor, cycle, defs, countdown));
                    if (variants.size() == 16) break;
                }
            }
            if (!variants.isEmpty()) return new RemoteViews(variants);
        }
        // MIN_WIDTH and MAX_HEIGHT belong to portrait; MAX_WIDTH and MIN_HEIGHT to landscape.
        int minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110);
        int maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW);
        int minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110);
        int maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH);
        return new RemoteViews(
                buildViews(context, maxW, minH, now, slot, anchor, cycle, defs, countdown),
                buildViews(context, minW, maxH, now, slot, anchor, cycle, defs, countdown));
    }

    static int layoutFor(float width, float height) {
        if (width < 156 || height < 216) {
            return width >= 360 && width >= height * 1.2f
                    ? R.layout.widget_layout_mini_wide : R.layout.widget_layout_mini;
        }
        if (width >= 360 && width >= height * 1.2f) return R.layout.widget_layout_wide;
        if (width >= 224 && height >= 320) return R.layout.widget_layout;
        if (width >= 216) return R.layout.widget_layout_companion;
        return R.layout.widget_layout_small;
    }

    static boolean showsCompanion(float width, float height) {
        int layout = layoutFor(width, height);
        // Reserve a real side region; never fall back to a tiny avatar badge.
        return layout == R.layout.widget_layout || layout == R.layout.widget_layout_companion
                || layout == R.layout.widget_layout_wide
                || (layout == R.layout.widget_layout_mini && width >= 216)
                || layout == R.layout.widget_layout_mini_wide;
    }

    static RemoteViews buildViews(Context context, float width, float height, Calendar now,
            ShiftSlot slot, Calendar anchor, String[] cycle, Map<String, ShiftDef> defs,
            Countdown countdown) {
        int layout = layoutFor(width, height);
        boolean medium = layout == R.layout.widget_layout_companion;
        boolean small = medium || layout == R.layout.widget_layout_small;
        boolean wide = layout == R.layout.widget_layout_wide;
        boolean miniWide = layout == R.layout.widget_layout_mini_wide;
        boolean mini = miniWide || layout == R.layout.widget_layout_mini;
        boolean miniShort = mini && !miniWide && height < 128;
        boolean showCompanion = showsCompanion(width, height);
        RemoteViews views = new RemoteViews(context.getPackageName(), layout);
        // Clear ImageView's resource cache before resolving the host's day/night alias again.
        views.setImageViewResource(R.id.companion_portrait, 0);
        views.setImageViewResource(R.id.companion_portrait, R.drawable.widget_companion);
        views.setViewVisibility(R.id.companion_portrait, showCompanion ? View.VISIBLE : View.GONE);
        boolean previous = dayNumber(now) != dayNumber(slot.day);
        views.setTextViewText(R.id.shift_title, context.getString(previous
                ? R.string.widget_yesterday_shift : R.string.widget_shift_title));
        String date = new SimpleDateFormat(mini || width < 190 ? "M/d EEE" : width < 240 || wide
                ? "M月d日 EEE" : "yyyy年M月d日 EEE", Locale.CHINA).format(now.getTime());
        views.setTextViewText(R.id.today_date, date);
        views.setContentDescription(R.id.today_date,
                new SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA).format(now.getTime()));
        views.setTextViewText(R.id.shift_emoji, slot.def.emoji);
        views.setTextViewText(R.id.shift_name, slot.name);
        // Keep text colors as resource references so the host can reapply night mode.
        boolean work = slot.def.isWork;
        String workTime = work
                ? slot.def.startTime + " – " + (isCrossDay(slot.def) ? "次日 " : "") + slot.def.endTime
                : context.getString(R.string.widget_rest_all_day);
        views.setContentDescription(R.id.shift_time, (previous && work ? "昨日 " : "") + workTime);
        if ((mini || medium || (wide && width < 440)) && work) {
            workTime = (previous ? "昨" : "") + slot.def.startTime + "–"
                    + (isCrossDay(slot.def) ? (previous ? "今" : "次日") : "") + slot.def.endTime;
        } else if (previous && small && work) {
            workTime = "昨日 " + workTime;
        }
        views.setTextViewText(R.id.shift_time, workTime);
        views.setTextViewText(R.id.shift_status, context.getString(!work ? R.string.widget_status_rest
                : now.before(slot.start) ? R.string.widget_status_before
                : now.before(slot.end) ? R.string.widget_status_work : R.string.widget_status_after));
        int position = cycleIndexOf(slot.day, anchor, cycle) + 1;
        views.setTextViewText(R.id.cycle_info, context.getString(mini
                ? R.string.widget_cycle_compact : R.string.widget_cycle_position,
                position, cycle.length));
        views.setProgressBar(R.id.progress_bar, cycle.length, position, false);
        views.setContentDescription(R.id.progress_bar,
                context.getString(R.string.widget_cycle_position, position, cycle.length));
        bindUpcoming(context, views, now, anchor, cycle, defs);
        int countdownLabel = countdown.label;
        if (mini && !miniWide) {
            if (countdown.label == R.string.widget_countdown_to_work) countdownLabel = R.string.widget_countdown_work_compact;
            if (countdown.label == R.string.widget_countdown_to_off) countdownLabel = R.string.widget_countdown_off_compact;
        }
        views.setTextViewText(R.id.countdown_label, context.getString(countdownLabel));
        views.setContentDescription(R.id.countdown_label, context.getString(countdown.label));
        views.setTextViewText(R.id.countdown_detail, countdown.target == 0
                ? context.getString(R.string.widget_no_upcoming)
                : new SimpleDateFormat("M/d HH:mm", Locale.CHINA).format(countdown.target)
                    + " · " + countdown.shiftName);
        boolean nativeCountdown = Build.VERSION.SDK_INT >= API_CHRONOMETER_COUNTDOWN && countdown.target > 0;
        views.setViewVisibility(R.id.countdown_chronometer, nativeCountdown ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.countdown_text, nativeCountdown ? View.GONE : View.VISIBLE);
        if (nativeCountdown) {
            views.setChronometerCountDown(R.id.countdown_chronometer, true);
            views.setChronometer(R.id.countdown_chronometer,
                    SystemClock.elapsedRealtime() + countdown.target - System.currentTimeMillis(), null, true);
        } else {
            // 复位为正向计时再停住：countDown 是 View 实例上的持久状态，
            // 若不复位，下一轮切换到倒计时时可能沿用旧的基准而显示异常值。
            if (Build.VERSION.SDK_INT >= API_CHRONOMETER_COUNTDOWN) {
                views.setChronometerCountDown(R.id.countdown_chronometer, false);
            }
            views.setChronometer(R.id.countdown_chronometer, SystemClock.elapsedRealtime(), null, false);
            views.setTextViewText(R.id.countdown_text, countdown.target == 0 ? "—"
                    : formatHms(countdown.target - System.currentTimeMillis()));
        }
        // Bound the effective font size so large system fonts and long hour totals cannot clip.
        float fontScale = context.getResources().getConfiguration().fontScale;
        float miniWidth = mini && !miniWide && showCompanion
                ? (width - 18) * 1.8f / 2.8f + 12 : width;
        float miniColumn = (width - (showCompanion ? 40 : 28)) / (showCompanion ? 2.65f : 2);
        float column = miniWide ? miniColumn : mini ? miniWidth - 42
                : wide ? (width - 52) / 2.75f - 20
                : medium ? (width - 42) / 2.25f * 1.25f
                : small ? width - 40 : (width - 60) / 2.25f * 1.25f;
        int digits = Math.max(2, String.valueOf(Math.max(0,
                countdown.target - now.getTimeInMillis()) / 3600000L).length()) + 6;
        float timerSize = Math.min(miniWide ? 22 : miniShort ? 14 : mini ? 16 : small ? 21 : 28, column / (digits * .62f));
        textSize(views, R.id.countdown_chronometer, timerSize / fontScale);
        textSize(views, R.id.countdown_text, timerSize / fontScale);
        float clockColumn = miniWide ? miniColumn : mini ? miniWidth - 12
                : wide ? (width - 52) / 2.75f : medium ? (width - 42) / 2.25f
                : small ? width - 60 : (width - 60) / 2.25f;
        textSize(views, R.id.live_clock, Math.min(miniShort ? 20 : mini ? 24 : small ? 18 : 30, clockColumn / 4.5f) / fontScale);
        textSize(views, R.id.shift_name, (miniShort ? 18 : mini ? 22 : small ? (width >= 224 && (!medium || height >= 240) ? 26 : 22) : height >= 440 ? 40 : wide && height < 260 ? 26 : 32) / fontScale);
        textSize(views, R.id.shift_emoji, (miniWide ? 20 : mini ? 16 : small ? 18 : 20) / fontScale);
        for (int id : new int[]{R.id.today_date, R.id.shift_status, R.id.cycle_info,
                R.id.next_shifts, R.id.countdown_label}) textSize(views, id, (mini ? 9 : small ? 10 : 11) / fontScale);
        textSize(views, R.id.shift_time, (mini ? 9 : small ? 10 : 12) / fontScale);
        textSize(views, R.id.countdown_detail, (mini || small ? 9 : 10) / fontScale);
        if (wide && height < 260) views.setViewVisibility(R.id.shift_status, View.GONE);
        views.setViewVisibility(R.id.countdown_detail,
                (mini && height < (miniWide ? 144 : 216)) || (small && height < 240)
                        ? View.GONE : View.VISIBLE);
        if (mini) {
            // At 2x2, keep the live information readable instead of squeezing in every label.
            views.setViewVisibility(R.id.cycle_info, miniWide || height >= 144 ? View.VISIBLE : View.GONE);
            views.setViewVisibility(R.id.next_shifts, height >= (miniWide ? 144 : 176) ? View.VISIBLE : View.GONE);
            views.setInt(R.id.next_shifts, "setMaxLines", 1);
        }
        if (small) {
            views.setInt(R.id.next_shifts, "setMaxLines", medium && height >= 240 ? 2 : 1);
            if (medium) views.setViewVisibility(R.id.shift_status, height >= 260 ? View.VISIBLE : View.GONE);
        }
        if (layout == R.layout.widget_layout) {
            boolean showWeek = height >= 420;
            views.setViewVisibility(R.id.week_strip, showWeek ? View.VISIBLE : View.GONE);
            if (showWeek) {
                int[] days = {R.id.week_day_0, R.id.week_day_1, R.id.week_day_2,
                        R.id.week_day_3, R.id.week_day_4, R.id.week_day_5, R.id.week_day_6};
                for (int i = 0; i < days.length; i++) {
                    Calendar day = addDays(now, i + 1);
                    String name = shiftNameAt(day, anchor, cycle);
                    views.setTextViewText(days[i], day.get(Calendar.DAY_OF_MONTH) + "\n" + defOf(name, defs).emoji);
                    views.setContentDescription(days[i], new SimpleDateFormat("M月d日 EEE", Locale.CHINA)
                            .format(day.getTime()) + " " + name);
                    textSize(views, days[i], 10 / fontScale);
                }
            }
        }
        bindClick(context, views);
        PendingIntent refresh = PendingIntent.getBroadcast(context, 1002,
                new Intent(context, MyWidgetProvider.class).setAction(WidgetRefresh.ACTION_TICK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.refresh_button, refresh);
        return views;
    }

    private static void textSize(RemoteViews views, int id, float size) {
        views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, size);
    }

    private static void bindUpcoming(Context context, RemoteViews views, Calendar now,
            Calendar anchor, String[] cycle, Map<String, ShiftDef> defs) {
        List<String> names = distinctWorkShiftNames(cycle, defs);
        Map<String, Integer> upcoming = new LinkedHashMap<>();
        int current = cycleIndexOf(now, anchor, cycle);
        for (int offset = 1; offset <= cycle.length; offset++) {
            String name = cycle[(current + offset) % cycle.length];
            if (!upcoming.containsKey(name)) upcoming.put(name, offset);
        }
        List<String> parts = new ArrayList<>();
        for (String name : names.subList(0, Math.min(8, names.size()))) {
            parts.add(defOf(name, defs).emoji + " " + name + " " + upcoming.get(name) + "天");
        }
        String visible = parts.isEmpty() ? context.getString(R.string.widget_no_upcoming)
                : String.join(" · ", parts.subList(0, Math.min(2, parts.size())))
                    + (names.size() > 2 ? " · +" + (names.size() - 2) : "");
        views.setTextViewText(R.id.next_shifts, visible);
        views.setContentDescription(R.id.next_shifts, parts.isEmpty() ? visible : String.join(" · ", parts));
    }

    private static List<String> distinctWorkShiftNames(String[] cycle, Map<String, ShiftDef> defs) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String name : cycle) {
            if (defOf(name, defs).isWork) names.add(name);
        }
        return new ArrayList<>(names);
    }

    // The web's "next shift" summary starts tomorrow, never today's already-finished shift.
    private static int daysUntil(String target, Calendar now, Calendar anchor, String[] cycle) {
        for (int offset = 1; offset <= cycle.length; offset++) {
            if (shiftNameAt(addDays(now, offset), anchor, cycle).equals(target)) return offset;
        }
        return -1;
    }

    static final class Countdown {
        final int label;
        final long target;
        final String shiftName;

        Countdown(int label, long target, String shiftName) {
            this.label = label;
            this.target = target;
            this.shiftName = shiftName;
        }
    }

    static Countdown countdownAt(Calendar now, ShiftSlot slot, Calendar anchor,
                                  String[] cycle, Map<String, ShiftDef> defs) {
        if (slot.def.isWork) {
            if (now.before(slot.start)) return new Countdown(R.string.widget_countdown_to_work,
                    slot.start.getTimeInMillis(), slot.name);
            if (now.before(slot.end)) return new Countdown(R.string.widget_countdown_to_off,
                    slot.end.getTimeInMillis(), slot.name);
        }
        ShiftSlot next = nextWorkSlot(now, anchor, cycle, defs);
        return next == null ? new Countdown(R.string.widget_status_rest, 0, "")
                : new Countdown(R.string.widget_countdown_to_work, next.start.getTimeInMillis(), next.name);
    }

    private static void bindClick(Context context, RemoteViews views) {
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        views.setOnClickPendingIntent(R.id.widget_container, pending);
    }

    static String formatHms(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    // ==================== 测试钩子（同包测试可见，不参与运行时逻辑） ====================

    static int debugLayoutFor(int widthDp, int heightDp) {
        return layoutFor(widthDp, heightDp);
    }

    static Map<String, ShiftDef> debugDefaultDefs() {
        return defaultDefs();
    }

    static boolean debugIsCrossDay(ShiftDef def) {
        return isCrossDay(def);
    }

    static int debugCycleIndex(Calendar date, Calendar anchor, String[] cycle) {
        return cycleIndexOf(date, anchor, cycle);
    }

    static ShiftSlot debugEffectiveSlot(Calendar now, Calendar anchor, String[] cycle,
                                        Map<String, ShiftDef> defs) {
        return effectiveSlot(now, anchor, cycle, defs);
    }

    static Calendar debugMidnight(Calendar source) {
        return midnight(source);
    }

    static java.util.List<String> debugDistinctWorkShiftNames(String[] cycle,
                                                             Map<String, ShiftDef> defs) {
        return distinctWorkShiftNames(cycle, defs);
    }

    static int debugDaysUntil(String target, Calendar now, Calendar anchor, String[] cycle) {
        return daysUntil(target, now, anchor, cycle);
    }
}