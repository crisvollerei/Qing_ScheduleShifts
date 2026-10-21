package com.example.myapp;

import static org.junit.Assert.*;
import static org.robolectric.util.reflector.Reflector.reflector;

import android.app.Activity;
import android.app.AlarmManager;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.util.SizeF;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.reflector.Direct;
import org.robolectric.util.reflector.ForType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

/** Exercise the framework host, not ShadowAppWidgetHostView's simplified inflation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34}, qualifiers = "zh-rCN-mdpi", application = android.app.Application.class)
public class WidgetHostRefreshTest {
    @ForType(AppWidgetHostView.class)
    interface FrameworkHost {
        @Direct void setAppWidget(int id, AppWidgetProviderInfo info);
        @Direct void updateAppWidget(RemoteViews views);
    }

    private RemoteViews providerPayload(AppWidgetManager manager, int id) {
        Map<Integer, ?> widgets = ReflectionHelpers.getField(Shadows.shadowOf(manager), "widgetInfos");
        RemoteViews views = ReflectionHelpers.getField(widgets.get(id), "lastRemoteViews");
        Parcel parcel = Parcel.obtain();
        try {
            views.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return RemoteViews.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    private void measure(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private void tap(View root, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0);
        try {
            assertTrue(root.dispatchTouchEvent(down));
            assertTrue(root.dispatchTouchEvent(up));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    @Test public void refreshOnAParcelledResponsiveHostAcknowledgesAndRebindsTheTimer() {
        for(int index=0;index<MyWidgetProvider.PROVIDERS.length;index++) checkProvider(index);
    }

    @SuppressWarnings("unchecked")
    private void checkProvider(int index) {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        AppWidgetManager manager = AppWidgetManager.getInstance(activity);
        WidgetRenderer.Form form=MyWidgetProvider.FORMS[index];
        Class<? extends MyWidgetProvider> provider=(Class<? extends MyWidgetProvider>)MyWidgetProvider.PROVIDERS[index];
        int id = Shadows.shadowOf(manager).createWidget(provider, form.layout);
        int w=(int)form.width,h=(int)form.height;
        int wide=Math.round(w*1.3f),tall=Math.round(h*1.3f);
        Bundle options = new Bundle();
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wide);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, Build.VERSION.SDK_INT>=31?tall:h);
        if (Build.VERSION.SDK_INT >= 31) {
            options.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES,
                    new ArrayList<>(Arrays.asList(new SizeF(w,h),new SizeF(wide,h),new SizeF(w,tall))));
        }
        manager.updateAppWidgetOptions(id, options);
        AppWidgetHostView host = new AppWidgetHostView(activity);
        AppWidgetProviderInfo info = new AppWidgetProviderInfo();
        info.provider = new ComponentName(activity, provider);
        ReflectionHelpers.setField(info, "providerInfo", ReflectionHelpers.callInstanceMethod(activity.getPackageManager(),
                "getReceiverInfo", ReflectionHelpers.ClassParameter.from(ComponentName.class, info.provider),
                ReflectionHelpers.ClassParameter.from(int.class, 0)));
        reflector(FrameworkHost.class, host).setAppWidget(id, info);
        // Launchers can add their own padding and measure differently from the reported options.
        host.setPadding(8, 12, 10, 6);
        FrameLayout surface = new FrameLayout(activity);
        surface.addView(host);
        activity.setContentView(surface);
        int[] previousLayouts = {R.layout.widget_card_minimum, R.layout.widget_card_compact,
                R.layout.widget_card_wide, R.layout.widget_card_full};
        assertNotEquals("Upgrade must use a new layout ID instead of reusing an old tree", previousLayouts[index], form.layout);
        host.setLayoutParams(new FrameLayout.LayoutParams(w, h));
        reflector(FrameworkHost.class, host).updateAppWidget(new RemoteViews(activity.getPackageName(), previousLayouts[index]));
        measure(surface, w, h);
        View previousRoot = host.findViewById(R.id.widget_container);
        assertNotNull(previousRoot);

        if (form == WidgetRenderer.Form.WIDE || form == WidgetRenderer.Form.FULL) {
            int previousFixed = form == WidgetRenderer.Form.WIDE ? R.layout.widget_fixed_wide : R.layout.widget_fixed_full;
            assertNotEquals("The 0.2.6 layout IDs must also trigger reinflation", previousFixed, form.layout);
            reflector(FrameworkHost.class, host).updateAppWidget(new RemoteViews(activity.getPackageName(), previousFixed));
            measure(surface, w, h);
            previousRoot = host.findViewById(R.id.widget_container);
        }

        int[] previousHandbooks = {R.layout.widget_handbook_minimum, R.layout.widget_handbook_minimum,
                R.layout.widget_handbook_wide, R.layout.widget_handbook_full};
        assertNotEquals("Typography upgrades must reinflate XML styles", previousHandbooks[index], form.layout);
        reflector(FrameworkHost.class, host).updateAppWidget(
                new RemoteViews(activity.getPackageName(), previousHandbooks[index]));
        measure(surface, w, h);
        previousRoot = host.findViewById(R.id.widget_container);
        assertNotNull(previousRoot);

        // Legacy hosts select by orientation; modern hosts also select by measured size.
        int[][] sizes = Build.VERSION.SDK_INT >= 31
                ? new int[][]{{w,h}, {w+64,h+48}, {wide,h}, {w,tall}, {w,h}}
                : new int[][]{{w,h}, {w+64,h+48}, {w,h}};
        for (int[] size : sizes) {
            MyWidgetProvider.refreshAll(activity);
            host.setLayoutParams(new FrameLayout.LayoutParams(size[0], size[1]));
            measure(surface, size[0], size[1]);
            reflector(FrameworkHost.class, host).updateAppWidget(providerPayload(manager, id));
            measure(surface, size[0], size[1]);
            assertNotNull("Framework host must load the real widget",
                    host.findViewById(R.id.widget_container));
            assertNotSame("Old layout IDs must trigger reinflation during an in-place upgrade",
                    previousRoot, host.findViewById(R.id.widget_container));
            assertEquals("A chosen widget never changes composition while resizing",
                    form.layout,providerPayload(manager,id).getLayoutId());
            assertEquals(form==WidgetRenderer.Form.FULL||form==WidgetRenderer.Form.WIDE,
                    host.findViewById(R.id.companion_portrait)!=null);

            // 每次重新绑定都必须换一个新的 Chronometer 实例。
            // 这是计时器从"走时中断"里恢复的唯一可靠方式：对同一个实例重复
            // start() 恢复不了（会一直停在旧值），换实例才行。
            Chronometer previous = host.findViewById(R.id.countdown_chronometer);
            for (int round = 0; round < 3; round++) {
                reflector(FrameworkHost.class, host).updateAppWidget(providerPayload(manager, id));
                measure(surface, size[0], size[1]);
                Chronometer current = host.findViewById(R.id.countdown_chronometer);
                assertNotSame("Every rebind must replace the timer instance", previous, current);
                previous = current;
            }
            AlarmManager alarms = (AlarmManager) activity.getSystemService(Activity.ALARM_SERVICE);
            assertEquals("Refresh must not enqueue animation alarms", 3,
                    Shadows.shadowOf(alarms).getScheduledAlarms().size());
        }
        if (form == WidgetRenderer.Form.WIDE && Build.VERSION.SDK_INT >= 31) {
            host.setLayoutParams(new FrameLayout.LayoutParams(w,h));
            host.setPadding(20,20,20,20);
            MyWidgetProvider.refreshAll(activity);
            measure(surface,w,h);
            reflector(FrameworkHost.class,host).updateAppWidget(providerPayload(manager,id));
            measure(surface,w,h);
            assertEquals("Measured smaller content must select the two-column variant",View.GONE,
                    host.findViewById(R.id.portrait_position).getVisibility());
            assertEquals(2,host.findViewById(R.id.countdown_block).getPaddingLeft());
            host.setPadding(0,0,0,0);
            measure(surface,w,h);
            assertEquals("Expanding the same host restores its portrait",View.VISIBLE,
                    host.findViewById(R.id.portrait_position).getVisibility());
            assertEquals("Reapply must restore the normal panel inset",7,
                    host.findViewById(R.id.countdown_block).getPaddingLeft());
        }
        activity.finish();
    }
}
