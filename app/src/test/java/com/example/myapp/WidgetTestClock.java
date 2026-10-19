package com.example.myapp;

import android.os.Build;
import android.os.SystemClock;
import android.widget.RemoteViews;

import java.util.Calendar;

/** Keep fixed-date layout samples independent of the machine's real wall clock. */
final class WidgetTestClock {
    private WidgetTestClock() { }

    static void alignCountdown(RemoteViews views, Calendar sample, MyWidgetProvider.Countdown countdown) {
        if (Build.VERSION.SDK_INT < 24 || countdown.target == 0) return;
        // Robolectric's setCurrentTimeMillis moves TextClock, not java.lang.System's wall time.
        long remaining = Math.max(0, countdown.target - sample.getTimeInMillis());
        String format = MyWidgetProvider.countdownFormat(sample.getTimeInMillis(), countdown.target);
        views.setString(R.id.countdown_chronometer, "setFormat", format);
        views.setChronometer(R.id.countdown_chronometer,
                SystemClock.elapsedRealtime() + remaining + 500,
                format, true);
    }
}
