package com.example.myapp;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import java.util.Calendar;
import java.util.Map;

final class WidgetRenderFixture {
    static final int[][] SIZES = {{110,110}, {140,168}, {184,184}, {224,168}, {266,210},
            {360,128}, {360,168}, {224,320}, {304,320}, {320,336}, {320,422},
            {110,400}, {180,420}, {280,110}, {500,110}, {500,320}, {300,520}};

    static Context context(Activity activity, boolean dark, float font) {
        Configuration config = new Configuration(activity.getResources().getConfiguration());
        config.fontScale = font;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return activity.createConfigurationContext(config);
    }

    // Sample selection is test-only; installed widgets always retain their chosen provider form.
    static WidgetRenderer.Form sampleForm(int width, int height) {
        if (width >= 280 && width >= height * 1.6f) return WidgetRenderer.Form.WIDE;
        if (width >= 224 && height >= 304) return WidgetRenderer.Form.FULL;
        if (width < 168 || height < 168) return WidgetRenderer.Form.MINIMUM;
        return WidgetRenderer.Form.COMPACT;
    }

    static RemoteViews views(Context context, int width, int height, String name, float radius, long duration) {
        return views(context, sampleForm(width,height), width,height,name,radius,duration);
    }

    static RemoteViews views(Context context, WidgetRenderer.Form form, int width, int height, String name, float radius, long duration) {
        Calendar now = Calendar.getInstance();
        now.set(2026, Calendar.OCTOBER, 19, 20, 52, 39);
        now.set(Calendar.MILLISECOND, 0);
        android.os.SystemClock.setCurrentTimeMillis(now.getTimeInMillis());
        Calendar anchor = MyWidgetProvider.debugMidnight(now);
        Map<String, MyWidgetProvider.ShiftDef> defs = MyWidgetProvider.debugDefaultDefs();
        if (!defs.containsKey(name)) defs.put(name, defs.get("白班"));
        String[] cycle = name.equals("休息") ? new String[]{name}
                : new String[]{name, "夜班", "休息", "白班", "夜班", "休息", "休息", "休息"};
        MyWidgetProvider.ShiftSlot slot = MyWidgetProvider.debugEffectiveSlot(now, anchor, cycle, defs);
        MyWidgetProvider.Countdown countdown = MyWidgetProvider.countdownAt(now, slot, anchor, cycle, defs);
        if (duration >= 0) countdown = new MyWidgetProvider.Countdown(R.string.widget_countdown_to_work,
                now.getTimeInMillis() + duration, name);
        RemoteViews views = MyWidgetProvider.buildViews(context, form, width, height, now, slot, anchor, cycle, defs,
                countdown, radius);
        WidgetTestClock.alignCountdown(views, now, countdown);
        return views;
    }

    static View render(Activity activity, Context context, int width, int height, String name,
            float radius, long duration) {
        return render(activity,context,sampleForm(width,height),width,height,name,radius,duration);
    }

    static View render(Activity activity, Context context, WidgetRenderer.Form form, int width, int height, String name,
            float radius, long duration) {
        FrameLayout host = new FrameLayout(context);
        activity.setContentView(host);
        View root = views(context, form, width, height, name, radius, duration).apply(context, host);
        float density = context.getResources().getDisplayMetrics().density;
        host.addView(root, new FrameLayout.LayoutParams(Math.round(width*density), Math.round(height*density)));
        measure(host, Math.round(width*density), Math.round(height*density));
        return root;
    }

    static void measure(View host, int width, int height) {
        host.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        host.layout(0, 0, width, height);
    }

    static Rect bounds(View root, View child) {
        Rect rect = new Rect(0, 0, child.getWidth(), child.getHeight());
        ((ViewGroup) root).offsetDescendantRectToMyCoords(child, rect);
        return rect;
    }

    static Rect bounds(View root, int id) { return bounds(root, root.findViewById(id)); }
}
