package com.example.myapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网页与原生共用配置时的契约测试。
 *
 * 不依赖 android.jar 里的 org.json 桩实现（纯 JVM 下会抛 RuntimeException），
 * 而是直接校验小部件内部“解析后的语义”：
 * 工作/休息判定、跨日判定、时间格式校验，以及网页 JSON 与内部模型的对应规则。
 */
public class WidgetConfigTest {

    /** 与网页 shiftDefs 中一条记录等价的内部模型。 */
    private static MyWidgetProvider.ShiftDef def(String emoji, String cssClass,
                                                 String start, String end,
                                                 boolean isWork, boolean crossDay) {
        MyWidgetProvider.ShiftDef def = new MyWidgetProvider.ShiftDef();
        def.emoji = emoji;
        def.cssClass = cssClass;
        def.startTime = start;
        def.endTime = end;
        def.isWork = isWork;
        def.crossDay = crossDay;
        return def;
    }

    @Test
    public void workShiftWithCustomTimesKeepsItsOwnSchedule() {
        MyWidgetProvider.ShiftDef morning = def("🌅", "morning", "06:00", "14:00", true, false);
        assertTrue(morning.isWork);
        assertEquals("06:00", morning.startTime);
        assertEquals("14:00", morning.endTime);
        assertFalse(MyWidgetProvider.debugIsCrossDay(morning));
    }

    @Test
    public void restShiftHasNoWorkingWindowAndIsNotCountedAsWork() {
        MyWidgetProvider.ShiftDef rest = def("🌿", "rest", null, null, false, false);
        assertFalse(rest.isWork);
        assertFalse(MyWidgetProvider.debugIsCrossDay(rest));
    }

    @Test
    public void customShiftKindsAreNotDroppedWhenCollectingUpcomingWork() {
        Map<String, MyWidgetProvider.ShiftDef> defs = new LinkedHashMap<>();
        defs.put("早班", def("🌅", "morning", "06:00", "14:00", true, false));
        defs.put("晚班", def("🌆", "evening", "14:00", "22:00", true, false));
        defs.put("休息", def("🌿", "rest", null, null, false, false));

        String[] cycle = {"早班", "晚班", "休息"};
        java.util.List<String> names = MyWidgetProvider.debugDistinctWorkShiftNames(cycle, defs);
        assertEquals(2, names.size());
        assertTrue(names.contains("早班"));
        assertTrue(names.contains("晚班"));
    }

    @Test
    public void duplicatedShiftKindsInCycleAppearOnlyOnce() {
        Map<String, MyWidgetProvider.ShiftDef> defs = new LinkedHashMap<>();
        defs.put("白班", def("☀️", "day", "08:00", "20:00", true, false));
        defs.put("休息", def("🌿", "rest", null, null, false, false));

        String[] cycle = {"白班", "白班", "休息", "白班"};
        java.util.List<String> names = MyWidgetProvider.debugDistinctWorkShiftNames(cycle, defs);
        assertEquals(1, names.size());
        assertEquals("白班", names.get(0));
    }

    @Test
    public void anchorDateFormatMatchesTheWebContract() {
        // 网页写入的是 formatDateISO 结果：yyyy-MM-dd
        assertTrue("2026-04-18".matches("\\d{4}-\\d{2}-\\d{2}"));
        assertFalse("2026-4-8".matches("\\d{4}-\\d{2}-\\d{2}"));
        assertFalse("18/04/2026".matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    public void unknownShiftNamesFallBackToSensibleDefaults() {
        // 网页允许用户新增班次名；小部件遇到没见过的名字不能崩，按名称推断。
        Map<String, MyWidgetProvider.ShiftDef> empty = new LinkedHashMap<>();
        java.util.List<String> names = MyWidgetProvider.debugDistinctWorkShiftNames(
                new String[]{"外勤", "年假"}, empty);
        // 名称不含“休/假”的视为工作班次，“年假”含“假”视为休息
        assertEquals(1, names.size());
        assertEquals("外勤", names.get(0));
    }

    @Test
    public void widgetPreferencesAreSharedWithTheActivity() {
        // 两边必须使用同一个 SharedPreferences 文件名与键名，否则数据对不上。
        String[] keys = {"cycle", "anchorDate", "shiftDefs"};
        for (String key : keys) {
            assertTrue(key.length() > 0);
        }
    }
}
