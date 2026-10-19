package com.example.myapp;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
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

import java.util.Arrays;
import java.util.Calendar;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetAdaptiveLayoutTest {
    private static final int[][] SIZES = {
            {110,110}, {110,320}, {156,216}, {215,216}, {216,110}, {216,216},
            {224,216}, {224,320}, {320,216}, {359,110}, {360,110}, {360,216},
            {500,110}, {500,320}, {300,520}
    };

    private View render(Activity activity, Context context, int width, int height,
            float radius, String shift, int cycleLength, boolean reapply, View previous) {
        Calendar now = Calendar.getInstance();
        now.set(2026, Calendar.OCTOBER, 19, 12, 34, 56);
        now.set(Calendar.MILLISECOND, 0);
        android.os.SystemClock.setCurrentTimeMillis(now.getTimeInMillis());
        Calendar anchor = MyWidgetProvider.debugMidnight(now);
        anchor.add(Calendar.DAY_OF_YEAR, 1 - cycleLength);
        String[] cycle = new String[cycleLength];
        Arrays.fill(cycle, shift);
        Map<String, MyWidgetProvider.ShiftDef> defs = MyWidgetProvider.debugDefaultDefs();
        MyWidgetProvider.ShiftDef custom = new MyWidgetProvider.ShiftDef();
        custom.emoji = "\u2600\uFE0F";
        custom.startTime = "08:00";
        custom.endTime = "20:00";
        defs.put(shift, custom);
        MyWidgetProvider.ShiftSlot slot = MyWidgetProvider.debugEffectiveSlot(now, anchor, cycle, defs);
        RemoteViews remote = MyWidgetProvider.buildViews(context, width, height, now, slot,
                anchor, cycle, defs, MyWidgetProvider.countdownAt(now, slot, anchor, cycle, defs), radius);
        FrameLayout host;
        View root;
        if (reapply) {
            root = previous;
            host = (FrameLayout) root.getParent();
            remote.reapply(context, root);
        } else {
            host = new FrameLayout(context);
            activity.setContentView(host);
            root = remote.apply(context, host);
            host.addView(root);
        }
        float density = context.getResources().getDisplayMetrics().density;
        int w = Math.round(width * density), h = Math.round(height * density);
        root.setLayoutParams(new FrameLayout.LayoutParams(w, h));
        host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        host.layout(0, 0, w, h);
        return root;
    }

    private Context configuration(Activity activity, float font, boolean dark) {
        Configuration config = new Configuration(activity.getResources().getConfiguration());
        config.fontScale = font;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return activity.createConfigurationContext(config);
    }

    private Rect bounds(View root, View child) {
        Rect rect = new Rect(0, 0, child.getWidth(), child.getHeight());
        ((ViewGroup) root).offsetDescendantRectToMyCoords(child, rect);
        return rect;
    }

    private void assertGeometry(View root, View view, int width, int height, float radius, String label) {
        if (view.getVisibility() != View.VISIBLE) return;
        boolean text = view instanceof TextView && ((TextView) view).getText().length() > 0;
        boolean control = view.getId() == R.id.refresh_button || view.getId() == R.id.progress_bar
                || view.getId() == R.id.companion_portrait;
        if (text || control) {
            Rect px = bounds(root, view);
            float density = root.getResources().getDisplayMetrics().density;
            Rect dp = new Rect(Math.round(px.left / density), Math.round(px.top / density),
                    Math.round(px.right / density), Math.round(px.bottom / density));
            assertTrue(label + " outside rounded host: " + dp,
                    WidgetSafeAreaTest.cornerOverflow(dp, width, height, radius) <= 0.1f);
            assertTrue(label + " has no space: " + dp, dp.width() > 0 && dp.height() > 0);
            if (text) {
                assertFalse(label + " refresh covers text: " + ((TextView) view).getText(),
                        Rect.intersects(px, bounds(root, root.findViewById(R.id.refresh_button))));
                TextView value = (TextView) view;
                float measured = value.getPaint().measureText(value.getText().toString());
                if (value.getMaxLines() == 1) {
                    assertTrue(label + " clips text: " + value.getText() + " in " + dp,
                            measured <= value.getWidth() - value.getCompoundPaddingLeft()
                                    - value.getCompoundPaddingRight() + 0.5f);
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                assertGeometry(root, group.getChildAt(index), width, height, radius, label);
            }
        }
    }

    @Test public void largerHostCornersAndLongShiftNamesStayInsideEveryShape() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            for (float font : new float[]{1f, 1.3f, 2f}) {
                Context context = configuration(activity, font, font == 2f);
                for (float radius : new float[]{28f, 36f, 48f}) {
                    for (int[] size : SIZES) {
                        View root = render(activity, context, size[0], size[1], radius,
                                "\u8bbe\u5907\u8fd0\u7ef4\u65e9\u73ed", 3660, false, null);
                        String label = size[0] + "x" + size[1] + " R=" + radius + " font=" + font;
                        assertGeometry(root, root, size[0], size[1], radius, label);
                        View portrait = root.findViewById(R.id.companion_portrait);
                        if (portrait.getVisibility() == View.VISIBLE) {
                            assertFalse(label + " refresh overlaps portrait", Rect.intersects(
                                    bounds(root, portrait), bounds(root, root.findViewById(R.id.refresh_button))));
                        }
                    }
                }
            }
        }
    }

    @Test public void wideLatinGlyphsAreNotEstimatedAsNarrowDigits() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            for (int[] size : SIZES) {
                View root = render(controller.get(), controller.get(), size[0], size[1], 36f,
                        "WWMWMaintenance", 3660, false, null);
                assertGeometry(root, root, size[0], size[1], 36f, "wide Latin glyphs");
            }
        }
    }

    @Test public void fullWidthSquareHeaderAndTimingDoNotPayForThePortraitColumn() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            View root = render(controller.get(), controller.get(), 216, 216, 28f,
                    "\u767d\u73ed", 8, false, null);
            assertTrue(root.findViewById(R.id.today_date).getWidth() >= 160);
            assertTrue(root.findViewById(R.id.timing_panel).getWidth() >= 190);
            assertTrue(((TextView) root.findViewById(R.id.countdown_chronometer)).getTextSize() >= 20f);
        }
    }

    @Test public void reapplyRestoresTheInsetsWhenTheHostRadiusShrinksAgain() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            View root = null;
            for (float radius : new float[]{28f, 48f, 28f, 36f, 28f}) {
                root = render(activity, activity, 216, 110, radius, "\u767d\u73ed", 8, root != null, root);
                assertGeometry(root, root, 216, 110, radius, "reapply R=" + radius);
                assertEquals(radius == 28f ? 0 : Math.max(0,
                        (int) Math.ceil(CornerInsets.safeInset(radius) + 1f) - 10), root.getPaddingLeft());
            }
        }
    }

    @Test @Config(qualifiers = "zh-rCN-xhdpi")
    public void phoneDensityUsesTheSameSafeAreaAndReadableFontBudget() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Context context = configuration(controller.get(), 2f, true);
            for (int[] size : SIZES) {
                View root = render(controller.get(), context, size[0], size[1], 40f,
                        "\u901a\u5bb5\u503c\u5b88", 3660, false, null);
                assertGeometry(root, root, size[0], size[1], 40f, "xhdpi");
            }
        }
    }

    @Test @Config(sdk = 28, application = android.app.Application.class)
    public void preAndroidTwelveStillAppliesEveryRemoteViewsLayout() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            for (int[] size : new int[][]{{110,110}, {360,110}, {156,216}, {216,216}, {360,216}, {224,320}}) {
                View root = render(controller.get(), controller.get(), size[0], size[1], 28f,
                        "\u767d\u73ed", 8, false, null);
                assertNotNull(root.findViewById(R.id.refresh_button));
                assertTrue(root.findViewById(R.id.refresh_button).isClickable());
            }
        }
    }
}