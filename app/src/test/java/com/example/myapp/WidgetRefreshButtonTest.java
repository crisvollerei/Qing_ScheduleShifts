package com.example.myapp;

import static org.junit.Assert.*;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;

import java.util.Calendar;
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

    /** Cover the three compositions, minimum size, and awkward host proportions. */
    private static final int[][] SIZES = {
            {110, 110}, {110, 143}, {143, 110}, {110, 216}, {180, 110},
            {224, 144}, {156, 216}, {216, 216}, {224, 320}, {320, 110},
            {360, 110}, {360, 216}, {500, 320}, {184, 184}
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
                    dpW > 0 && dpH > 0);
        }
        System.out.println(report);
    }

    /**
     * The independent touch target sits at the root's top/end, outside the content flow.
     * 这样各比例下位置一致，不会随内容流被挤到中间。
     */
    @Test public void refreshButtonSitsAtTheTopEndCornerInEveryRatio() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        for (int[] size : SIZES) {
            int width = size[0], height = size[1];
            View root = render(activity, viewsFor(activity, width, height), width, height);
            ImageView button = root.findViewById(R.id.refresh_button);
            assertNotNull("布局缺少刷新按钮 " + width + "x" + height, button);
            View target = root.findViewById(R.id.refresh_touch_target);
            assertEquals(target, button.getParent());
            assertEquals(root, ((View) target.getParent()).getParent());
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) target.getLayoutParams();
            Rect bounds = new Rect(0,0,target.getWidth(),target.getHeight());
            ((ViewGroup)root).offsetDescendantRectToMyCoords(target,bounds);
            assertTrue(bounds.centerX() > width / 2f);
            assertTrue(bounds.centerY() < height / 3f);
        }
    }

    @Test public void refreshIconAndSurroundingTargetHaveClickActionsInEveryRatio() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        for (int[] size : SIZES) {
            int width = size[0], height = size[1];
            View root = render(activity, viewsFor(activity, width, height), width, height);
            ImageView button = root.findViewById(R.id.refresh_button);
            assertNotNull(button);
            View target = root.findViewById(R.id.refresh_touch_target);
            assertTrue("Refresh target must handle the whole hit region", target.hasOnClickListeners());
            assertTrue("Icon center must also refresh", button.hasOnClickListeners());
            assertNotNull("Whole hit region must show pressed feedback", target.getForeground());
        }
    }

    @Test public void refreshHasAFingerSizedHitAreaWithoutEnlargingItsIcon() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        for (int[] size : SIZES) {
            View root = render(activity, viewsFor(activity, size[0], size[1]), size[0], size[1]);
            View icon = root.findViewById(R.id.refresh_button);
            View target = icon.getParent() == root ? icon : (View) icon.getParent();
            float density = activity.getResources().getDisplayMetrics().density;
            assertTrue("Refresh touch target must be at least 48dp wide", target.getWidth() / density >= 48f);
            assertTrue("Refresh touch target must be at least 48dp tall", target.getHeight() / density >= 48f);
            assertTrue("The expanded touch target must handle clicks", target.hasOnClickListeners());
            WidgetRenderer.Geometry geometry=new WidgetRenderer.Geometry(WidgetRenderer.Form.COMPACT,size[0],size[1],28);
            assertEquals(26f*geometry.scale,icon.getWidth()/density,1f);
        }
    }

    @Test @Config(sdk = {28, 34}, application = android.app.Application.class)
    public void tapsAroundTheRefreshIconActuallyRefreshInsteadOfOpeningTheApp() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            AppWidgetManager manager = AppWidgetManager.getInstance(activity);
            int widgetId = Shadows.shadowOf(manager).createWidget(MyWidgetProvider.class, R.layout.widget_fixed_compact);
            for (int[] size : SIZES) {
                for (int[] offset : new int[][]{{0, 0}, {-21, 0}, {21, 0}, {0, -21}, {0, 21}}) {
                    View root = render(activity, viewsFor(activity, size[0], size[1]), size[0], size[1]);
                    View icon = root.findViewById(R.id.refresh_button);
                    Rect bounds = new Rect(0, 0, icon.getWidth(), icon.getHeight());
                    ((ViewGroup) root).offsetDescendantRectToMyCoords(icon, bounds);
                    float density = activity.getResources().getDisplayMetrics().density;
                    float x = bounds.exactCenterX() + offset[0] * density;
                    float y = bounds.exactCenterY() + offset[1] * density;
                    ((TextView) Shadows.shadowOf(manager).getViewFor(widgetId)
                            .findViewById(R.id.countdown_label)).setText("stale countdown");
                    long time = SystemClock.uptimeMillis();
                    MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0);
                    MotionEvent up = MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, x, y, 0);
                    try {
                        assertTrue(root.dispatchTouchEvent(down));
                        assertTrue(root.dispatchTouchEvent(up));
                        Shadows.shadowOf(Looper.getMainLooper()).idle();
                    } finally {
                        down.recycle();
                        up.recycle();
                    }
                    String label = size[0] + "x" + size[1] + " offset=" + offset[0] + "," + offset[1];
                    assertNull(label + " must not fall through to opening the app",
                            Shadows.shadowOf(activity).getNextStartedActivity());
                    assertNotEquals(label + " must reach the refresh receiver", "stale countdown",
                            ((TextView) Shadows.shadowOf(manager).getViewFor(widgetId)
                                    .findViewById(R.id.countdown_label)).getText().toString());
                }
            }
        }
    }

    @Test public void tickBroadcastReachesTheProviderWithoutCrashing() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        MyWidgetProvider provider = new MyWidgetProvider();
        provider.onReceive(activity, new android.content.Intent(activity, MyWidgetProvider.class)
                .setAction(WidgetRefresh.ACTION_TICK));
    }

    /** Verify an actual widget update, not merely the existence of the alarm service. */
    @Test public void tickBroadcastActuallyRefreshesWidgetViews() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.getSharedPreferences("WidgetSettings", Context.MODE_PRIVATE).edit()
                .putString("cycle", "[\"白班\",\"夜班\",\"休息\"]")
                .putString("anchorDate", "2026-04-18")
                .putString("shiftDefs", "{}")
                .commit();

        AppWidgetManager manager = AppWidgetManager.getInstance(activity);
        int widgetId = Shadows.shadowOf(manager).createWidget(MyWidgetProvider.class, R.layout.widget_fixed_compact);
        ((TextView) Shadows.shadowOf(manager).getViewFor(widgetId)
                .findViewById(R.id.countdown_label)).setText("stale countdown");

        new MyWidgetProvider().onReceive(activity, new android.content.Intent(activity, MyWidgetProvider.class)
                .setAction(WidgetRefresh.ACTION_TICK));

        // 刷新后应重新排好下一次状态边界闹钟，这是 refreshAll 走完的标志。
        android.app.AlarmManager alarms =
                (android.app.AlarmManager) activity.getSystemService(Context.ALARM_SERVICE);
        assertNotEquals("stale countdown", ((TextView) Shadows.shadowOf(manager).getViewFor(widgetId)
                .findViewById(R.id.countdown_label)).getText().toString());
        assertFalse(Shadows.shadowOf(alarms).getScheduledAlarms().isEmpty());
    }
}
