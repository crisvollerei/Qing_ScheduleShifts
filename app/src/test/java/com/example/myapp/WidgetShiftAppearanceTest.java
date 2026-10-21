package com.example.myapp;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34}, qualifiers = "zh-rCN-mdpi", application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetShiftAppearanceTest {
    private static final String[] STYLES = {"day", "night", "rest", "noon", "morning", "evening"};
    private static final String[] NAMES = {"白班", "夜巡", "调休", "中班", "晨巡", "晚勤"};

    private Map<String, MyWidgetProvider.ShiftDef> definitions() {
        Map<String, MyWidgetProvider.ShiftDef> defs = new LinkedHashMap<>();
        String[] starts = {"08:00", "20:00", null, "14:00", "06:15", "16:30"};
        String[] ends = {"20:00", "08:00", null, "22:00", "14:45", "00:30"};
        for (int i = 0; i < STYLES.length; i++) {
            MyWidgetProvider.ShiftDef def = new MyWidgetProvider.ShiftDef();
            def.cssClass = STYLES[i];
            def.emoji = WidgetShiftAppearance.presetEmoji(STYLES[i]);
            def.startTime = starts[i]; def.endTime = ends[i]; def.isWork = i != 2;
            defs.put(NAMES[i], def);
        }
        MyWidgetProvider.ShiftDef custom = new MyWidgetProvider.ShiftDef();
        custom.emoji = "📋"; custom.cssClass = "morning";
        custom.startTime = "09:10"; custom.endTime = "18:20";
        defs.put("巡检", custom);
        return defs;
    }

    private View render(Activity activity, Context context, WidgetRenderer.Form form, int width, int height,
            String name, Map<String, MyWidgetProvider.ShiftDef> defs) {
        Calendar now = Calendar.getInstance(); now.set(2026, Calendar.OCTOBER, 21, 15, 40, 32); now.set(Calendar.MILLISECOND, 0);
        android.os.SystemClock.setCurrentTimeMillis(now.getTimeInMillis());
        Calendar anchor = MyWidgetProvider.debugMidnight(now);
        String[] cycle = {name, "晨巡", "中班", "晚勤", "夜巡", "调休", "巡检", "白班"};
        MyWidgetProvider.ShiftSlot slot = MyWidgetProvider.debugEffectiveSlot(now, anchor, cycle, defs);
        MyWidgetProvider.Countdown countdown = MyWidgetProvider.countdownAt(now, slot, anchor, cycle, defs);
        RemoteViews views = WidgetRenderer.build(context, form, width, height, now, slot, anchor, cycle, defs, countdown, 28);
        WidgetTestClock.alignCountdown(views, now, countdown);
        FrameLayout host = new FrameLayout(context); activity.setContentView(host);
        View card = views.apply(context, host); host.addView(card);
        WidgetRenderFixture.measure(host, width, height);
        return card;
    }

    @Test public void everyPresetGetsItsOwnNativeSymbolAndWebStyleColorInEveryForm() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            for (boolean dark : new boolean[]{false, true}) {
                Context context = WidgetRenderFixture.context(controller.get(), dark, 1.3f);
                Map<String, MyWidgetProvider.ShiftDef> defs = definitions();
                for (String name : defs.keySet()) for (WidgetRenderer.Form form : WidgetRenderer.Form.values()) {
                    View card = render(controller.get(), context, form, (int) form.width, (int) form.height, name, defs);
                    MyWidgetProvider.ShiftDef def = defs.get(name);
                    String tag = form + " / " + name + " / dark=" + dark;
                    ImageView symbol = card.findViewById(R.id.shift_symbol);
                    assertTrue(tag, symbol.isShown());
                    assertEquals(tag, WidgetShiftAppearance.symbol(def.emoji), Shadows.shadowOf(symbol.getDrawable()).getCreatedFromResId());
                    assertNotNull(tag, symbol.getColorFilter());
                    assertEquals(tag, View.GONE, card.findViewById(R.id.shift_emoji).getVisibility());
                    assertEquals(tag, WidgetShiftAppearance.color(context, def.cssClass), ((TextView) card.findViewById(R.id.shift_name)).getCurrentTextColor());
                    assertFalse(tag, symbol.getDrawable().getBounds().isEmpty());
                    if (form == WidgetRenderer.Form.FULL) for (int id : WidgetRenderer.WEEK_DAYS) {
                        TextView cell = card.findViewById(id);
                        assertEquals(tag, 2, cell.getLayout().getLineCount());
                        assertTrue(tag, cell.getLayout().getHeight() <= cell.getHeight());
                        for (int line = 0; line < 2; line++) assertTrue(tag,
                                cell.getLayout().getLineWidth(line) <= cell.getWidth() + .5f);
                    }
                }
            }
        }
    }

    @Test public void customAndEmptyEmojiAreNotReplacedByTheStyleDefault() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            for (String emoji : new String[]{"🛠️", "👩‍🔧", "", "☀"}) {
                Map<String, MyWidgetProvider.ShiftDef> defs = definitions();
                defs.get("中班").emoji = emoji;
                for (WidgetRenderer.Form form : WidgetRenderer.Form.values()) {
                    View card = render(controller.get(), controller.get(), form, (int) form.width, (int) form.height, "中班", defs);
                    assertEquals(View.GONE, card.findViewById(R.id.shift_symbol).getVisibility());
                    TextView custom = card.findViewById(R.id.shift_emoji);
                    assertEquals(emoji, custom.getText().toString());
                    assertEquals(emoji.isEmpty() ? View.GONE : View.VISIBLE, custom.getVisibility());
                }
            }
        }
    }

    @Test public void reapplySwitchesPresetSymbolsAndCustomEmojiWithoutStaleColors() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Map<String, MyWidgetProvider.ShiftDef> defs = definitions();
            Calendar now = Calendar.getInstance(); now.set(2026, Calendar.OCTOBER, 21, 15, 40, 32);
            android.os.SystemClock.setCurrentTimeMillis(now.getTimeInMillis());
            Calendar anchor = MyWidgetProvider.debugMidnight(now);
            FrameLayout host = new FrameLayout(activity); activity.setContentView(host);
            View card = null;
            for (String name : new String[]{"白班", "中班", "晨巡", "晚勤", "夜巡", "调休", "巡检", "巡检", "白班"}) {
                if (name.equals("巡检")) defs.get(name).emoji = "🛠️";
                String[] cycle = {name};
                MyWidgetProvider.ShiftSlot slot = MyWidgetProvider.debugEffectiveSlot(now, anchor, cycle, defs);
                MyWidgetProvider.Countdown countdown = MyWidgetProvider.countdownAt(now, slot, anchor, cycle, defs);
                RemoteViews views = WidgetRenderer.build(activity, WidgetRenderer.Form.FULL, 304, 320,
                        now, slot, anchor, cycle, defs, countdown, 28);
                WidgetTestClock.alignCountdown(views, now, countdown);
                if (card == null) { card = views.apply(activity, host); host.addView(card); }
                else views.reapply(activity, card);
                WidgetRenderFixture.measure(host, 304, 320);
                assertEquals(name, ((TextView) card.findViewById(R.id.shift_name)).getText().toString());
                assertEquals(WidgetShiftAppearance.color(activity, slot.def.cssClass),
                        ((TextView) card.findViewById(R.id.shift_name)).getCurrentTextColor());
                boolean nativeSymbol = WidgetShiftAppearance.symbol(slot.def.emoji) != 0;
                assertEquals(nativeSymbol ? View.VISIBLE : View.GONE, card.findViewById(R.id.shift_symbol).getVisibility());
                assertEquals(nativeSymbol ? View.GONE : View.VISIBLE, card.findViewById(R.id.shift_emoji).getVisibility());
            }
        }
    }

    @Test public void styleColorsStayReadableOnAllWidgetSurfacesInBothThemes() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            for (boolean dark : new boolean[]{false, true}) {
                Context context = WidgetRenderFixture.context(controller.get(), dark, 1);
                for (String style : STYLES) for (int surface : new int[]{R.color.widget_surface,
                        R.color.widget_surface_end, R.color.widget_panel, R.color.widget_badge}) {
                    double ratio = ColorUtils.calculateContrast(WidgetShiftAppearance.color(context, style), ContextCompat.getColor(context, surface));
                    assertTrue(style + " dark=" + dark + " contrast=" + ratio, ratio >= 4.5);
                }
            }
        }
    }

    @Test @Config(sdk = 34) public void nativeStylePreviewImages() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            WidgetRenderer.Form[] forms = {WidgetRenderer.Form.MINIMUM, WidgetRenderer.Form.WIDE, WidgetRenderer.Form.FULL};
            int[][] sizes = {{184, 210}, {550, 210}, {550, 450}};
            Map<String, MyWidgetProvider.ShiftDef> defs = definitions();
            for (boolean dark : new boolean[]{false, true}) {
                Context context = WidgetRenderFixture.context(controller.get(), dark, 1);
                for (String name : defs.keySet()) for (int i = 0; i < forms.length; i++) {
                    View card = render(controller.get(), context, forms[i], sizes[i][0], sizes[i][1], name, defs);
                    Bitmap image = Bitmap.createBitmap(card.getWidth() * 3, card.getHeight() * 3, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(image); canvas.scale(3, 3); card.draw(canvas);
                    String style = name.equals("巡检") ? "custom" : defs.get(name).cssClass;
                    File path = new File("build/widget-style-previews/" + (dark ? "night-" : "day-") + style + "-" + forms[i].name().toLowerCase(java.util.Locale.ROOT) + ".png");
                    path.getParentFile().mkdirs();
                    try (FileOutputStream stream = new FileOutputStream(path)) { image.compress(Bitmap.CompressFormat.PNG, 100, stream); }
                    image.recycle();
                }
            }
        }
    }
}
