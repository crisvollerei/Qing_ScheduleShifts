package com.example.myapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 小部件排班计算的回归测试。
 *
 * 重点覆盖旧实现里写死逻辑导致的缺陷：
 * 1. 跨日班次的结束时刻必须取班次自己的 endTime，而不是固定的早上 8 点；
 * 2. 周期位置按民用日期计算，凌晨延续昨日班次时不跳位；
 * 3. “距下次班次”为任意自定义班次工作，不再只认白班/夜班。
 */
public class WidgetScheduleTest {

    private static Map<String, MyWidgetProvider.ShiftDef> defs() {
        return MyWidgetProvider.debugDefaultDefs();
    }

    private static Calendar at(int year, int month, int day, int hour, int minute) {
        Calendar value = Calendar.getInstance();
        value.set(year, month - 1, day, hour, minute, 0);
        value.set(Calendar.MILLISECOND, 0);
        return value;
    }

    private static final String[] CYCLE = {"白班", "夜班", "休息", "白班", "夜班", "休息", "休息", "休息"};

    private static Calendar anchor() {
        return MyWidgetProvider.debugMidnight(at(2026, 4, 18, 0, 0));
    }

    // ---------- 跨日判定 ----------

    @Test
    public void crossDayIsDetectedWhenEndIsNotAfterStart() {
        assertTrue(MyWidgetProvider.debugIsCrossDay(defs().get("夜班")));
        assertFalse(MyWidgetProvider.debugIsCrossDay(defs().get("白班")));
        assertFalse(MyWidgetProvider.debugIsCrossDay(defs().get("休息")));
    }

    @Test
    public void explicitCrossDayFlagForcesOvernightEvenWithLaterEndTime() {
        MyWidgetProvider.ShiftDef def = new MyWidgetProvider.ShiftDef();
        def.isWork = true;
        def.startTime = "09:00";
        def.endTime = "17:00";
        def.crossDay = true;
        assertTrue(MyWidgetProvider.debugIsCrossDay(def));
    }

    // ---------- 周期计算 ----------

    @Test
    public void cycleIndexIgnoresTimeOfDayAndSupportsDatesBeforeAnchor() {
        Map<String, MyWidgetProvider.ShiftDef> d = defs();
        Calendar a = anchor();
        // 周期长度 8：锚定日 -1 天应回绕到第 7 天（索引 7），而不是负数或 0。
        assertEquals(0, MyWidgetProvider.debugCycleIndex(at(2026, 4, 18, 23, 59), a, CYCLE));
        assertEquals(7, MyWidgetProvider.debugCycleIndex(at(2026, 4, 17, 12, 0), a, CYCLE));
        assertEquals(0, MyWidgetProvider.debugCycleIndex(at(2026, 4, 26, 1, 0), a, CYCLE));
        // 与网页 schedule-core.js 的公式一致：((diff % len) + len) % len
        assertEquals(7, MyWidgetProvider.debugCycleIndex(at(2026, 4, 17, 0, 0), a, CYCLE));
    }

    // ---------- 核心缺陷：跨日班次结束时刻 ----------

    /**
     * 旧实现把“昨日跨日班次是否还在进行”写死为“当前时间早于今天 08:00”。
     * 自定义成 22:00-06:00 的夜班在 06:30 应已下班，旧逻辑却仍认为在上班。
     */
    @Test
    public void overnightShiftEndsAtItsOwnEndTimeNotAHardCodedHour() {
        Map<String, MyWidgetProvider.ShiftDef> d = new LinkedHashMap<>();
        MyWidgetProvider.ShiftDef night = new MyWidgetProvider.ShiftDef();
        night.emoji = "🌙";
        night.cssClass = "night";
        night.startTime = "22:00";
        night.endTime = "06:00";
        night.isWork = true;
        night.crossDay = true;
        d.put("夜班", night);
        MyWidgetProvider.ShiftDef rest = new MyWidgetProvider.ShiftDef();
        rest.emoji = "🌿";
        rest.cssClass = "rest";
        rest.isWork = false;
        d.put("休息", rest);

        String[] cycle = {"夜班", "休息"};
        Calendar a = MyWidgetProvider.debugMidnight(at(2026, 4, 18, 0, 0));

        // 4/19 01:00 —— 仍在 4/18 的夜班里
        MyWidgetProvider.ShiftSlot during = MyWidgetProvider.debugEffectiveSlot(at(2026, 4, 19, 1, 0), a, cycle, d);
        assertEquals("夜班", during.name);
        assertEquals(18, during.day.get(Calendar.DAY_OF_MONTH));

        // 4/19 05:59 —— 仍在班上
        MyWidgetProvider.ShiftSlot edge = MyWidgetProvider.debugEffectiveSlot(at(2026, 4, 19, 5, 59), a, cycle, d);
        assertEquals("夜班", edge.name);

        // 4/19 06:30 —— 已过 06:00 下班时间，应切换到当天的休息
        MyWidgetProvider.ShiftSlot after = MyWidgetProvider.debugEffectiveSlot(at(2026, 4, 19, 6, 30), a, cycle, d);
        assertEquals("休息", after.name);
        assertEquals(19, after.day.get(Calendar.DAY_OF_MONTH));
    }

    /**
     * 23:00-11:00 这种“晚到上午”的班次，旧实现 08:00 就切断，正确行为应持续到 11:00。
     */
    @Test
    public void lateOvernightShiftLastsPastEightInTheMorning() {
        Map<String, MyWidgetProvider.ShiftDef> d = new LinkedHashMap<>();
        MyWidgetProvider.ShiftDef night = new MyWidgetProvider.ShiftDef();
        night.emoji = "🌙";
        night.cssClass = "night";
        night.startTime = "23:00";
        night.endTime = "11:00";
        night.isWork = true;
        night.crossDay = true;
        d.put("夜班", night);
        MyWidgetProvider.ShiftDef rest = new MyWidgetProvider.ShiftDef();
        rest.cssClass = "rest";
        rest.isWork = false;
        d.put("休息", rest);

        String[] cycle = {"夜班", "休息"};
        Calendar a = MyWidgetProvider.debugMidnight(at(2026, 4, 18, 0, 0));

        // 09:00 仍应属于昨日夜班（旧实现在 08:00 就误判为今日休息）
        MyWidgetProvider.ShiftSlot slot = MyWidgetProvider.debugEffectiveSlot(at(2026, 4, 19, 9, 0), a, cycle, d);
        assertEquals("夜班", slot.name);

        // 11:30 已下班
        MyWidgetProvider.ShiftSlot after = MyWidgetProvider.debugEffectiveSlot(at(2026, 4, 19, 11, 30), a, cycle, d);
        assertEquals("休息", after.name);
    }

    // ---------- 动态工作班次 ----------

    /**
     * 旧实现只统计白班/夜班；周期里出现第三种自定义工作班次时会被完全忽略。
     */
    @Test
    public void allWorkShiftKindsInCycleAreConsideredNotJustDayAndNight() {
        Map<String, MyWidgetProvider.ShiftDef> d = new LinkedHashMap<>();
        for (String name : new String[]{"白班", "中班", "夜班"}) {
            MyWidgetProvider.ShiftDef def = new MyWidgetProvider.ShiftDef();
            def.cssClass = name.equals("夜班") ? "night" : "day";
            def.isWork = true;
            def.startTime = name.equals("中班") ? "14:00" : (name.equals("夜班") ? "20:00" : "08:00");
            def.endTime = name.equals("中班") ? "22:00" : (name.equals("夜班") ? "08:00" : "20:00");
            def.crossDay = name.equals("夜班");
            d.put(name, def);
        }
        MyWidgetProvider.ShiftDef rest = new MyWidgetProvider.ShiftDef();
        rest.cssClass = "rest";
        rest.isWork = false;
        d.put("休息", rest);

        String[] cycle = {"白班", "中班", "夜班", "休息"};
        java.util.List<String> names = MyWidgetProvider.debugDistinctWorkShiftNames(cycle, d);
        assertEquals(3, names.size());
        assertTrue(names.contains("中班"));
    }

    @Test
    public void daysUntilFindsAnyShiftAcrossTheWholeCycle() {
        Map<String, MyWidgetProvider.ShiftDef> d = defs();
        Calendar a = anchor();
        Calendar today = MyWidgetProvider.debugMidnight(at(2026, 4, 18, 0, 0));

        // 4/18 是周期第 0 天（白班）
        assertEquals(3, MyWidgetProvider.debugDaysUntil("白班", today, a, CYCLE));
        assertEquals(1, MyWidgetProvider.debugDaysUntil("夜班", today, a, CYCLE));
        assertEquals(2, MyWidgetProvider.debugDaysUntil("休息", today, a, CYCLE));
    }

    // ---------- 时间格式化 ----------

    @Test
    public void countdownFormatIsHourMinuteSecond() {
        assertEquals("00:00:00", MyWidgetProvider.formatHms(0));
        assertEquals("00:00:59", MyWidgetProvider.formatHms(59_000));
        assertEquals("01:01:01", MyWidgetProvider.formatHms(3_661_000));
        assertEquals("00:00:00", MyWidgetProvider.formatHms(-5_000));
    }
    @Test public void exactStartChangesFromToWorkToToOff() {
        Calendar before = at(2026, 4, 18, 7, 59);
        MyWidgetProvider.ShiftSlot slot = MyWidgetProvider.debugEffectiveSlot(before, anchor(), CYCLE, defs());
        assertEquals(R.string.widget_countdown_to_work,
                MyWidgetProvider.countdownAt(before, slot, anchor(), CYCLE, defs()).label);
        Calendar start = at(2026, 4, 18, 8, 0);
        assertEquals(R.string.widget_countdown_to_off,
                MyWidgetProvider.countdownAt(start, slot, anchor(), CYCLE, defs()).label);
        assertEquals(start.getTimeInMillis(),
                MyWidgetProvider.nextStateChange(before, slot, anchor(), CYCLE, defs()));
    }

    @Test public void exactEndTargetsTheNextStartNeverAnExpiredShift() {
        Calendar end = at(2026, 4, 18, 20, 0);
        MyWidgetProvider.ShiftSlot slot = MyWidgetProvider.debugEffectiveSlot(end, anchor(), CYCLE, defs());
        MyWidgetProvider.Countdown countdown = MyWidgetProvider.countdownAt(end, slot, anchor(), CYCLE, defs());
        assertEquals(R.string.widget_countdown_to_work, countdown.label);
        assertEquals(at(2026, 4, 19, 20, 0).getTimeInMillis(), countdown.target);
    }

    @Test public void midnightIsABoundaryEvenDuringARestDay() {
        Calendar now = at(2026, 4, 20, 9, 0);
        MyWidgetProvider.ShiftSlot slot = MyWidgetProvider.debugEffectiveSlot(now, anchor(), CYCLE, defs());
        assertEquals(at(2026, 4, 21, 0, 0).getTimeInMillis(),
                MyWidgetProvider.nextStateChange(now, slot, anchor(), CYCLE, defs()));
    }

    @Test public void allRestCycleHasNoFakeCountdownAndStillRefreshesAtMidnight() {
        Calendar now = at(2026, 4, 18, 12, 0);
        String[] cycle = {"休息"};
        MyWidgetProvider.ShiftSlot slot = MyWidgetProvider.debugEffectiveSlot(now, anchor(), cycle, defs());
        assertEquals(0, MyWidgetProvider.countdownAt(now, slot, anchor(), cycle, defs()).target);
        assertEquals(at(2026, 4, 19, 0, 0).getTimeInMillis(),
                MyWidgetProvider.nextStateChange(now, slot, anchor(), cycle, defs()));
    }

    @Test public void explicitLongOvernightMatchesWebWithoutAnArbitrary26HourCap() {
        Map<String, MyWidgetProvider.ShiftDef> definitions = defs();
        MyWidgetProvider.ShiftDef longShift = definitions.get("白班");
        longShift.startTime = "08:00";
        longShift.endTime = "20:00";
        longShift.crossDay = true;
        Calendar now = at(2026, 4, 19, 19, 0);
        String[] cycle = {"白班", "休息"};
        MyWidgetProvider.ShiftSlot slot = MyWidgetProvider.debugEffectiveSlot(now, anchor(), cycle, definitions);
        assertEquals("白班", slot.name);
        assertEquals(at(2026, 4, 19, 20, 0).getTimeInMillis(), slot.end.getTimeInMillis());
    }

    @Test public void longCyclesSearchBeyondAWeekAndCountdownRetainsHoursBeyond24() {
        String[] cycle = new String[400];
        java.util.Arrays.fill(cycle, "休息");
        cycle[399] = "白班";
        Calendar now = at(2026, 4, 18, 12, 0);
        MyWidgetProvider.ShiftSlot slot = MyWidgetProvider.debugEffectiveSlot(now, anchor(), cycle, defs());
        assertTrue(MyWidgetProvider.countdownAt(now, slot, anchor(), cycle, defs()).target
                > now.getTimeInMillis() + 390L * 86400000L);
        assertEquals("44:52:33", MyWidgetProvider.formatHms((44 * 3600L + 52 * 60L + 33) * 1000));
    }

    @Test public void impossibleAnchorsAreRejectedLikeTheWeb() {
        assertEquals(null, MyWidgetProvider.parseAnchor("2026-02-30"));
        assertEquals(null, MyWidgetProvider.parseAnchor("2026-13-01"));
        assertEquals(null, MyWidgetProvider.parseAnchor("1800-01-01"));
        assertEquals(29, MyWidgetProvider.parseAnchor("2028-02-29").get(Calendar.DAY_OF_MONTH));
    }

    @Test public void anchorAcceptsAValidDayWhenDstSkipsMidnightLikeJavaScript() {
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/Sao_Paulo"));
            Calendar day = MyWidgetProvider.parseAnchor("2018-11-04");
            assertTrue(day != null);
            assertEquals(4, day.get(Calendar.DAY_OF_MONTH));
        } finally {
            java.util.TimeZone.setDefault(original);
        }
    }


    /**
     * 倒计时数值必须落在「一个周期之内」的合理范围。
     *
     * 这类异常曾真实出现过：某台手机的小部件显示「距上班 491:47:51」，
     * 而同一屏的「白班 2天」是正确的——说明显示的数值与目标时刻脱节，
     * 变成了某个与班次无关的量（当时是系统单调时钟异常，重启后恢复）。
     *
     * 断言不关心由哪个控件渲染，只检查数值本身是否合理，
     * 因此不会限制具体实现方式。
     */
    @Test public void countdownNeverExceedsOneCycle() {
        for (int day = 0; day < 30; day++) {
            for (int hour : new int[]{0, 8, 13, 20, 23}) {
                Calendar now = at(2026, 4, 18, hour, 0);
                now.add(Calendar.DAY_OF_YEAR, day);
                MyWidgetProvider.ShiftSlot slot =
                        MyWidgetProvider.debugEffectiveSlot(now, anchor(), CYCLE, defs());
                MyWidgetProvider.Countdown countdown =
                        MyWidgetProvider.countdownAt(now, slot, anchor(), CYCLE, defs());
                if (countdown.target == 0) continue;   // 全休息周期没有倒计时

                long remaining = countdown.target - now.getTimeInMillis();
                long cycleMillis = CYCLE.length * 24L * 3600_000L;
                assertTrue("倒计时不得为负：" + remaining, remaining >= 0);
                assertTrue("倒计时超出周期量级：remaining=" + remaining
                                + "ms（上限 " + cycleMillis + "ms）",
                        remaining <= cycleMillis);
            }
        }
    }
}
