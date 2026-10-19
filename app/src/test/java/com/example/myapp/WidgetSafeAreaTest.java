package com.example.myapp;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.android.controller.ActivityController;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 排版验收：圆角安全区、无截断、间距节奏。
 *
 * <p>桌面小部件有两层圆角：布局自己的背景 drawable，以及宿主（Launcher）按
 * system_app_widget_background_radius 对整个小部件做的裁切。后者在 Android 12+
 * 默认 28dp，且宿主可以在运行时改，所以真正会把内容切掉的是它。
 *
 * <p>这里把「内容必须落在宿主圆角之内」写成断言，覆盖全部档位 × 主题 × 字号：
 * <ul>
 *   <li>可见文字 / 进度条 / 刷新按钮必须整个落在 R=28dp 的圆角矩形内部；</li>
 *   <li>同时距卡片四条直边至少 {@link #MIN_STRAIGHT_MARGIN_DP}；</li>
 *   <li>伴侣立绘只需落在圆角之内，允许贴直边（角色出血是有意设计）；</li>
 *   <li>任何文字都不允许被省略号截断。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetSafeAreaTest {

    /** 宿主圆角半径（Android 12+ 系统默认值，取最保守的一档）。 */
    static final float CORNER_RADIUS_DP = 28f;
    /** 内容距卡片直边的最小距离。 */
    static final float MIN_STRAIGHT_MARGIN_DP = 10f;

    /** 全集尺寸：覆盖 6 个布局档位的边界与常见比例。 */
    private static final int[][] SIZES = {
            {110, 110}, {110, 128}, {110, 143}, {110, 144}, {110, 175}, {110, 176},
            {110, 215}, {110, 216}, {110, 320},
            {140, 110}, {156, 110}, {180, 110}, {216, 110}, {224, 110}, {224, 144},
            {240, 215}, {280, 180}, {320, 110}, {359, 110}, {360, 110}, {360, 144}, {360, 215}, {500, 110},
            {500, 215}, {360, 176}, {420, 110},
            {156, 216}, {180, 320}, {215, 216}, {216, 216}, {224, 216}, {240, 240},
            {240, 280}, {280, 216}, {320, 216}, {360, 216}, {360, 300}, {500, 320},
            {224, 320}, {224, 380}, {320, 320}, {320, 380}, {224, 420}, {320, 420}, {224, 440}, {300, 520},
    };

    /** 内容控件：必须满足圆角 + 直边双重安全区。 */
    private static final int[] CONTENT_IDS = {
            R.id.today_date, R.id.shift_title, R.id.shift_emoji, R.id.shift_name,
            R.id.shift_time, R.id.shift_status, R.id.cycle_info, R.id.progress_bar,
            R.id.next_shifts, R.id.live_clock, R.id.countdown_label,
            R.id.countdown_chronometer, R.id.countdown_text, R.id.countdown_detail,
            R.id.refresh_button, R.id.week_strip,
    };

    private View render(Activity activity, int width, int height, boolean dark, float fontScale) {
        android.content.res.Configuration config =
                new android.content.res.Configuration(activity.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                | (dark ? android.content.res.Configuration.UI_MODE_NIGHT_YES
                        : android.content.res.Configuration.UI_MODE_NIGHT_NO);
        config.fontScale = fontScale;
        android.content.Context context = activity.createConfigurationContext(config);
        Calendar now = Calendar.getInstance();
        Calendar anchor = MyWidgetProvider.parseAnchor("2026-04-18");
        String[] cycle = {"白班", "夜班", "休息", "白班", "夜班", "休息", "休息", "休息"};
        Map<String, MyWidgetProvider.ShiftDef> defs = MyWidgetProvider.debugDefaultDefs();
        MyWidgetProvider.ShiftSlot slot = MyWidgetProvider.debugEffectiveSlot(now, anchor, cycle, defs);
        RemoteViews remote = MyWidgetProvider.buildViews(context, width, height, now, slot,
                anchor, cycle, defs, MyWidgetProvider.countdownAt(now, slot, anchor, cycle, defs));
        FrameLayout surface = new FrameLayout(context);
        activity.setContentView(surface);
        View view = remote.apply(context, surface);
        float density = context.getResources().getDisplayMetrics().density;
        int w = Math.round(width * density), h = Math.round(height * density);
        surface.addView(view, new ViewGroup.LayoutParams(w, h));
        surface.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        surface.layout(0, 0, w, h);
        return view;
    }

    /** 控件相对根容器的矩形（dp）。 */
    private static Rect boundsDp(View root, View child) {
        Rect rect = new Rect(0, 0, child.getWidth(), child.getHeight());
        ((ViewGroup) root).offsetDescendantRectToMyCoords(child, rect);
        float density = root.getResources().getDisplayMetrics().density;
        return new Rect(Math.round(rect.left / density), Math.round(rect.top / density),
                Math.round(rect.right / density), Math.round(rect.bottom / density));
    }

    /**
     * 一个矩形离「宿主圆角矩形」边界的越界量（dp）。&lt;= 0 表示完全在圆角之内。
     *
     * <p>用圆角矩形的有符号距离函数来做，避免分角判断时把左右/上下弄反：
     * 把矩形的每个角点夹到「允许区域」[R, W-R] x [R, H-R] 上，
     * 该角点到夹取点的距离就是它超出圆角矩形的深度（四个直角区之外恒为 0）。
     * 矩形是凸的，四个角点都在圆角矩形内当且仅当整个矩形在内。
     *
     * <p>由此可直接推出验收用的内缩下限：四边均内缩 B 时越界量为
     * R·(1 − 1/√2) − B，令其 &lt;= 0 得 B &gt;= R·(1 − 1/√2)；
     * R=28 时下限 8.20dp，本项目取 10dp 留余量。
     */
    static float cornerOverflow(Rect rect, int cardWidth, int cardHeight, float radius) {
        float[][] points = {
                {rect.left, rect.top}, {rect.right, rect.top},
                {rect.left, rect.bottom}, {rect.right, rect.bottom},
        };
        float worst = 0f;
        for (float[] point : points) {
            float nearestX = clamp(point[0], radius, cardWidth - radius);
            float nearestY = clamp(point[1], radius, cardHeight - radius);
            float distance = (float) Math.hypot(point[0] - nearestX, point[1] - nearestY);
            worst = Math.max(worst, distance - radius);
        }
        return worst;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String name(View view) {
        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (Exception missing) {
            return view.getClass().getSimpleName();
        }
    }

    /**
     * 几何判据自检。
     *
     * <p>这条测试保护的正是「检测器本身」：越界函数写错时，上面那些「全部通过」的结论
     * 会变成假阳性。用几个解析可算的例子把边界钉死。
     */
    @Test
    public void cornerOverflowMatchesTheAnalyticBoundary() {
        int size = 110;
        float radius = 28f;
        float bound = (float) (radius * (1 - 1 / Math.sqrt(2)));
        Rect analytic = new Rect(Math.round(bound), Math.round(bound),
                size - Math.round(bound), size - Math.round(bound));
        assertTrue("解析下限处不应越界", cornerOverflow(analytic, size, size, radius) <= 0.6f);
        // 内缩不足则必须报出来
        Rect tooTight = new Rect(6, 6, size - 6, size - 6);
        assertTrue("内缩 6dp 应判越界", cornerOverflow(tooTight, size, size, radius) > 2f);
        // 四个角都要能检测到（防止左右/上下判断写反）
        for (Rect corner : new Rect[]{
                new Rect(2, 2, 20, 20), new Rect(size - 20, 2, size - 2, 20),
                new Rect(2, size - 20, 20, size - 2), new Rect(size - 20, size - 20, size - 2, size - 2)}) {
            assertTrue("四角均应可检出：" + corner, cornerOverflow(corner, size, size, radius) > 5f);
        }
        // 中心区域与贴底但居于安全区的矩形都不应误报
        assertEquals(0f, cornerOverflow(new Rect(30, 30, 80, 80), size, size, radius), 0f);
        assertEquals(0f, cornerOverflow(new Rect(14, 82, 96, 98), size, size, radius), 0f);
        // 旧缺陷的复现：刷新按钮钉在右上角
        assertTrue("右上角贴边的按钮必须判越界",
                cornerOverflow(new Rect(84, 0, 110, 26), size, size, radius) > 5f);
        // 修正后的位置不应误报
        assertEquals(0f, cornerOverflow(new Rect(74, 10, 100, 36), size, size, radius), 0f);
    }

    @Test
    public void everyContentStaysInsideTheHostRoundedCornerAndTheStraightMargins() {
        try (ActivityController<Activity> controller =
                     Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            List<String> failures = new ArrayList<>();
            for (boolean dark : new boolean[]{false, true}) {
                for (float font : new float[]{1f, 1.3f}) {
                    for (int[] size : SIZES) {
                        int width = size[0], height = size[1];
                        String variant = width + "x" + height + (dark ? "-dark" : "-light") + "-font" + font;
                        View root = render(activity, width, height, dark, font);
                        for (int id : CONTENT_IDS) {
                            View view = root.findViewById(id);
                            if (view == null || view.getVisibility() != View.VISIBLE) continue;
                            if (view.getWidth() <= 0 || view.getHeight() <= 0) continue;
                            Rect rect = boundsDp(root, view);
                            String label = name(view);
                            float overflow = cornerOverflow(rect, width, height, CORNER_RADIUS_DP);
                            if (overflow > 0f) {
                                failures.add(variant + " " + label + " 侵入宿主圆角 " + round(overflow)
                                        + "dp at " + rect.toShortString());
                            }
                            if (rect.left < MIN_STRAIGHT_MARGIN_DP || rect.top < MIN_STRAIGHT_MARGIN_DP
                                    || width - rect.right < MIN_STRAIGHT_MARGIN_DP
                                    || height - rect.bottom < MIN_STRAIGHT_MARGIN_DP) {
                                failures.add(variant + " " + label + " 距直边过近 "
                                        + "L" + rect.left + " T" + rect.top
                                        + " R" + (width - rect.right) + " B" + (height - rect.bottom));
                            }
                        }
                        View companion = root.findViewById(R.id.companion_portrait);
                        if (companion != null && companion.getVisibility() == View.VISIBLE) {
                            Rect rect = boundsDp(root, companion);
                            float overflow = cornerOverflow(rect, width, height, CORNER_RADIUS_DP);
                            if (overflow > 0f) {
                                failures.add(variant + " companion_portrait 侵入宿主圆角 "
                                        + round(overflow) + "dp at " + rect.toShortString());
                            }
                        }
                    }
                }
            }
            assertEquals(failures.isEmpty() ? "" : String.join("\n", failures), 0, failures.size());
        }
    }

    @Test
    public void noTextIsEverEllipsized() {
        try (ActivityController<Activity> controller =
                     Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            List<String> failures = new ArrayList<>();
            for (boolean dark : new boolean[]{false, true}) {
                for (float font : new float[]{1f, 1.3f}) {
                    for (int[] size : SIZES) {
                        View root = render(activity, size[0], size[1], dark, font);
                        String variant = size[0] + "x" + size[1] + (dark ? "-dark" : "-light") + "-font" + font;
                        collectEllipsized(root, variant, failures);
                    }
                }
            }
            assertTrue(String.join("\n", failures), failures.isEmpty());
        }
    }

    private void collectEllipsized(View view, String variant, List<String> failures) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            android.text.Layout layout = text.getLayout();
            if (layout == null) return;
            int ellipsis = 0;
            for (int line = 0; line < layout.getLineCount(); line++) ellipsis += layout.getEllipsisCount(line);
            if (ellipsis > 0) {
                failures.add(variant + " " + name(view) + " 被省略号截断：\"" + text.getText() + "\"");
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectEllipsized(group.getChildAt(i), variant, failures);
        }
    }

    /**
     * 垂直节奏：同一列里相邻块的间距要接近，且内容不能缩在顶部、底下空一大块。
     *
     * <p>只比较**同一个父容器内的相邻兄弟块**——嵌套容器的子块本来就与父块重叠，
     * 混在一起算会把「面板内部的正常间距」误判成节奏失衡。
     */
    @Test
    public void verticalRhythmStaysEvenAndTheCardIsNotBottomHeavy() {
        try (ActivityController<Activity> controller =
                     Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            List<String> failures = new ArrayList<>();
            for (boolean dark : new boolean[]{false, true}) {
                for (int[] size : SIZES) {
                    View root = render(activity, size[0], size[1], dark, 1f);
                    String variant = size[0] + "x" + size[1] + (dark ? "-dark" : "-light");
                    checkStackRhythm(root, root, variant, failures);
                    checkBottomFilling(root, variant, size[1], failures);
                }
            }
            assertTrue(String.join("\n", failures), failures.isEmpty());
        }
    }

    /** 逐个容器检查其直接子块（可见、有高度）之间的垂直间隙是否成节奏。 */
    private void checkStackRhythm(View root, View group, String variant, List<String> failures) {
        if (group instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) group;
            List<Integer> gaps = new ArrayList<>();
            Integer previousBottom = null;
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                Rect rect = boundsDp(root, child);
                if (rect.height() <= 0) continue;
                if (previousBottom != null) {
                    int gap = rect.top - previousBottom;
                    if (gap > 0) gaps.add(gap);
                }
                previousBottom = rect.bottom;
            }
            if (gaps.size() >= 2) {
                int max = gaps.stream().mapToInt(Integer::intValue).max().orElse(0);
                int min = gaps.stream().mapToInt(Integer::intValue).min().orElse(0);
                // 同一列内最大间隙不超过最小间隙的 3 倍 + 6dp；再大就是「上面挤、下面空」。
                if (max > min * 3 + 6) {
                    failures.add(variant + " 垂直节奏失衡：" + name(parent) + " 内间隙 " + gaps);
                }
                // 间隙本身也不能过大：单段留白超过 40dp 就是空洞。
                if (max > 40) {
                    failures.add(variant + " 出现过大留白：" + name(parent) + " 间隙 " + gaps);
                }
            }
            for (int i = 0; i < parent.getChildCount(); i++) {
                checkStackRhythm(root, parent.getChildAt(i), variant, failures);
            }
        }
    }

    /** 内容不应缩在顶部：最后一块的底边要接近卡片底部的安全线。 */
    private void checkBottomFilling(View root, String variant, int cardHeight, List<String> failures) {
        List<Rect> blocks = new ArrayList<>();
        collectContentBlocks(root, root, blocks);
        if (blocks.isEmpty()) return;
        int bottom = blocks.stream().mapToInt(r -> r.bottom).max().orElse(0);
        int hole = cardHeight - bottom;
        if (hole > Math.max(30, Math.round(cardHeight * 0.30f))) {
            failures.add(variant + " 底部留白过大 " + hole + "dp（内容止于 y=" + bottom + "）");
        }
    }

    /** 收集会占位的可见内容块（有文字的控件 / 有背景的容器），跳过装饰图。 */
    private void collectContentBlocks(View root, View view, List<Rect> blocks) {
        if (view.getVisibility() != View.VISIBLE) return;
        boolean decorative = view.getId() == View.NO_ID && view instanceof android.widget.ImageView;
        if (!decorative && view.getWidth() > 0 && view.getHeight() > 0
                && (view instanceof TextView || view.getBackground() != null)) {
            blocks.add(boundsDp(root, view));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectContentBlocks(root, group.getChildAt(i), blocks);
            }
        }
    }

    private static String round(float value) {
        return String.format(java.util.Locale.US, "%.2f", value);
    }
}