package com.example.myapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import androidx.core.content.ContextCompat;

public class WorkReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (WorkReminders.FIRE.equals(intent.getAction())) {
            Intent ring = WorkReminders.consume(context, intent, System.currentTimeMillis());
            if (ring != null) {
                try { ContextCompat.startForegroundService(context, ring); }
                catch (RuntimeException blocked) {
                    WorkReminders.prefs(context).edit().putString("lastFailure", "系统阻止了响铃，请检查后台限制并测试闹钟").apply();
                    Log.w("WorkReminder", "Unable to start alarm", blocked);
                }
            }
        } else {
            WorkReminders.cancelTest(context);
            WorkReminders.reconcile(context, System.currentTimeMillis());
        }
    }
}
