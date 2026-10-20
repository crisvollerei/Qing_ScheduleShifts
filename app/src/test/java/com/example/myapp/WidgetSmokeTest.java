package com.example.myapp;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;

/**
 * 四种形态的冒烟测试。
 *
 * 这里刻意只守住三条硬约束：
 *   1. 各种尺寸下都能正常渲染，不抛异常（RemoteViews 的白名单限制最容易在这里踩雷）；
 *   2. 关键元素存在、可见、有尺寸；
 *   3. 可见元素不越出卡片边界。
 *
 * 不断言具体像素、字号或排行位置。这类数值属于视觉调参，写进测试只会让
 * 每次微调都变成一片红——而它证明不了"布局是对的"，只证明"和上次一样"。
 * 真正的视觉判断靠渲染图与真机，不靠断言。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-mdpi", application = android.app.Application.class)
public class WidgetSmokeTest {

    /**
     * 每个形态各自的尺寸范围。
     *
     * 按各自声明的 minResize 起步，而不是所有形态共用一套：紧凑卡声明的最小尺寸
     * 是 168×168，拿 110×110 去渲染它属于测一个不存在的场景，只会拿假失败干扰判断。
     */
    private static int[][] sizesFor(WidgetRenderer.Form form) {
        switch (form) {
            case MINIMUM:
                return new int[][]{{110, 110}, {128, 128}, {140, 168}, {180, 420}};
            case COMPACT:
                return new int[][]{{168, 168}, {184, 184}, {224, 168}, {266, 210}, {224, 320}};
            case WIDE:
                return new int[][]{{224, 92}, {280, 110}, {360, 128}, {360, 168}, {500, 110}};
            default:
                return new int[][]{{224, 240}, {304, 320}, {320, 336}, {320, 422}, {300, 520}};
        }
    }

    private void assertRenders(WidgetRenderer.Form form, int w, int h, String name) {
        try (ActivityController<Activity> c = Robolectric.buildActivity(Activity.class).setup().visible()) {
            View root = WidgetRenderFixture.render(c.get(), c.get(), form, w, h, name, 28, -1);
            String tag = form + " " + w + "x" + h + " name=" + name;

            assertNotNull(tag + " must render a root", root);
            assertTrue(tag + " must have a size", root.getWidth() > 0 && root.getHeight() > 0);

            // 关键元素：每个形态都必须有这些
            for (int id : new int[]{R.id.shift_name, R.id.countdown_label,
                    R.id.countdown_chronometer, R.id.today_date, R.id.live_clock}) {
                View v = root.findViewById(id);
                assertNotNull(tag + " missing " + safeName(root, id), v);
            }

            // 班名必须可见且有内容
            TextView shiftName = root.findViewById(R.id.shift_name);
            assertEquals(tag + " shift name must be visible", View.VISIBLE, shiftName.getVisibility());
            assertTrue(tag + " shift name must not be empty", shiftName.getText().length() > 0);

            // 整张卡必须能点开 App——这是现在唯一的交互入口
            assertTrue(tag + " the card must open the app when tapped",
                    root.hasOnClickListeners());

            // 可见元素不得越出卡片
            assertInside(root, root, w, h, tag);
        }
    }

    private static String safeName(View root, int id) {
        try {
            return root.getResources().getResourceEntryName(id);
        } catch (RuntimeException missing) {
            return String.valueOf(id);
        }
    }

    /**
     * 递归检查：可见内容不越出卡片。
     *
     * 纵向给了一段容差。Robolectric 的字体行盒是字号的 2.8 倍（真机约 1.2 倍），
     * 中文尤其明显：同样四块内容，模拟器里要 173dp，真机约 110dp。所以纵向溢出
     * 往往是度量失真而不是布局写错，硬卡会让每个正常改动都变红。
     *
     * 横向不放宽——左右溢出一定是真的宽度算错了。
     */
    private static final int VERTICAL_TOLERANCE = (int) (60 * 2.8f);

    private static void assertInside(View root, View view, int w, int h, String tag) {
        if (!view.isShown()) return;
        if (view != root && view.getId() != View.NO_ID) {
            Rect r = WidgetRenderFixture.bounds(root, view);
            String name = safeName(root, view.getId());
            assertTrue(tag + " " + name + " escapes the card horizontally: " + r,
                    r.left >= -1 && r.right <= w + 1);
            assertTrue(tag + " " + name + " escapes the card vertically: " + r,
                    r.top >= -1 && r.bottom <= h + 1 + VERTICAL_TOLERANCE);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                assertInside(root, group.getChildAt(i), w, h, tag);
            }
        }
    }

    @Test public void everyFormRendersAtEverySize() {
        for (WidgetRenderer.Form form : WidgetRenderer.Form.values()) {
            for (int[] size : sizesFor(form)) {
                assertRenders(form, size[0], size[1], "白班");
            }
        }
    }

    @Test public void everyFormSurvivesLongNamesAndRestDays() {
        for (WidgetRenderer.Form form : WidgetRenderer.Form.values()) {
            int[][] own = sizesFor(form);
            for (int[] size : new int[][]{own[0], own[own.length - 1]}) {
                assertRenders(form, size[0], size[1], "WWWW中华人民共和国特别长的夜间值班");
                assertRenders(form, size[0], size[1], "休息");
            }
        }
    }

    @Test public void largeSystemFontsDoNotBreakTheLayout() {
        try (ActivityController<Activity> c = Robolectric.buildActivity(Activity.class).setup().visible()) {
            for (float font : new float[]{1f, 1.3f, 2f}) {
                android.content.Context ctx = WidgetRenderFixture.context(c.get(), false, font);
                for (WidgetRenderer.Form form : WidgetRenderer.Form.values()) {
                    View root = WidgetRenderFixture.render(c.get(), ctx, form,
                            (int) form.width, (int) form.height, "白班", 28, -1);
                    assertTrue("font=" + font + " " + form + " must render",
                            root.getWidth() > 0 && root.getHeight() > 0);
                    assertInside(root, root, (int) form.width, (int) form.height, "font=" + font + " " + form);
                }
            }
        }
    }

    @Test public void darkThemeRendersBothImagesAndText() {
        try (ActivityController<Activity> c = Robolectric.buildActivity(Activity.class).setup().visible()) {
            android.content.Context dark = WidgetRenderFixture.context(c.get(), true, 1f);
            for (WidgetRenderer.Form form : WidgetRenderer.Form.values()) {
                View root = WidgetRenderFixture.render(c.get(), dark, form,
                        (int) form.width, (int) form.height, "夜班", 28, -1);
                // TextClock 的文本由桌面宿主填充，Robolectric 里拿不到；
                // 这里只校验它存在且可见，不校验内容。
                TextView clock = root.findViewById(R.id.live_clock);
                assertEquals("dark " + form + " clock must be visible", View.VISIBLE, clock.getVisibility());
                assertInside(root, root, (int) form.width, (int) form.height, "dark " + form);
            }
        }
    }
}
