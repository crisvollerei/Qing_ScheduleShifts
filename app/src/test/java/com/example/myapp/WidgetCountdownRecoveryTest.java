package com.example.myapp;

import static org.junit.Assert.*;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextClock;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.util.ReflectionHelpers;

import java.util.Calendar;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34}, qualifiers = "zh-rCN-mdpi", application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetCountdownRecoveryTest {
    private static final String WORK = "\u767d\u73ed";
    private static final String REST = "\u4f11\u606f";

    private RemoteViews views(Activity activity, long target, boolean work) {
        Calendar now = Calendar.getInstance();
        Calendar anchor = MyWidgetProvider.debugMidnight(now);
        String[] cycle = {work ? WORK : REST};
        Map<String, MyWidgetProvider.ShiftDef> defs = MyWidgetProvider.debugDefaultDefs();
        MyWidgetProvider.ShiftSlot slot = MyWidgetProvider.debugEffectiveSlot(now, anchor, cycle, defs);
        MyWidgetProvider.Countdown countdown = new MyWidgetProvider.Countdown(
                R.string.widget_countdown_to_off, target, work ? WORK : "");
        return MyWidgetProvider.buildViews(activity, 360, 110, now, slot, anchor, cycle, defs, countdown);
    }

    private View attach(Activity activity, RemoteViews views) {
        FrameLayout host = new FrameLayout(activity);
        activity.setContentView(host);
        View root = views.apply(activity, host);
        host.addView(root);
        host.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(110, View.MeasureSpec.EXACTLY));
        host.layout(0, 0, 360, 110);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return root;
    }

    private void advanceSeconds(int seconds) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(seconds, TimeUnit.SECONDS);
    }

    @Test public void actualRemoteViewsOutputIsZeroPaddedWithoutExtraLeftPadding() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            long[] seconds = {0, 9, 59, 60, 3599, 3600, 3601, 8230, 35999, 36000, 360001, 432000};
            for (long remaining : seconds) {
                View root = attach(activity, views(activity, System.currentTimeMillis() + remaining * 1000 + 500, true));
                Chronometer timer = root.findViewById(R.id.countdown_chronometer);
                assertEquals("remaining=" + remaining, MyWidgetProvider.formatHms(remaining * 1000),
                        timer.getText().toString());
                assertEquals(0, timer.getPaddingLeft());
                assertEquals(0, root.findViewById(R.id.countdown_text).getPaddingLeft());
            }
        }
    }

    @Test public void reapplyRestartsALostTickWithoutRecreatingTheView() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            long target = System.currentTimeMillis() + 8_230_000;
            View root = attach(activity, views(activity, target, true));
            Chronometer timer = root.findViewById(R.id.countdown_chronometer);
            TextClock clock = root.findViewById(R.id.live_clock);
            Runnable tick = ReflectionHelpers.getField(timer, "mTickRunnable");
            assertTrue(ReflectionHelpers.<Boolean>getField(timer, "mRunning"));
            timer.removeCallbacks(tick);

            String frozen = timer.getText().toString();
            String clockBefore = clock.getText().toString();
            advanceSeconds(3);
            assertEquals(frozen, timer.getText().toString());
            assertNotEquals(clockBefore, clock.getText().toString());
            // Repeating start alone leaves a lost callback stalled, as in the old binding.
            timer.start();
            advanceSeconds(3);
            assertEquals(frozen, timer.getText().toString());

            views(activity, target, true).reapply(activity, root);
            assertSame(timer, root.findViewById(R.id.countdown_chronometer));
            assertTrue(timer.isCountDown());
            assertTrue(timer.getBase() > SystemClock.elapsedRealtime());
            String recovered = timer.getText().toString();
            advanceSeconds(3);
            assertNotEquals("A refresh must restore ticking, not just replace frozen text", recovered,
                    timer.getText().toString());
        }
    }

    @Test public void refreshChangesThePrefixWhenNativeOutputDropsAnHourFieldOrDigit() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            for (long remaining : new long[]{36_001_000L, 3_601_000L}) {
                long target = System.currentTimeMillis() + remaining;
                View root = attach(activity, views(activity, target, true));
                advanceSeconds(3);
                views(activity, target, true).reapply(activity, root);
                Chronometer timer = root.findViewById(R.id.countdown_chronometer);
                assertEquals(MyWidgetProvider.formatHms(target - System.currentTimeMillis()),
                        timer.getText().toString());
            }
        }
    }

    @Test public void countdownCanRecoverAfterAnAllRestConfiguration() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            View root = attach(activity, views(activity, System.currentTimeMillis() + 30_000, true));
            Chronometer timer = root.findViewById(R.id.countdown_chronometer);
            views(activity, 0, false).reapply(activity, root);
            assertEquals(View.GONE, timer.getVisibility());
            assertFalse(timer.isCountDown());
            views(activity, System.currentTimeMillis() + 30_000, true).reapply(activity, root);
            assertEquals(View.VISIBLE, timer.getVisibility());
            String before = timer.getText().toString();
            advanceSeconds(3);
            assertNotEquals(before, timer.getText().toString());
        }
    }
}
