package com.example.myapp;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Paint;
import android.graphics.Typeface;
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
        if (WidgetRefresh.ACTION_RECOVER.equals(action)) {
            WidgetRefresh.recover(context);
            return;
        }
        if (WidgetRefresh.ACTION_SPIN_START.equals(action)) {
            // Refresh immediately; animation alarms may be delayed without exact-alarm access.
            refreshAll(context);
            WidgetRefresh.scheduleSpin(context);
            return;
        }
        if (WidgetRefresh.ACTION_SPIN.equals(action)) {
            // 转圈动画的某一帧：只改按钮旋转角度，不重算班次。
            int frame = intent.getIntExtra("frame", 0);
            applySpinFrame(context, frame);
            return;
        }
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

    /** 把某一帧的旋转角度应用到所有小部件的刷新按钮上。 */
    private static void applySpinFrame(Context context, int frame) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, MyWidgetProvider.class));
        if (ids.length == 0) return;
        float[] frames = WidgetRefresh.spinFrames();
        if (frame < 0 || frame >= frames.length) return;
        float degrees = frames[frame];
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), layoutForId(context, manager, id));
            views.setFloat(R.id.refresh_button, "setRotation", degrees);
            manager.partiallyUpdateAppWidget(id, views);
        }
    }

    /** 取该小部件当前应使用的布局，供部分更新（只改一个属性）复用。 */
    private static int layoutForId(Context context, AppWidgetManager manager, int id) {
        Bundle options = manager.getAppWidgetOptions(id);
        int minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110);
        int maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW);
        int minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110);
        int maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH);
        return layoutFor(Math.max(minW, maxW), Math.max(minH, maxH));
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
        Countdown countdown = countdownAt(now, slot, anchor, cycle, defs);
        // Chronometer's native string changes shape below ten hours and one hour.
        for (long threshold : new long[]{36_000_000L, 3_600_000L}) {
            long formatChange = countdown.target - threshold + 1;
            if (countdown.target > 0 && formatChange > current) next = Math.min(next, formatChange);
        }
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
    // ==================== 排版系统 ====================
    //
    // 六套布局共用一套排版规格：字号阶梯、间距节拍、圆角安全区。
    // 这里把「布局档位 -> 规格」集中成一张表；buildViews 只按表应用，
    // 不再在 if 分支里散落裸数字。

    /** 宿主圆角半径（Android 12+ system_app_widget_background_radius 的默认值）。 */
    static final float HOST_CORNER_RADIUS = 28f;

    /** 排版档位。 */
    enum Tier { MINI, MINI_WIDE, SMALL, COMPANION, WIDE, FULL }

    /**
     * 一档排版规格。
     *
     * 字号字段存的是设计阶梯下标，与 dimens.xml 的 widget_text_* 对齐。
     * 阶梯决定期望大小，最终大小再按本次绑定文案的实际列宽收缩。
     */
    static final class Spec {
        final Tier tier;
        /** 该档的圆角安全内边距 B（dp），与 dimens.xml 的 widget_inset_* 一致。 */
        final int inset;
        /** 底部面板内边距（dp），与 dimens 的 widget_pad_block_* 一致。 */
        final int blockPad;
        /** 进度条高度（dp），与 dimens 的 widget_progress_* 一致。 */
        final int progressHeight;
        final int dateStep, labelStep, nameStep, clockStep, timerStep, detailStep, emojiStep;
        final boolean compactCycle, compactCountdownLabel;

        Spec(Tier tier, int inset, int blockPad, int progressHeight, int dateStep, int labelStep,
             int nameStep, int clockStep, int timerStep, int detailStep, int emojiStep,
             boolean compactCycle, boolean compactCountdownLabel) {
            this.tier = tier;
            this.inset = inset;
            this.blockPad = blockPad;
            this.progressHeight = progressHeight;
            this.dateStep = dateStep;
            this.labelStep = labelStep;
            this.nameStep = nameStep;
            this.clockStep = clockStep;
            this.timerStep = timerStep;
            this.detailStep = detailStep;
            this.emojiStep = emojiStep;
            this.compactCycle = compactCycle;
            this.compactCountdownLabel = compactCountdownLabel;
        }
    }

    // 字号阶梯（sp），下标 0 起；与 dimens.xml 的 widget_text_* 一一对应。
    static final float[] DATE_LADDER = {9f, 10f, 11f, 12f};
    static final float[] LABEL_LADDER = {9f, 10f, 11f};
    static final float[] NAME_LADDER = {16f, 18f, 22f, 26f, 32f, 40f};
    static final float[] CLOCK_LADDER = {14f, 16f, 18f, 22f, 26f, 30f};
    static final float[] TIMER_LADDER = {14f, 18f, 22f, 28f, 34f};
    static final float[] DETAIL_LADDER = {8f, 9f, 10f};
    static final float[] EMOJI_LADDER = {16f, 18f, 20f};

    /**
     * 六档排版规格。
     *
     * 视觉层级（班次名 &gt; 倒计时 &gt; 实时时钟 &gt; 日期 &gt; 辅助信息）由字号落差的
     * 单调关系表达：nameStep 恒大于 clockStep，clockStep 恒不小于 dateStep，
     * dateStep 恒大于 detailStep。颜色通道由布局里的 widget_ink / widget_accent /
     * widget_muted 承担。
     */
    static final Spec SPEC_MINI = new Spec(
            Tier.MINI, 10, 4, 2, 0, 0, 0, 0, 0, 0, 0, true, true);
    // 矮宽档：横向富余、纵向紧张，所以标签不占行、与数值同行。
    // 标签字号取阶梯第 2 级（11sp）——9sp 与 26/28sp 的数值并排时对比过于悬殊，
    // 在 360dp 宽、110dp 高的卡片上会明显「标签读不清、数值抢眼」。
    static final Spec SPEC_MINI_WIDE = new Spec(
            Tier.MINI_WIDE, 10, 4, 2, 0, 2, 3, 4, 3, 1, 0, /* compactCycle */ true,
            /* compactCountdownLabel */ true);
    static final Spec SPEC_SMALL = new Spec(
            Tier.SMALL, 12, 6, 3, 1, 1, 3, 2, 2, 1, 1, false, false);
    // The square card gives its timing panel the full content width.
    static final Spec SPEC_COMPANION = new Spec(
            Tier.COMPANION, 12, 6, 3, 1, 1, 2, 2, 2, 1, 1, false, false);
    static final Spec SPEC_WIDE = new Spec(
            Tier.WIDE, 14, 10, 4, 2, 1, 4, 4, 3, 2, 2, false, false);
    static final Spec SPEC_FULL = new Spec(
            Tier.FULL, 14, 10, 4, 2, 1, 4, 4, 3, 2, 2, false, false);

    static Spec specFor(float width, float height) {
        int layout = layoutFor(width, height);
        if (layout == R.layout.widget_layout_mini_wide) return SPEC_MINI_WIDE;
        if (layout == R.layout.widget_layout_mini) {
            if (height >= 180 || (width >= 216 && height >= 144)) return new Spec(Tier.MINI, 10, 4, 2,
                    0, 0, 2, 1, 1, 0, 0, true, true);
            return SPEC_MINI;
        }
        if (layout == R.layout.widget_layout_small) return height >= 280
                ? new Spec(Tier.SMALL, 12, 6, 3, 1, 1, 4, 3, 3, 1, 1, false, false)
                : SPEC_SMALL;
        if (layout == R.layout.widget_layout_companion) return width >= 280
                ? new Spec(Tier.COMPANION, 12, 6, 3, 1, 1, 3, 2, 2, 1, 1, false, false)
                : SPEC_COMPANION;
        if (layout == R.layout.widget_layout_wide) return SPEC_WIDE;
        return height >= 420
                ? new Spec(Tier.FULL, 14, 10, 4, 3, 2, 5, 5, 4, 2, 2, false, false)
                : SPEC_FULL;
    }

    static Spec withSafeInset(Spec base, float radius) {
        int inset = Math.max(base.inset, (int) Math.ceil(CornerInsets.safeInset(radius) + 1f));
        if (inset == base.inset) return base;
        return new Spec(base.tier, inset, base.blockPad, base.progressHeight, base.dateStep,
                base.labelStep, base.nameStep, base.clockStep, base.timerStep, base.detailStep,
                base.emojiStep, base.compactCycle, base.compactCountdownLabel);
    }

    private static float ladder(float[] values, int step) {
        return values[Math.max(0, Math.min(values.length - 1, step))];
    }

    // ==================== 溢出策略：先缩短文案，再降字号 ====================

    /** 班次时段文案形态，由窄到宽。 */
    enum WorkTimeStyle { COMPACT, STANDARD }

    /** 后续排班文案形态，由窄到宽。 */
    enum UpcomingStyle { NEXT_ONLY, COMPACT, FULL }

    /**
     * 文案策略：所有「文案随宽度变化」的判断集中在这一处。
     *
     * 顺序永远是「先缩短文案，仍放不下才降字号」，因此任何一档都不会出现省略号。
     */
    static final class TextPolicy {
        final String datePattern;
        final boolean compactCycle;
        final boolean compactCountdownLabel;
        final WorkTimeStyle workTimeStyle;
        final UpcomingStyle upcomingStyle;
        final boolean showCountdownDetail;

        TextPolicy(String datePattern, boolean compactCycle, boolean compactCountdownLabel,
                   WorkTimeStyle workTimeStyle, UpcomingStyle upcomingStyle,
                   boolean showCountdownDetail) {
            this.datePattern = datePattern;
            this.compactCycle = compactCycle;
            this.compactCountdownLabel = compactCountdownLabel;
            this.workTimeStyle = workTimeStyle;
            this.upcomingStyle = upcomingStyle;
            this.showCountdownDetail = showCountdownDetail;
        }
    }

    // ---------- 列宽：与布局里的 padding / weight / 固定列宽逐项对齐 ----------

    /** mini 档伴侣立绘列宽 + 间距（与 widget_portrait_mini / widget_companion_gap_mini 一致）。 */
    private static final float MINI_PORTRAIT = 72f, MINI_PORTRAIT_GAP = 6f;
    /** mini_wide 档立绘列宽（widget_portrait_mini = 72dp）+ 间距。 */
    private static final float MINI_WIDE_PORTRAIT = 72f, MINI_WIDE_PORTRAIT_GAP = 6f;
    /** 矮宽档数据行的标签列宽（与 dimens 的 widget_data_label_mini_wide 一致）。 */
    private static final float DATA_LABEL_MINI_WIDE = 36f;
    /**
     * 宽档数字在 WidgetTimer 字体（sans-serif-light + tnum）下的字宽（em）。
     * 实测（mdpi）：100sp 时数字宽 55、冒号宽 21，8 字符 372 vs 7 字符 317，
     * 两者相差正好一个数字宽 = 0.55em。用于「冒号对齐」时补齐缺失的小时位。
     */
    private static final float DIGIT_EM = 0.55f;
    /** small / companion / full 档立绘列宽 + 间距。 */
    private static final float SMALL_PORTRAIT = 76f, SMALL_PORTRAIT_GAP = 8f;
    /** wide 档立绘列宽 + 间距（widget_portrait_wide = 88dp，widget_companion_gap_full = 12dp）。 */
    private static final float WIDE_PORTRAIT = 88f, WIDE_PORTRAIT_GAP = 12f;
    /** 首行要给刷新按钮让出的宽度（26dp 按钮 + 一段节拍）。 */
    private static final float HEADER_RESERVE = 32f;

    /** 左右两栏权重（与布局 layout_weight 一致）。 */
    /** 与布局 layout_weight 严格一致：wide 左右都是 1，右侧 1.35；mini_wide 左 1 / 右 1.35。 */
    private static final float WIDE_LEFT_WEIGHT = 1f, WIDE_RIGHT_WEIGHT = 1.35f;
    private static final float MINI_WIDE_LEFT_WEIGHT = 1f, MINI_WIDE_RIGHT_WEIGHT = 1.35f;

    /**
     * 文本列可用宽度（dp）。与布局的 padding / weight / 固定列宽逐项对齐，
     * 文案形态与字号上限都按它推导，避免「布局按卡片宽、文案按卡片宽」各算各的。
     */
    static float textColumnWidth(float width, Spec spec, boolean showCompanion) {
        switch (spec.tier) {
            case MINI:
                return width - 2 * spec.inset
                        - (showCompanion ? MINI_PORTRAIT + MINI_PORTRAIT_GAP : 0);
            case MINI_WIDE: {
                // 布局里立绘列宽 72dp，两列之间还有两个 gap（中列 marginStart + 立绘 marginStart）。
                float avail = width - 2 * spec.inset
                        - (showCompanion ? MINI_WIDE_PORTRAIT + 2 * MINI_WIDE_PORTRAIT_GAP
                                         : MINI_WIDE_PORTRAIT_GAP);
                return avail * MINI_WIDE_LEFT_WEIGHT
                        / (MINI_WIDE_LEFT_WEIGHT + MINI_WIDE_RIGHT_WEIGHT);
            }
            case SMALL:
                return width - 2 * spec.inset;
            case COMPANION:
                // Only the hero shares its row with the portrait, not the header or timing panel.
                return width - 2 * spec.inset;
            case WIDE: {
                // 立绘列自带 marginStart，右栏也自带一个 marginStart。
                float gaps = showCompanion ? 2 * WIDE_PORTRAIT_GAP : WIDE_PORTRAIT_GAP;
                float avail = width - 2 * spec.inset
                        - (showCompanion ? WIDE_PORTRAIT : 0) - gaps;
                return avail * WIDE_LEFT_WEIGHT / (WIDE_LEFT_WEIGHT + WIDE_RIGHT_WEIGHT);
            }
            default:
                return width - 2 * spec.inset;
        }
    }

    /** 右侧「此刻 / 倒计时」列可用宽度（dp）。 */
    static float clockColumnWidth(float width, Spec spec, boolean showCompanion) {
        switch (spec.tier) {
            case MINI_WIDE: {
                float avail = width - 2 * spec.inset
                        - (showCompanion ? MINI_WIDE_PORTRAIT + 2 * MINI_WIDE_PORTRAIT_GAP
                                         : MINI_WIDE_PORTRAIT_GAP);
                return avail * MINI_WIDE_RIGHT_WEIGHT
                        / (MINI_WIDE_LEFT_WEIGHT + MINI_WIDE_RIGHT_WEIGHT);
            }
            case WIDE: {
                float gaps = showCompanion ? 2 * WIDE_PORTRAIT_GAP : WIDE_PORTRAIT_GAP;
                float avail = width - 2 * spec.inset
                        - (showCompanion ? WIDE_PORTRAIT : 0) - gaps;
                return avail * WIDE_RIGHT_WEIGHT / (WIDE_LEFT_WEIGHT + WIDE_RIGHT_WEIGHT);
            }
            case SMALL:
            case FULL:
                // 底部面板横跨整幅内容宽，减去面板自身的内边距。
                return width - 2 * spec.inset - 2 * spec.blockPad;
            case COMPANION:
                return width - 2 * spec.inset - 2 * spec.blockPad;
            default:
                return textColumnWidth(width, spec, showCompanion);
        }
    }

    // ---------- 溢出策略：先缩短文案，再按实测宽度降字号 ----------

    /** Conservative width in em; size fitting always uses the bound content. */
    static float emWidth(String text) {
        if (text == null || text.isEmpty()) return 1f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(100f);
        // Measuring glyphs also handles wide Latin names and joined emoji correctly.
        return Math.max(0.1f, paint.measureText(text) / 100f * 1.04f);
    }

    /**
     * 把字号收进可用宽度 —— 这就是「先缩短文案、再降字号」里的降字号。
     *
     * @param desired 阶梯给出的目标字号（sp）
     * @param column  该控件可用列宽（dp）
     * @param text    即将显示的文字
     */
    static float fitSize(float desired, float column, String text) {
        if (column <= 0f) return desired;
        return Math.max(1f, Math.min(desired, column / emWidth(text)));
    }

    /** 等宽数字串（HH:mm:ss）按字符数估算。 */
    static float fitDigits(float desired, float column, int chars) {
        float full = 0f;
        for (int i = 0; i < chars; i++) full += 0.58f;
        return Math.max(1f, Math.min(desired, column / Math.max(0.1f, full)));
    }

    static TextPolicy textPolicy(float width, Spec spec, boolean showCompanion) {
        // Typography is kept at design size on a fixed-height widget.
        float column = textColumnWidth(width, spec, showCompanion);
        float dateColumn = (spec.tier == Tier.MINI ? column : width - 2 * spec.inset)
                - HEADER_RESERVE;
        boolean narrow = column < 104f;
        boolean medium = column < 150f;

        String datePattern;
        if (dateColumn < 100f) datePattern = "M/d EEE";
        else if (dateColumn < 180f) datePattern = "M月d日 EEE";
        else datePattern = "yyyy年M月d日 EEE";
        // 矮宽档的日期独占一整行、横向非常富余，但纵向只有一行的高度，
        // 用完整写法（yyyy年M月d日）会显得过长，反而把注意力从班次上抢走。
        if (spec.tier == Tier.MINI_WIDE) datePattern = "M月d日 EEE";

        WorkTimeStyle workTime = narrow ? WorkTimeStyle.COMPACT : WorkTimeStyle.STANDARD;

        UpcomingStyle upcoming;
        if (narrow) upcoming = UpcomingStyle.NEXT_ONLY;
        else if (medium) upcoming = UpcomingStyle.COMPACT;
        else upcoming = UpcomingStyle.FULL;

        // 倒计时副标题是最后一级辅助信息：窄列直接不显示。
        boolean detail = !narrow;
        return new TextPolicy(datePattern, spec.compactCycle, spec.compactCountdownLabel,
                workTime, upcoming, detail);
    }

    // ---------- 可见性策略 ----------

    /**
     * 单行文字的渲染高度（dp）。
     *
     * <p>实测（Robolectric mdpi + 系统默认字体 + includeFontPadding=false）：
     * <ul>
     *   <li>WidgetLabel / WidgetText（sans-serif）：约 <b>1.44 倍</b>字号；</li>
     *   <li>WidgetTimer（sans-serif-light + tnum）：约 <b>1.0 倍</b>字号。</li>
     * </ul>
     * 计时器明显更矮，早先一律按 1.6 倍估算会让「矮宽档」白算掉十几 dp，
     * 从而误撤掉本可以显示的周期进度。这里按角色分开取系数，并各留 2dp 余量。
     */
    static float rowHeight(float textSize) {
        return textSize * 1.44f + 2f;
    }

    /** 计时器类控件（WidgetTimer 样式）的行高，比普通文字矮。 */
    static float timerRowHeight(float textSize) {
        return textSize * 1.02f + 2f;
    }

    /** 一档里哪些块显示，集中成一张表。 */
    static final class Visibility {
        final boolean cycleInfo, nextShifts, shiftStatus, countdownDetail, weekStrip;
        /** 班次时段：高度不够时先撤，保证主信息不被裁。 */
        final boolean shiftTime;

        Visibility(boolean cycleInfo, boolean nextShifts, boolean shiftStatus,
                   boolean countdownDetail, boolean weekStrip) {
            this(cycleInfo, nextShifts, shiftStatus, countdownDetail, weekStrip, true);
        }

        Visibility(boolean cycleInfo, boolean nextShifts, boolean shiftStatus,
                   boolean countdownDetail, boolean weekStrip, boolean shiftTime) {
            this.cycleInfo = cycleInfo;
            this.nextShifts = nextShifts;
            this.shiftStatus = shiftStatus;
            this.countdownDetail = countdownDetail;
            this.weekStrip = weekStrip;
            this.shiftTime = shiftTime;
        }

        /** 撤掉某个可选块，用于按优先级逐级收缩。 */
        Visibility drop(int priority) {
            switch (priority) {
                case 0: return new Visibility(cycleInfo, nextShifts, shiftStatus, countdownDetail, false, shiftTime);
                case 1: return new Visibility(cycleInfo, nextShifts, shiftStatus, false, weekStrip, shiftTime);
                case 2: return new Visibility(cycleInfo, false, shiftStatus, countdownDetail, weekStrip, shiftTime);
                case 3: return new Visibility(cycleInfo, nextShifts, false, countdownDetail, weekStrip, shiftTime);
                case 4: return new Visibility(false, nextShifts, shiftStatus, countdownDetail, weekStrip, shiftTime);
                case 5: return new Visibility(cycleInfo, nextShifts, shiftStatus, countdownDetail, weekStrip, false);
                default: return this;
            }
        }

        boolean has(int priority) {
            switch (priority) {
                case 0: return weekStrip;
                case 1: return countdownDetail;
                case 2: return nextShifts;
                case 3: return shiftStatus;
                case 4: return cycleInfo;
                case 5: return shiftTime;
                default: return false;
            }
        }
    }

    /**
     * 估算当前可见块占用的总高度（dp）。
     *
     * <p>每一种档位的布局结构不同（单列 / 左右两栏 / 三栏），所以按档位分别累加：
     * 单列把行高相加，多栏取各栏的较大值。这个函数与布局 XML 的行结构一一对应，
     * 改布局时两处要一起改（{@code WidgetSafeAreaTest} 会兜底检查是否溢出）。
     */
    static float contentHeight(Visibility v, Spec spec) {
        float label = ladder(LABEL_LADDER, spec.labelStep);
        float date = ladder(DATE_LADDER, spec.dateStep);
        float name = ladder(NAME_LADDER, spec.nameStep);
        float clock = ladder(CLOCK_LADDER, spec.clockStep);
        float timer = ladder(TIMER_LADDER, spec.timerStep);
        float detail = ladder(DETAIL_LADDER, spec.detailStep);
        float nameRow = Math.max(rowHeight(name), spec.tier == Tier.MINI ? 22f : 28f);
        float dateRow = Math.max(26f, rowHeight(date));
        float clockRow = Math.max(rowHeight(label), timerRowHeight(clock));
        float timerRow = Math.max(rowHeight(label), timerRowHeight(timer));
        float gap = spec.tier == Tier.MINI || spec.tier == Tier.MINI_WIDE ? 2f
                : spec.tier == Tier.SMALL || spec.tier == Tier.COMPANION ? 3f : 4f;
        float hero = nameRow + (v.shiftStatus ? rowHeight(label) + gap : 0f)
                + (v.shiftTime ? rowHeight(detail) + gap : 0f);
        float schedule = (v.cycleInfo ? rowHeight(label) + spec.progressHeight + gap : 0f)
                + (v.nextShifts ? rowHeight(label) + gap + 2f : 0f);
        float timing = clockRow + timerRow + gap + 2f
                + (v.countdownDetail ? rowHeight(detail) + gap : 0f);
        switch (spec.tier) {
            case MINI:
                // The compact layout has no panel, and all secondary rows carry their own gaps.
                return dateRow + hero + schedule + clockRow + timerRow
                        + (v.countdownDetail ? rowHeight(detail) + 2f : 0f)
                        + (v.cycleInfo ? 4f : 0f);
            case MINI_WIDE:
                return 26f + Math.max(64f, Math.max(hero + schedule, timing));
            case WIDE: {
                float stackedTiming = 2 * rowHeight(label) + timerRowHeight(clock)
                        + timerRowHeight(timer) + 2 * gap + 12f
                        + (v.countdownDetail ? rowHeight(detail) + gap : 0f);
                return 26f + gap + Math.max(88f, Math.max(hero + schedule + 8f, stackedTiming));
            }
            case COMPANION:
                hero = Math.max(72f, hero);
                break;
            case FULL:
                hero = Math.max(80f, hero);
                if (v.weekStrip) hero += rowHeight(label) + 44f + 12f;
                break;
            default:
                break;
        }
        return dateRow + hero + schedule + timing + 2 * spec.blockPad + 3 * gap;
    }

    /**
     * 可见性策略：从「全显示」开始，按优先级逐级撤块，直到估算高度放得下。
     *
     * <p>撤除顺序（先撤的在前）：未来七天 &gt; 倒计时副标题 &gt; 后续排班 &gt; 状态 &gt; 周期
     * &gt; 班次时段。主信息（日期 / 班次名 / 实时时钟 / 倒计时）永不撤除——
     * 它们在最小档 110x110 上也一定放得下（实测约 78dp &lt; 90dp 可用高）。
     */
    static Visibility visibilityFor(float width, float height, Spec spec, TextPolicy policy) {
        float available = height - 2 * spec.inset;
        Visibility current = new Visibility(true, true, true,
                policy.showCountdownDetail, true, true);
        for (int guard = 0; guard < 8; guard++) {
            if (contentHeight(current, spec) <= available) return current;
            int priority = -1;
            for (int p = 0; p < 6; p++) {
                if (current.has(p)) { priority = p; break; }
            }
            if (priority < 0) return current;
            current = current.drop(priority);
        }
        return current;
    }

    // ==================== 渲染 ====================

    static RemoteViews buildViews(Context context, float width, float height, Calendar now,
            ShiftSlot slot, Calendar anchor, String[] cycle, Map<String, ShiftDef> defs,
            Countdown countdown) {
        float radius = context.getResources().getDimension(R.dimen.widget_corner_radius)
                / context.getResources().getDisplayMetrics().density;
        return buildViews(context, width, height, now, slot, anchor, cycle, defs, countdown, radius);
    }

    static RemoteViews buildViews(Context context, float width, float height, Calendar now,
            ShiftSlot slot, Calendar anchor, String[] cycle, Map<String, ShiftDef> defs,
            Countdown countdown, float hostRadius) {
        int layout = layoutFor(width, height);
        Spec baseSpec = specFor(width, height);
        float radius = Math.min(hostRadius, Math.min(width, height) / 2f);
        Spec spec = withSafeInset(baseSpec, radius);
        boolean showCompanion = showsCompanion(width, height);
        TextPolicy policy = textPolicy(width, spec, showCompanion);
        Visibility visible = visibilityFor(width, height, spec, policy);
        RemoteViews views = new RemoteViews(context.getPackageName(), layout);
        int extra = Math.round((spec.inset - baseSpec.inset)
                * context.getResources().getDisplayMetrics().density);
        // Root padding also moves the overlaid refresh control into the host safe area.
        views.setViewPadding(R.id.widget_container, extra, extra, extra, extra);

        // Clear ImageView's resource cache before resolving the host's day/night alias again.
        views.setImageViewResource(R.id.companion_portrait, 0);
        views.setImageViewResource(R.id.companion_portrait, R.drawable.widget_companion);
        views.setViewVisibility(R.id.companion_portrait, showCompanion ? View.VISIBLE : View.GONE);

        String date = new SimpleDateFormat(policy.datePattern, Locale.CHINA).format(now.getTime());
        float column = textColumnWidth(width, spec, showCompanion);
        float heroColumn = column - ((spec.tier == Tier.COMPANION || spec.tier == Tier.FULL)
                && showCompanion ? SMALL_PORTRAIT + SMALL_PORTRAIT_GAP : 0f);
        String workTime = bindShiftText(context, views, now, slot, policy, visible, spec, heroColumn);
        bindCycle(context, views, slot, anchor, cycle, policy, spec, column);
        if (visible.weekStrip && spec.tier == Tier.FULL) {
            bindWeekStrip(context, views, now, anchor, cycle, defs, spec);
        }
        bindUpcoming(context, views, now, anchor, cycle, defs, policy, spec,
                textColumnWidth(width, spec, showCompanion));
        bindCountdown(context, views, now, countdown, policy);
        applyTypography(context, views, width, height, spec, policy, countdown, now, date,
                slot.name, heroColumn, workTime);
        applyVisibility(views, visible, layout);
        bindClick(context, views);

        // Manual calibration must not depend on animation delivery.
        PendingIntent refresh = PendingIntent.getBroadcast(context, 1002,
                new Intent(context, MyWidgetProvider.class).setAction(WidgetRefresh.ACTION_SPIN_START),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.refresh_button, refresh);
        return views;
    }

    /**
     * 班次时段文案：从完整写法开始，逐级收短到放得下为止。
     *
     * 这是「先缩短文案」这一半策略的具体实现——绝不让 TextView 打省略号。
     * 估算用 {@link #emWidth} 与 detail 档字号，偏保守。
     */
    private static String chooseWorkTime(String full, String compact, String minimal,
            float column, float size) {
        for (String candidate : new String[]{full, compact, minimal}) {
            if (candidate == null) continue;
            if (emWidth(candidate) * size <= column) return candidate;
        }
        return minimal;
    }

    /** 班次区文案：日期 / 标题 / 图标 / 班次名 / 时段 / 状态。 */
    private static String bindShiftText(Context context, RemoteViews views, Calendar now,
            ShiftSlot slot, TextPolicy policy, Visibility visible, Spec spec, float column) {
        boolean previous = dayNumber(now) != dayNumber(slot.day);
        views.setTextViewText(R.id.shift_title, context.getString(previous
                ? R.string.widget_yesterday_shift : R.string.widget_shift_title));
        views.setTextViewText(R.id.today_date, new SimpleDateFormat(policy.datePattern,
                Locale.CHINA).format(now.getTime()));
        views.setContentDescription(R.id.today_date,
                new SimpleDateFormat("yyyy年M月d日 EEEE", Locale.CHINA).format(now.getTime()));
        views.setTextViewText(R.id.shift_emoji, slot.def.emoji);
        views.setTextViewText(R.id.shift_name, slot.name);

        // Keep text colors as resource references so the host can reapply night mode.
        boolean work = slot.def.isWork;
        boolean cross = work && isCrossDay(slot.def);
        String full = work
                ? slot.def.startTime + " – " + (cross ? "次日 " : "") + slot.def.endTime
                : context.getString(R.string.widget_rest_all_day);
        views.setContentDescription(R.id.shift_time, (previous && work ? "昨日 " : "") + full);
        // 三种写法由长到短；无论哪个档位，都取第一个真正放得下的。
        String longest = previous && work ? "昨日 " + full : full;
        String compact = work
                ? (previous ? "昨 " : "") + slot.def.startTime + "–"
                    + (cross ? (previous ? "今 " : "次日 ") : "") + slot.def.endTime
                : full;
        String minimal = work
                ? slot.def.startTime + "–" + slot.def.endTime
                : full;
        float detailSize = ladder(DETAIL_LADDER, spec.detailStep);
        String workTime = chooseWorkTime(longest, compact, minimal, column, detailSize);
        views.setTextViewText(R.id.shift_time, workTime);
        views.setTextViewText(R.id.shift_status, context.getString(!work ? R.string.widget_status_rest
                : now.before(slot.start) ? R.string.widget_status_before
                : now.before(slot.end) ? R.string.widget_status_work : R.string.widget_status_after));
        return workTime;
    }

    /** 周期区文案 + 进度条。 */
    private static void bindCycle(Context context, RemoteViews views, ShiftSlot slot,
            Calendar anchor, String[] cycle, TextPolicy policy, Spec spec, float column) {
        int position = cycleIndexOf(slot.day, anchor, cycle) + 1;
        float size = ladder(LABEL_LADDER, spec.labelStep);
        String full = context.getString(R.string.widget_cycle_position, position, cycle.length);
        String compact = context.getString(R.string.widget_cycle_compact, position, cycle.length);
        String text = policy.compactCycle || emWidth(full) * size > column ? compact : full;
        views.setTextViewText(R.id.cycle_info, text);
        textSize(views, R.id.cycle_info, fitSize(size, column, text));
        views.setProgressBar(R.id.progress_bar, cycle.length, position, false);
        views.setContentDescription(R.id.progress_bar,
                context.getString(R.string.widget_cycle_position, position, cycle.length));
    }

    /** 倒计时区文案 + 原生 Chronometer / 文本兜底。 */
    private static void bindCountdown(Context context, RemoteViews views, Calendar now,
            Countdown countdown, TextPolicy policy) {
        int label = countdown.label;
        if (policy.compactCountdownLabel) {
            if (label == R.string.widget_countdown_to_work) label = R.string.widget_countdown_work_compact;
            if (label == R.string.widget_countdown_to_off) label = R.string.widget_countdown_off_compact;
        }
        views.setTextViewText(R.id.countdown_label, context.getString(label));
        views.setContentDescription(R.id.countdown_label, context.getString(countdown.label));
        views.setTextViewText(R.id.countdown_detail, countdown.target == 0
                ? context.getString(R.string.widget_no_upcoming)
                : new SimpleDateFormat("M/d HH:mm", Locale.CHINA).format(countdown.target)
                    + " · " + countdown.shiftName);
        boolean nativeCountdown = Build.VERSION.SDK_INT >= API_CHRONOMETER_COUNTDOWN && countdown.target > 0;
        views.setViewVisibility(R.id.countdown_chronometer, nativeCountdown ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.countdown_text, nativeCountdown ? View.GONE : View.VISIBLE);
        if (nativeCountdown) {
            long wallNow = System.currentTimeMillis();
            long base = SystemClock.elapsedRealtime() + countdown.target - wallNow;
            // A host can lose the tick callback while retaining mRunning=true.
            // Stop first so reapply posts a fresh callback instead of just changing the text.
            views.setBoolean(R.id.countdown_chronometer, "setStarted", false);
            views.setChronometerCountDown(R.id.countdown_chronometer, true);
            views.setChronometer(R.id.countdown_chronometer, base,
                    countdownFormat(wallNow, countdown.target), true);
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
    }

    /** Fit each role against its actual row; fixed-height widgets use density-scaled design sizes. */
    private static void applyTypography(Context context, RemoteViews views, float width,
            float height, Spec spec, TextPolicy policy, Countdown countdown, Calendar now,
            String dateText, String shiftName, float heroColumn, String workTime) {
        boolean companion = showsCompanion(width, height);
        float column = textColumnWidth(width, spec, companion);
        float clockColumn = clockColumnWidth(width, spec, companion);
        float labelSize = ladder(LABEL_LADDER, spec.labelStep);

        // Reserve at least HH:mm:ss, including the extra hour digit at a boundary.
        long remaining = Math.max(0, countdown.target - now.getTimeInMillis());
        int timerChars = Math.min(11, Math.max(8, String.valueOf(remaining / 3600000L).length() + 6));
        int labelChars = policy.compactCountdownLabel ? 3 : 4;
        // 矮宽档的标签列宽由 dimens 固定（widget_data_label_mini_wide = 30dp），
        // 这里按「固定列宽 + 一个节拍」预留，而不是按字数估算，否则标签字号变大后数值会溢出。
        float labelReserve = spec.tier == Tier.WIDE ? 0f
                : spec.tier == Tier.MINI_WIDE ? DATA_LABEL_MINI_WIDE + 4f
                : labelChars * labelSize + 6f;
        float timerSize = fitDigits(ladder(TIMER_LADDER, spec.timerStep),
                Math.max(24f, clockColumn - labelReserve), timerChars);

        // 实时时钟：固定 8 字符 HH:mm:ss；同一行前面还有「此刻」标签（2 个字）。
        float clockReserve = spec.tier == Tier.WIDE ? 0f
                : spec.tier == Tier.MINI_WIDE ? DATA_LABEL_MINI_WIDE + 4f
                : 2f * labelSize + 6f;
        float clockSize = fitDigits(ladder(CLOCK_LADDER, spec.clockStep),
                Math.max(24f, clockColumn - clockReserve), 8);

        // 矮宽档的两行时间竖向相邻，必须同字号才能让冒号落在同一条竖线上：
        // 不同字号时同一个「数字宽」在两行里对应的像素不同，补位永远差一点。
        if (spec.tier == Tier.MINI_WIDE) {
            float shared = Math.min(timerSize, clockSize);
            timerSize = shared;
            clockSize = shared;
        }
        textSize(views, R.id.countdown_chronometer, timerSize);
        textSize(views, R.id.countdown_text, timerSize);
        // Both paths now render HH:mm:ss; clear padding retained by reused host views.
        views.setViewPadding(R.id.countdown_chronometer, 0, 0, 0, 0);
        views.setViewPadding(R.id.countdown_text, 0, 0, 0, 0);

        textSize(views, R.id.live_clock, clockSize);

        float emojiBox = spec.tier == Tier.MINI || spec.tier == Tier.MINI_WIDE ? 22f
                : spec.tier == Tier.SMALL || spec.tier == Tier.COMPANION ? 24f : 28f;
        float nameGap = spec.tier == Tier.MINI || spec.tier == Tier.MINI_WIDE ? 4f
                : spec.tier == Tier.SMALL || spec.tier == Tier.COMPANION ? 5f : 6f;
        textSize(views, R.id.shift_name, fitSize(ladder(NAME_LADDER, spec.nameStep),
                Math.max(1f, heroColumn - emojiBox - nameGap - 2f), shiftName));
        textSize(views, R.id.shift_emoji, ladder(EMOJI_LADDER, spec.emojiStep));

        float dateSize = ladder(DATE_LADDER, spec.dateStep);
        float detailSize = ladder(DETAIL_LADDER, spec.detailStep);
        textSize(views, R.id.shift_status, labelSize);
        textSize(views, R.id.clock_label, labelSize);
        textSize(views, R.id.countdown_label, labelSize);
        float dateColumn = (spec.tier == Tier.MINI ? column : width - 2 * spec.inset)
                - HEADER_RESERVE;
        textSize(views, R.id.today_date,
                fitSize(dateSize, Math.max(1f, dateColumn), dateText));
        textSize(views, R.id.shift_time,
                fitSize(detailSize, heroColumn, workTime));
        textSize(views, R.id.countdown_detail,
                fitSize(detailSize, clockColumn, countdown.target == 0
                        ? context.getString(R.string.widget_no_upcoming)
                        : new SimpleDateFormat("M/d HH:mm", Locale.CHINA).format(countdown.target)
                            + " · " + countdown.shiftName));
        if (spec.tier == Tier.FULL) {
            textSize(views, R.id.week_title, labelSize);
            for (int id : WEEK_DAYS) textSize(views, id, labelSize);
        }
    }

    private static void textSize(RemoteViews views, int id, float size) {
        views.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_DIP, size);
    }

    /** 按策略设置可见性；布局里写死的默认值也在这里统一覆盖。 */
    private static void applyVisibility(RemoteViews views, Visibility visible, int layout) {
        // 周期文案与进度条是一组信息：只留进度条会变成一条没有含义的色带。
        views.setViewVisibility(R.id.cycle_info, visible.cycleInfo ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.progress_bar, visible.cycleInfo ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.next_shifts, visible.nextShifts ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.shift_status, visible.shiftStatus ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.shift_time, visible.shiftTime ? View.VISIBLE : View.GONE);
        views.setViewVisibility(R.id.countdown_detail,
                visible.countdownDetail ? View.VISIBLE : View.GONE);
        if (layout != R.layout.widget_layout_mini) {
            boolean schedule = visible.cycleInfo || visible.nextShifts;
            views.setViewVisibility(R.id.schedule_section, schedule ? View.VISIBLE : View.GONE);
            views.setViewVisibility(R.id.spring_2, schedule ? View.VISIBLE : View.GONE);
        }
        if (layout == R.layout.widget_layout) {
            views.setViewVisibility(R.id.week_strip, visible.weekStrip ? View.VISIBLE : View.GONE);
        }
    }

    /** 「未来七天」的七个日期格。 */
    static final int[] WEEK_DAYS = {R.id.week_day_0, R.id.week_day_1, R.id.week_day_2,
            R.id.week_day_3, R.id.week_day_4, R.id.week_day_5, R.id.week_day_6};

    /** 「未来七天」每一格：上面是日号，下面是班次图标。 */
    private static void bindWeekStrip(Context context, RemoteViews views, Calendar now,
            Calendar anchor, String[] cycle, Map<String, ShiftDef> defs, Spec spec) {
        for (int i = 0; i < WEEK_DAYS.length; i++) {
            Calendar day = addDays(now, i + 1);
            String name = shiftNameAt(day, anchor, cycle);
            views.setTextViewText(WEEK_DAYS[i],
                    day.get(Calendar.DAY_OF_MONTH) + "\n" + defOf(name, defs).emoji);
            views.setContentDescription(WEEK_DAYS[i],
                    new SimpleDateFormat("M月d日 EEE", Locale.CHINA).format(day.getTime()) + " " + name);
        }
    }

    /**
     * 后续排班文案。
     *
     * <p>宽度不够时按 {@link UpcomingStyle} 逐级收短，而不是让 TextView 打省略号：
     * FULL 两个班次完整写法 -> COMPACT 单行两个班次 -> NEXT_ONLY 只给最近的班次。
     * contentDescription 始终保留完整信息，无障碍读屏不受影响。
     */
    private static void bindUpcoming(Context context, RemoteViews views, Calendar now,
            Calendar anchor, String[] cycle, Map<String, ShiftDef> defs, TextPolicy policy,
            Spec spec, float column) {
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
        String visible;
        if (parts.isEmpty()) {
            visible = context.getString(R.string.widget_no_upcoming);
        } else if (policy.upcomingStyle == UpcomingStyle.NEXT_ONLY) {
            String first = names.get(0);
            visible = first + " " + upcoming.get(first) + "天后";
        } else {
            visible = String.join(" · ", parts.subList(0, Math.min(2, parts.size())))
                    + (names.size() > 2 ? " · +" + (names.size() - 2) : "");
        }
        views.setTextViewText(R.id.next_shifts, visible);
        views.setContentDescription(R.id.next_shifts,
                parts.isEmpty() ? visible : String.join(" · ", parts));
        // 这条文案的单行长度随班次名与天数变化，字号必须按**这一条实际的字符串**收，
        // 否则在 240dp 宽 / 1.3 倍字号这类组合上会被省略号截断。
        float labelSize = ladder(LABEL_LADDER, spec.labelStep);
        textSize(views, R.id.next_shifts, fitSize(labelSize, column, visible));
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

    static float debugRowHeight(float textSize) {
        return rowHeight(textSize);
    }

    static float debugContentHeight(Visibility visible, Spec spec) {
        return contentHeight(visible, spec);
    }
}
