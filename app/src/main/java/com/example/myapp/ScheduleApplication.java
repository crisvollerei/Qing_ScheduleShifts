package com.example.myapp;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;

import androidx.core.content.ContextCompat;

/** Recover immediately on unlock, and rebind resources when system night mode changes. */
public class ScheduleApplication extends Application {
    private final BroadcastReceiver visibleRefresh = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            MyWidgetProvider.refreshAll(context);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_TIME_TICK);
        ContextCompat.registerReceiver(this, visibleRefresh, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        MyWidgetProvider.refreshAll(this);
    }
}
