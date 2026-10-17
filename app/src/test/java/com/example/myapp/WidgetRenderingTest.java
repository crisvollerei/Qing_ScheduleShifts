package com.example.myapp;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
                    for (int[] size : new int[][]{{156,216}, {180,320}, {215,216}, {216,216}, {224,216}, {240,240}, {240,280}, {280,216}, {320,320},
                            {320,216}, {300,216}, {359,216}, {360,216}, {360,300}, {500,320}, {300,520}, {224,320}, {224,380}, {320,380}, {224,420}, {320,420}, {224,440}, {110,110}, {110,144}, {110,176},
                            {110,216}, {110,320}, {110,128}, {110,143}, {110,175}, {110,215},
                            {140,110}, {156,110}, {180,110}, {224,110}, {224,144},
                            {216,110}, {224,128}, {216,144}, {224,176}, {240,215}, {280,180}, {320,110}, {359,110}, {360,110}, {360,144}, {360,215}, {500,110}}) {
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
                                R.id.shift_status, R.id.cycle_info, R.id.progress_bar, R.id.next_shifts,
                                R.id.live_clock, R.id.countdown_label, R.id.countdown_chronometer,
                                R.id.countdown_detail}) {
                            boolean mini = MyWidgetProvider.layoutFor(size[0], size[1]) == R.layout.widget_layout_mini
                                    || MyWidgetProvider.layoutFor(size[0], size[1]) == R.layout.widget_layout_mini_wide;
                            if (id == R.id.shift_status && view.findViewById(id).getVisibility() != View.VISIBLE) continue;
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
                        try {
                            assertCompanionPresentation(view, MyWidgetProvider.showsCompanion(size[0], size[1]), variant);
                        } catch (AssertionError error) { failures.append(error.getMessage()).append("\n"); }
                    }
                }
            }
            assertEquals(failures.toString(), "", failures.toString());
        }
    }

    private Rect bounds(View root, View child) {
        Rect rectangle = new Rect(0, 0, child.getWidth(), child.getHeight());
        ((ViewGroup) root).offsetDescendantRectToMyCoords(child, rectangle);
        return rectangle;
    }

    private void assertCompanionPresentation(View root, boolean expected, String variant) {
        ImageView portrait = root.findViewById(R.id.companion_portrait);
        assertEquals(variant + " portrait visibility", expected ? View.VISIBLE : View.GONE, portrait.getVisibility());
        if (expected) {
            assertCompanionIsSeparate(root, variant);
        } else {
            float density = root.getResources().getDisplayMetrics().density;
            assertTrue(variant + " hidden portrait must not reserve a column",
                    root.findViewById(R.id.shift_time).getWidth() >= root.getWidth() - 32 * density);
        }
    }

    private void assertCompanionIsSeparate(View root, String variant) {
        ImageView portrait = root.findViewById(R.id.companion_portrait);
        assertWithinBounds(root, R.id.companion_portrait, variant);
        float density = root.getResources().getDisplayMetrics().density;
        assertTrue(variant + " must have a real side character, not a tiny avatar",
                Math.min(portrait.getWidth(), portrait.getHeight()) >= 64 * density);
        assertNull(variant + " no avatar halo", portrait.getBackground());
        assertTrue(variant + " missing companion bitmap", portrait.getDrawable() instanceof BitmapDrawable);
        Bitmap bitmap = ((BitmapDrawable) portrait.getDrawable()).getBitmap();
        assertTrue("Native companion must stay small", bitmap.getWidth() <= 320 && bitmap.getHeight() <= 320);
        assertTrue("Preserve transparent background", bitmap.hasAlpha());
        android.util.TypedValue resolved = new android.util.TypedValue();
        root.getResources().getValue(R.drawable.widget_companion, resolved, true);
        boolean dark = (root.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        assertTrue("Companion must follow the host theme, not the current shift",
                resolved.string.toString().contains(dark ? "widget_companion_night" : "widget_companion_day"));
        Rect picture = bounds(root, portrait);
        for (int id : new int[]{R.id.today_date, R.id.shift_name, R.id.shift_time, R.id.shift_status,
                R.id.cycle_info, R.id.next_shifts, R.id.live_clock, R.id.countdown_label,
                R.id.countdown_chronometer, R.id.countdown_text, R.id.countdown_detail}) {
            View information = root.findViewById(id);
            if (information.getVisibility() != View.VISIBLE) continue;
            assertFalse(variant + " companion overlaps " + root.getResources().getResourceEntryName(id),
                    Rect.intersects(picture, bounds(root, information)));
        }
    }

    @Test public void moderatelyWideCardsShowSideCharactersWithoutFullCardHeight() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            for (int[] sample : new int[][]{{110,110,0}, {180,320,0}, {215,280,0},
                    {216,216,1}, {224,216,1}, {240,240,1}, {280,216,1}, {320,216,1},
                    {215,110,0}, {216,110,1}, {224,110,1}, {320,110,1}, {359,110,1}, {360,110,1}, {500,110,1}}) {
                View view = render(controller.get(), sample[0], sample[1], false, 1.3f);
                assertCompanionPresentation(view, sample[2] == 1, sample[0] + "x" + sample[1]);
            }
        }
    }

    @Test public void resizingTheSameMiniViewReclaimsTheHiddenCharacterColumn() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Calendar now = Calendar.getInstance();
            Calendar anchor = MyWidgetProvider.debugMidnight(now);
            String[] cycle = {"白班"};
            Map<String, MyWidgetProvider.ShiftDef> defs = MyWidgetProvider.debugDefaultDefs();
            MyWidgetProvider.ShiftSlot slot = MyWidgetProvider.debugEffectiveSlot(now, anchor, cycle, defs);
            FrameLayout surface = new FrameLayout(activity);
            activity.setContentView(surface);
            View view = null;
            for (int width : new int[]{215, 216, 320, 110, 224, 180}) {
                RemoteViews remote = MyWidgetProvider.buildViews(activity, width, 110, now, slot, anchor,
                        cycle, defs, MyWidgetProvider.countdownAt(now, slot, anchor, cycle, defs));
                if (view == null) {
                    view = remote.apply(activity, surface);
                    surface.addView(view);
                } else {
                    remote.reapply(activity, view);
                }
                surface.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(110, View.MeasureSpec.EXACTLY));
                surface.layout(0, 0, width, 110);
                assertCompanionPresentation(view, width >= 216, "resize " + width);
                for (int id : new int[]{R.id.shift_time, R.id.live_clock, R.id.countdown_chronometer}) {
                    assertWithinBounds(view, id, "resize " + width);
                    assertWholeText(view, id, "resize " + width);
                }
            }
        }
    }

    @Test public void oneRemoteViewsPayloadResolvesEitherHostThemeAndRepairsAStalePortrait() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Calendar now = Calendar.getInstance();
            Calendar anchor = MyWidgetProvider.debugMidnight(now);
            String[] cycle = {"夜班"};
            Map<String, MyWidgetProvider.ShiftDef> defs = MyWidgetProvider.debugDefaultDefs();
            MyWidgetProvider.ShiftSlot slot = MyWidgetProvider.debugEffectiveSlot(now, anchor, cycle, defs);
            RemoteViews remote = MyWidgetProvider.buildViews(activity, 320, 320, now, slot, anchor,
                    cycle, defs, MyWidgetProvider.countdownAt(now, slot, anchor, cycle, defs));
            for (boolean dark : new boolean[]{false, true, false}) {
                Configuration config = new Configuration(activity.getResources().getConfiguration());
                config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                        | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
                Context host = activity.createConfigurationContext(config);
                FrameLayout surface = new FrameLayout(host);
                View view = remote.apply(host, surface);
                ImageView image = view.findViewById(R.id.companion_portrait);
                Bitmap expected = ((BitmapDrawable) host.getDrawable(dark
                        ? R.drawable.widget_companion_night : R.drawable.widget_companion_day)).getBitmap();
                assertTrue("Host theme wins over provider theme", expected.sameAs(((BitmapDrawable) image.getDrawable()).getBitmap()));
                image.setImageResource(dark ? R.drawable.widget_companion_day : R.drawable.widget_companion_night);
                remote.reapply(host, view);
                assertTrue("Reapply must not retain the old avatar", expected.sameAs(((BitmapDrawable) image.getDrawable()).getBitmap()));
            }
        }
    }

    @Test @Config(qualifiers = "zh-rCN-xhdpi")
    public void phoneDensityCompanionsRenderWithWorkingAndOvernightShifts() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Bitmap[] samples = new Bitmap[2];
            for (boolean dark : new boolean[]{false, true}) {
                Configuration config = new Configuration(activity.getResources().getConfiguration());
                config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                        | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
                config.fontScale = 1.3f;
                Context context = activity.createConfigurationContext(config);
                Calendar now = Calendar.getInstance();
                Calendar anchor = MyWidgetProvider.debugMidnight(now);
                for (String shift : new String[]{"白班", "夜班"}) {
                    for (int[] size : new int[][]{{110,110}, {156,216}, {216,110}, {224,144}, {240,180}, {216,216}, {224,216}, {240,240}, {280,216}, {320,216}, {360,110}, {360,216}, {224,320}, {320,320}, {500,320}}) {
                        View view = render(activity, context, size[0], size[1], now, anchor,
                                new String[]{shift}, MyWidgetProvider.debugDefaultDefs());
                        assertCompanionPresentation(view, MyWidgetProvider.showsCompanion(size[0], size[1]), "working phone density");
                        for (int id : new int[]{R.id.shift_name, R.id.shift_time, R.id.live_clock, R.id.countdown_chronometer}) {
                            assertWithinBounds(view, id, "working phone density");
                        }
                        for (int id : new int[]{R.id.shift_name, R.id.live_clock, R.id.countdown_chronometer}) {
                            assertWholeText(view, id, "working phone density");
                        }
                        File directory = new File("../.qa/widget-previews");
                        directory.mkdirs();
                        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
                        view.draw(new Canvas(bitmap));
                        String name = "companion-" + size[0] + "x" + size[1] + (dark ? "-dark-" : "-light-") + shift + ".png";
                        try (FileOutputStream out = new FileOutputStream(new File(directory, name))) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                        }
                        if (size[0] == 240 && size[1] == 240 && "白班".equals(shift)) {
                            samples[dark ? 1 : 0] = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                        }
                        bitmap.recycle();
                    }
                }
            }
            Bitmap comparison = Bitmap.createBitmap(samples[0].getWidth() * 2 + 24,
                    samples[0].getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(comparison);
            canvas.drawBitmap(samples[0], 0, 0, null);
            canvas.drawBitmap(samples[1], samples[0].getWidth() + 24, 0, null);
            try (FileOutputStream out = new FileOutputStream("../.qa/widget-previews/companion-width-led-themes.png")) {
                comparison.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            comparison.recycle();
            for (Bitmap sample : samples) sample.recycle();
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
            for (int[] size : new int[][]{{110,110}, {224,110}, {216,216}, {240,240}, {320,216}, {360,110}, {360,216}, {500,320}}) {
                night.startTime = "00:00";
                night.endTime = "23:59";
                night.crossDay = true;
                View view = render(activity, activity, size[0], size[1], now, anchor, new String[]{"夜班"}, defs);
                assertTrue(((TextView) view.findViewById(R.id.shift_time)).getContentDescription().toString().startsWith("昨日 "));
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
