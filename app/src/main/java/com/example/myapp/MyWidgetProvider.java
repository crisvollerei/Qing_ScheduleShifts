package com.example.myapp;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.widget.RemoteViews;
import android.widget.Toast;

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
    public static class Minimum extends MyWidgetProvider { }
    public static class Wide extends MyWidgetProvider { }
    public static class Full extends MyWidgetProvider { }

    static final Class<?>[] PROVIDERS = {Minimum.class, MyWidgetProvider.class, Wide.class, Full.class};
    static final WidgetRenderer.Form[] FORMS = {WidgetRenderer.Form.MINIMUM, WidgetRenderer.Form.COMPACT,
            WidgetRenderer.Form.WIDE, WidgetRenderer.Form.FULL};

    static int[] widgetIds(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        List<Integer> ids = new ArrayList<>();
        for (Class<?> provider : PROVIDERS) {
            for (int id : manager.getAppWidgetIds(new ComponentName(context, provider))) ids.add(id);
        }
        int[] result = new int[ids.size()];
        for (int i = 0; i < result.length; i++) result[i] = ids.get(i);
        return result;
    }

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
                    String icon = WidgetShiftAppearance.presetEmoji(def.cssClass);
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
                    } else {
                        def.startTime = null;
                        def.endTime = null;
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

    static ShiftDef defOf(String name, Map<String, ShiftDef> defs) {
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

    static Calendar readAnchor(Context context) {
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

    static int cycleIndexOf(Calendar date, Calendar anchor, String[] cycle) {
        long diff = dayNumber(date) - dayNumber(anchor);
        int index = (int) (diff % cycle.length);
        if (index < 0) index += cycle.length;
        return index;
    }

    static String shiftNameAt(Calendar date, Calendar anchor, String[] cycle) {
        return cycle[cycleIndexOf(date, anchor, cycle)];
    }

    // ==================== 跨日判定 ====================

    static boolean isCrossDay(ShiftDef def) {
        if (def == null || !def.isWork) return false;
        if (!isValidTime(def.startTime) || !isValidTime(def.endTime)) return false;
        if (def.crossDay) return true;
        return def.endTime.compareTo(def.startTime) <= 0;
    }

    /** 构造某一天的班次起止时刻；跨日班次的结束时刻落在次日。 */
    static ShiftSlot slotOf(Calendar day, String name, ShiftDef def) {
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
    static ShiftSlot nextWorkSlot(Calendar from, Calendar anchor, String[] cycle,
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
        // Disabling one composition must not stop the other installed compositions.
        refreshAll(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (WidgetRefresh.ACTION_RECOVER.equals(action)) {
            WidgetRefresh.recover(context);
            return;
        }
        if (WidgetRefresh.ACTION_REFRESH.equals(action)
                || WidgetRefresh.ACTION_SPIN_START.equals(action)) {
            WidgetRefresh.cancelLegacySpin(context);
            refreshAll(context);
            Toast.makeText(context, R.string.widget_refreshed, Toast.LENGTH_SHORT).show();
            return;
        }
        if (WidgetRefresh.ACTION_SPIN.equals(action)) {
            // Ignore animation broadcasts queued by an older installed version.
            return;
        }
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) WidgetRefresh.cancelLegacySpin(context);
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
        int[] ids = widgetIds(context);
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
        for (int i = 0; i < PROVIDERS.length; i++) {
            for (int id : manager.getAppWidgetIds(new ComponentName(context, PROVIDERS[i]))) {
                manager.updateAppWidget(id, responsiveViews(context, FORMS[i], manager.getAppWidgetOptions(id),
                        now, slot, anchor, cycle, defs, countdown));
            }
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
        Countdown countdown = countdownAt(now, slot, anchor, cycle, defs);
        // Chronometer's native string changes shape below ten hours and one hour.
        for (long threshold : new long[]{36_000_000L, 3_600_000L}) {
            long formatChange = countdown.target - threshold + 1;
            if (countdown.target > 0 && formatChange > current) next = Math.min(next, formatChange);
        }
        return next;
    }

    private static RemoteViews responsiveViews(Context context, WidgetRenderer.Form form, Bundle options, Calendar now,
            ShiftSlot slot, Calendar anchor, String[] cycle, Map<String, ShiftDef> defs,
            Countdown countdown) {
        if (Build.VERSION.SDK_INT >= 31) {
            ArrayList<SizeF> sizes = options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES);
            Map<SizeF, RemoteViews> variants = new LinkedHashMap<>();
            if (sizes != null) {
                for (SizeF size : sizes) {
                    if (!Float.isFinite(size.getWidth()) || !Float.isFinite(size.getHeight())
                            || size.getWidth() <= 0 || size.getHeight() <= 0) continue;
                    variants.put(size, buildViews(context, form, size.getWidth(), size.getHeight(),
                            now, slot, anchor, cycle, defs, countdown));
                    if (variants.size() == 16) break;
                }
            }
            if (!variants.isEmpty()) {
                // Some hosts report the cell size but measure a smaller content rectangle.
                // Let the framework choose a less decorated variant using the real bounds.
                List<SizeF> advertised = new ArrayList<>(variants.keySet());
                for (float deduction : new float[]{40, 20}) {
                    for (SizeF size : advertised) {
                        if (variants.size() >= 16) break;
                        SizeF content = new SizeF(Math.max(48, size.getWidth() - deduction),
                                Math.max(48, size.getHeight() - deduction));
                        if (!variants.containsKey(content)) variants.put(content,
                                buildViews(context, form, content.getWidth(), content.getHeight(),
                                        now, slot, anchor, cycle, defs, countdown));
                    }
                }
                return new RemoteViews(variants);
            }
        }
        // MIN_WIDTH and MAX_HEIGHT belong to portrait; MAX_WIDTH and MIN_HEIGHT to landscape.
        int minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, (int) form.width);
        int maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW);
        int minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, (int) form.height);
        int maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH);
        return new RemoteViews(
                buildViews(context, form, maxW, minH, now, slot, anchor, cycle, defs, countdown),
                buildViews(context, form, minW, maxH, now, slot, anchor, cycle, defs, countdown));
    }

    static RemoteViews buildViews(Context context, float width, float height, Calendar now,
            ShiftSlot slot, Calendar anchor, String[] cycle, Map<String, ShiftDef> defs,
            Countdown countdown) {
        return buildViews(context, WidgetRenderer.Form.COMPACT, width, height, now, slot, anchor, cycle, defs, countdown);
    }

    static RemoteViews buildViews(Context context, WidgetRenderer.Form form, float width, float height, Calendar now,
            ShiftSlot slot, Calendar anchor, String[] cycle, Map<String, ShiftDef> defs,
            Countdown countdown) {
        float radius = context.getResources().getDimension(R.dimen.widget_corner_radius)
                / context.getResources().getDisplayMetrics().density;
        return buildViews(context, form, width, height, now, slot, anchor, cycle, defs, countdown, radius);
    }

    static RemoteViews buildViews(Context context, float width, float height, Calendar now,
            ShiftSlot slot, Calendar anchor, String[] cycle, Map<String, ShiftDef> defs,
            Countdown countdown, float radius) {
        return buildViews(context, WidgetRenderer.Form.COMPACT, width, height, now, slot, anchor, cycle, defs, countdown, radius);
    }

    static RemoteViews buildViews(Context context, WidgetRenderer.Form form, float width, float height, Calendar now,
            ShiftSlot slot, Calendar anchor, String[] cycle, Map<String, ShiftDef> defs,
            Countdown countdown, float radius) {
        return WidgetRenderer.build(context, form, width, height, now, slot, anchor, cycle, defs, countdown, radius);
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

    /** Chronometer accepts one formatted time string, not separate numeric fields. */
    static String countdownFormat(long now, long target) {
        long remaining = Math.max(0, target - now);
        if (remaining < 3_600_000L) return "00:%s";
        return remaining < 36_000_000L ? "0%s" : "%s";
    }

    static String formatHms(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        return String.format(Locale.getDefault(), "%02d:%02d:%02d",
                seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }

    // ==================== 测试钩子（同包测试可见，不参与运行时逻辑） ====================

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
