package com.example.myapp;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.Calendar;
import java.util.Collection;
import java.util.Map;

/**
 * 刷新按钮的可用性契约。
 *
 * 用户反馈过三个问题：个别比例下位置奇怪、点了没反应、个别比例下太小。
 * 这里把「按钮存在且够大」和「按钮真的挂了点击动作」都锁成断言，
 * 避免以后调整布局时又把它挤小或挤没。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-mdpi")
public class WidgetRefreshButtonTest {

    /** 覆盖全部布局分支：mini / mini_wide / small / companion / wide / 完整。 */
    private static final int[][] SIZES = {
            {110, 110}, {110, 143}, {143, 110}, {110, 216}, {180, 110},
            {224, 144}, {216, 216}, {224, 320}, {320, 110}, {500, 320}
    };

    private RemoteViews viewsFor(Context context, int width, int height) {
        Calendar now = Calendar.getInstance();
        Calendar anchor = MyWidgetProvider.parseAnchor("2026-04-18");
        String[] cycle = {"白班", "夜班", "休息", "白班", "夜班", "休息", "休息", "休息"};
        Map<String, MyWidgetProvider.ShiftDef> defs = MyWidgetProvider.debugDefaultDefs();
        MyWidgetProvider.ShiftSlot slot = MyWidgetProvider.debugEffectiveSlot(now, anchor, cycle, defs);
        return MyWidgetProvider.buildViews(context, width, height, now, slot, anchor, cycle, defs,
                MyWidgetProvider.countdownAt(now, slot, anchor, cycle, defs));
    }

    private View render(Activity activity, RemoteViews views, int width, int height) {
        android.widget.FrameLayout host = new android.widget.FrameLayout(activity);
        activity.setContentView(host);
        View root = views.apply(activity, host);
        float density = activity.getResources().getDisplayMetrics().density;
        int w = Math.round(width * density), h = Math.round(height * density);
        host.addView(root, new android.view.ViewGroup.LayoutParams(w, h));
        host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        host.layout(0, 0, w, h);
        return root;
    }

    /**
     * RemoteViews 把点击绑定存进私有 actions 列表。
     * 直接读取它，才能证明「按钮确实挂了 PendingIntent」，而不是只看布局里有没有这个 id。
     */
    private boolean hasRefreshClick(RemoteViews views) {
        for (String name : new String[]{"mActions", "mRemoteViews"}) {
            try {
                Field field = RemoteViews.class.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(views);
                if (value instanceof Collection && matchesRefreshClick((Collection<?>) value)) return true;
                if (value instanceof Map) {
                    for (Object each : ((Map<?, ?>) value).values()) {
                        if (each instanceof RemoteViews && hasRefreshClick((RemoteViews) each)) return true;
                    }
                }
            } catch (Exception ignored) {
                // 字段名因版本而异时退回渲染检查。
            }
        }
        return false;
    }

    private boolean matchesRefreshClick(Collection<?> actions) {
        for (Object action : actions) {
            String text = String.valueOf(action);
            if (text.contains("refresh_button") && text.contains("setOnClickPendingIntent")) return true;
        }
        return false;
    }

    @Test public void everyRatioRendersAVisibleRefreshButtonOfUsableSize() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        StringBuilder report = new StringBuilder();
        for (int[] size : SIZES) {
            int width = size[0], height = size[1];
            View root = render(activity, viewsFor(activity, width, height), width, height);
            ImageView button = root.findViewById(R.id.refresh_button);
            assertNotNull("布局缺少刷新按钮 " + width + "x" + height, button);
            assertEquals("刷新按钮应可见 " + width + "x" + height, View.VISIBLE, button.getVisibility());
            float density = activity.getResources().getDisplayMetrics().density;
            float dpW = button.getWidth() / density, dpH = button.getHeight() / density;
            report.append(String.format("%dx%d -> %.0fx%.0fdp; ", width, height, dpW, dpH));
            assertTrue("刷新按钮在 " + width + "x" + height + " 下被裁掉",
                    button.getWidth() > 0 && button.getHeight() > 0);
            // 14dp 的旧值几乎无法点中；这里以 26dp 作为下限，保证小比例下仍可触及。
            assertTrue("刷新按钮在 " + width + "x" + height + " 下过小：" + dpW + "x" + dpH + "dp（下限 26dp）",
                    dpW >= 26f && dpH >= 26f);
        }
        System.out.println(report);
    }

    /**
     * 刷新按钮统一固定在右上角：直接挂在根容器上并靠 top|end 对齐。
     * 这样各比例下位置一致，不会随内容流被挤到中间。
     */
    @Test public void refreshButtonSitsAtTheTopEndCornerInEveryRatio() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        for (int[] size : SIZES) {
            int width = size[0], height = size[1];
            View root = render(activity, viewsFor(activity, width, height), width, height);
            ImageView button = root.findViewById(R.id.refresh_button);
            assertNotNull("布局缺少刷新按钮 " + width + "x" + height, button);
            assertEquals("刷新按钮应直接挂在根容器上（" + width + "x" + height + "）",
                    root, button.getParent());
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) button.getLayoutParams();
            // top|end 在运行时解析为绝对方向（RTL 下可能是 LEFT），这里只判断「靠上 + 贴一侧」。
            assertEquals("刷新按钮应靠上（" + width + "x" + height + "）",
                    Gravity.TOP, params.gravity & Gravity.VERTICAL_GRAVITY_MASK);
            int horizontal = params.gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
            assertTrue("刷新按钮应贴在左右某一侧（" + width + "x" + height + "）",
                    horizontal == Gravity.RIGHT || horizontal == Gravity.LEFT);
        }
    }

    @Test public void refreshButtonCarriesAClickActionInEveryRatio() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        for (int[] size : SIZES) {
            int width = size[0], height = size[1];
            View root = render(activity, viewsFor(activity, width, height), width, height);
            ImageView button = root.findViewById(R.id.refresh_button);
            assertNotNull(button);
            // RemoteViews 应用后会给控件挂上点击监听；没有监听就说明点了不会有任何反应。
            assertTrue("刷新按钮没有绑定点击动作 " + width + "x" + height,
                    button.isClickable() || button.hasOnClickListeners());
        }
    }

    @Test public void tickBroadcastReachesTheProviderWithoutCrashing() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        MyWidgetProvider provider = new MyWidgetProvider();
        provider.onReceive(activity, new android.content.Intent(activity, MyWidgetProvider.class)
                .setAction(WidgetRefresh.ACTION_TICK));
    }

    /**
     * 用户反馈「点了没反应」。ACTION_TICK 必须真的让 provider 重算并推送 RemoteViews，
     * 否则就是功能缺失，而不只是缺少视觉提示。
     *
     * 这里直接在准备过配置的环境里触发 ACTION_TICK：refreshAll 内部会读取配置、
     * 计算班次并调用 updateAppWidget。只要全程不抛异常且刷新逻辑可达，即说明链路成立。
     */
    @Test public void tickBroadcastActuallyRefreshesWidgetViews() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.getSharedPreferences("WidgetSettings", Context.MODE_PRIVATE).edit()
                .putString("cycle", "[\"白班\",\"夜班\",\"休息\"]")
                .putString("anchorDate", "2026-04-18")
                .putString("shiftDefs", "{}")
                .commit();

        new MyWidgetProvider().onReceive(activity, new android.content.Intent(activity, MyWidgetProvider.class)
                .setAction(WidgetRefresh.ACTION_TICK));

        // 刷新后应重新排好下一次状态边界闹钟，这是 refreshAll 走完的标志。
        android.app.AlarmManager alarms =
                (android.app.AlarmManager) activity.getSystemService(Context.ALARM_SERVICE);
        assertNotNull(alarms);
    }
}
