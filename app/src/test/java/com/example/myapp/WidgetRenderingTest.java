package com.example.myapp;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.RemoteViews;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.android.controller.ActivityController;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Calendar;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "zh-rCN-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetRenderingTest {
    private View render(Activity activity, int width, int height, boolean dark, float fontScale) {
        Configuration config = new Configuration(activity.getResources().getConfiguration());
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        config.fontScale = fontScale;
        Context context = activity.createConfigurationContext(config);
        Calendar now = Calendar.getInstance();
        Calendar anchor = MyWidgetProvider.parseAnchor("2026-04-18");
        String[] cycle = {"白班", "夜班", "休息", "白班", "夜班", "休息", "休息", "休息"};
        return render(activity, context, width, height, now, anchor, cycle, MyWidgetProvider.debugDefaultDefs());
    }

    private View render(Activity activity, Context context, int width, int height,
            Calendar now, Calendar anchor, String[] cycle, Map<String, MyWidgetProvider.ShiftDef> defs) {
        android.os.SystemClock.setCurrentTimeMillis(now.getTimeInMillis());
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

    private void assertWithinBounds(View root, int id, String variant) {
        View view = root.findViewById(id);
        assertEquals(variant + " Required information must be shown: " + id, View.VISIBLE, view.getVisibility());
        assertTrue(variant + " zero-height view " + id, view.getHeight() > 0);
        int y = 0, x = 0;
        for (View current = view; current != root; current = (View) current.getParent()) {
            y += current.getTop();
            x += current.getLeft();
            View parent = (View) current.getParent();
            assertTrue(variant + " ancestor clips " + root.getResources().getResourceEntryName(id)
                    + " y=" + y + " h=" + view.getHeight() + " parent=" + parent.getHeight(),
                    y >= 0 && y + view.getHeight() <= parent.getHeight());
            assertTrue(variant + " ancestor clips width: " + root.getResources().getResourceEntryName(id),
                    x >= 0 && x + view.getWidth() <= parent.getWidth());
        }
        assertTrue(variant + " clipped: " + root.getResources().getResourceEntryName(id)
                + " y=" + y + " h=" + view.getHeight() + " root=" + root.getHeight(),
                y >= 0 && y + view.getHeight() <= root.getHeight());
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            assertTrue(variant + " Missing text: " + id, text.getText().length() > 0);
            assertTrue(variant + " Text has zero height: " + id,
                    text.getLayout() != null && text.getLayout().getLineCount() > 0);
            assertTrue(variant + " text internally clips " + root.getResources().getResourceEntryName(id)
                            + " height=" + text.getHeight() + " layout=" + text.getLayout().getHeight(),
                    text.getHeight() >= text.getLayout().getHeight()
                            + text.getCompoundPaddingTop() + text.getCompoundPaddingBottom());
        }
    }

    @Test public void allSizesAndThemesRenderWithoutClipping() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            StringBuilder failures = new StringBuilder();
            for (boolean dark : new boolean[]{false, true}) {
                for (float font : new float[]{1f, 1.3f}) {
                    for (int[] size : new int[][]{{156,216}, {180,320}, {240,280}, {320,320},
                            {320,216}, {500,320}, {300,520}, {110,110}, {110,144}, {110,176},
                            {110,216}, {110,320}, {110,128}, {110,143}, {110,175}, {110,215},
                            {140,110}, {156,110}, {180,110}, {224,110}, {224,144},
                            {240,215}, {280,180}, {320,110}, {500,110}}) {
                        String variant = size[0] + "x" + size[1] + (dark ? "-dark" : "-light") + "-font" + font;
                        View view = render(activity, size[0], size[1], dark, font);
                        File directory = new File("../.qa/widget-previews");
                        directory.mkdirs();
                        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
                        view.draw(new Canvas(bitmap));
                        try (FileOutputStream out = new FileOutputStream(new File(directory, variant + ".png"))) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                        }
                        bitmap.recycle();
                        for (int id : new int[]{R.id.today_date, R.id.shift_name, R.id.shift_emoji, R.id.shift_time,
                                R.id.cycle_info, R.id.progress_bar, R.id.next_shifts,
                                R.id.live_clock, R.id.countdown_label, R.id.countdown_chronometer,
                                R.id.countdown_detail}) {
                            boolean mini = MyWidgetProvider.layoutFor(size[0], size[1]) == R.layout.widget_layout_mini
                                    || MyWidgetProvider.layoutFor(size[0], size[1]) == R.layout.widget_layout_mini_wide;
                            if ((id == R.id.countdown_detail || (mini && (id == R.id.cycle_info || id == R.id.next_shifts)))
                                    && view.findViewById(id).getVisibility() == View.GONE) continue;
                            try {
                                assertWithinBounds(view, id, variant);
                                if (id == R.id.live_clock || id == R.id.countdown_chronometer || id == R.id.shift_emoji) {
                                    assertWholeText(view, id, variant);
                                }
                            }
                            catch (AssertionError error) { failures.append(error.getMessage()).append("\n"); }
                        }
                    }
                }
            }
            assertEquals(failures.toString(), "", failures.toString());
        }
    }

    private void assertWholeText(View root, int id, String variant) {
        TextView text = root.findViewById(id);
        int available = text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight();
        assertTrue(variant + " text clips horizontally: " + root.getResources().getResourceEntryName(id)
                + " text=" + text.getText() + " available=" + available,
                text.getPaint().measureText(text.getText().toString()) <= available);
        assertEquals(variant + " ellipsized: " + root.getResources().getResourceEntryName(id),
                0, text.getLayout().getEllipsisCount(0));
    }

    @Test public void miniLayoutsHandleOvernightRestAndFiveDigitHours() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Calendar now = Calendar.getInstance();
            Calendar anchor = MyWidgetProvider.debugMidnight(now);
            Map<String, MyWidgetProvider.ShiftDef> defs = MyWidgetProvider.debugDefaultDefs();
            MyWidgetProvider.ShiftDef night = defs.get("夜班");
            for (int[] size : new int[][]{{110,110}, {224,110}}) {
                night.startTime = "00:00";
                night.endTime = "23:59";
                night.crossDay = true;
                View view = render(activity, activity, size[0], size[1], now, anchor, new String[]{"夜班"}, defs);
                assertTrue(((TextView) view.findViewById(R.id.shift_time)).getText().toString().startsWith("昨00:00–今23:59"));
                for (int id : new int[]{R.id.shift_time, R.id.live_clock, R.id.countdown_chronometer}) {
                    assertWithinBounds(view, id, "overnight");
                    assertWholeText(view, id, "overnight");
                }
                night.startTime = "08:00";
                night.endTime = "20:00";
                night.crossDay = false;
                String[] longCycle = new String[3660];
                java.util.Arrays.fill(longCycle, "休息");
                longCycle[3659] = "夜班";
                view = render(activity, activity, size[0], size[1], now, anchor, longCycle, defs);
                assertTrue(((Chronometer) view.findViewById(R.id.countdown_chronometer)).getText().toString()
                        .matches("[0-9]{5}:[0-9]{2}:[0-9]{2}"));
                assertWithinBounds(view, R.id.countdown_chronometer, "long hours");
                assertWholeText(view, R.id.countdown_chronometer, "long hours");
                view = render(activity, activity, size[0], size[1], now, anchor, new String[]{"休息"}, defs);
                assertEquals(View.GONE, view.findViewById(R.id.countdown_chronometer).getVisibility());
                assertWithinBounds(view, R.id.countdown_text, "all rest");
                assertWithinBounds(view, R.id.countdown_label, "all rest");
            }
        }
    }

    @Test @Config(qualifiers = "zh-rCN-xhdpi")
    public void minimumAlsoFitsPhoneDensityAndLargeFonts() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            for (int[] size : new int[][]{{110,110}, {110,128}, {224,110}}) {
                View view = render(controller.get(), size[0], size[1], true, 1.3f);
                for (int id : new int[]{R.id.today_date, R.id.shift_emoji, R.id.shift_name, R.id.shift_time,
                        R.id.progress_bar, R.id.live_clock, R.id.countdown_label, R.id.countdown_chronometer}) {
                    assertWithinBounds(view, id, "phone density");
                }
                assertWholeText(view, R.id.live_clock, "phone density");
                assertWholeText(view, R.id.countdown_chronometer, "phone density");
            }
        }
    }

    @Test public void miniTimersAlsoTickWithoutProviderUpdates() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            for (int width : new int[]{110, 224}) {
                View view = render(controller.get(), width, 110, false, 1.3f);
                Chronometer countdown = view.findViewById(R.id.countdown_chronometer);
                String clockBefore = ((TextClock) view.findViewById(R.id.live_clock)).getText().toString();
                String timerBefore = countdown.getText().toString();
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(
                        2200, java.util.concurrent.TimeUnit.MILLISECONDS);
                assertNotEquals(clockBefore, ((TextClock) view.findViewById(R.id.live_clock)).getText().toString());
                assertNotEquals(timerBefore, countdown.getText().toString());
            }
        }
    }

    @Test public void timersAreHostDrivenNativeViewsWithAValidElapsedRealtimeBase() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            View view = render(controller.get(), 320, 320, false, 1);
            assertTrue(view.findViewById(R.id.live_clock) instanceof TextClock);
            Chronometer countdown = view.findViewById(R.id.countdown_chronometer);
            assertTrue(countdown.isCountDown());
            assertTrue(countdown.getBase() > android.os.SystemClock.elapsedRealtime());
            String clockBefore = ((TextClock) view.findViewById(R.id.live_clock)).getText().toString();
            String countdownBefore = countdown.getText().toString();
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(
                    2200, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertNotEquals(clockBefore, ((TextClock) view.findViewById(R.id.live_clock)).getText().toString());
            assertNotEquals(countdownBefore, countdown.getText().toString());
        }
    }
}
