package com.example.myapp;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;

/** A small lock-screen alarm surface using the web app's daylight/night palette. */
public class WorkAlarmActivity extends Activity {
    static final String CLOSED = "com.example.myapp.WORK_ALARM_CLOSED";
    private boolean closing;
    private final BroadcastReceiver closed = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { finish(); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ContextCompat.registerReceiver(this, closed, new IntentFilter(CLOSED), ContextCompat.RECEIVER_NOT_EXPORTED);
        render();
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); render(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void render() {
        boolean night = "dark".equals(WorkReminders.prefs(this).getString("theme", "light"));
        int bg = Color.parseColor(night ? "#11182b" : "#f6f5fa");
        int ink = Color.parseColor(night ? "#e7ecfa" : "#39354c");
        int accent = Color.parseColor(night ? "#4b6c9d" : "#8064a9");
        getWindow().setStatusBarColor(bg); getWindow().setNavigationBarColor(bg);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER); root.setPadding(dp(28), dp(32), dp(28), dp(32)); root.setBackgroundColor(bg);
        TextView label = new TextView(this); label.setText("清蒸排班 · 上班提醒"); label.setTextColor(ink); label.setTextSize(15);
        root.addView(label);
        TextView title = new TextView(this); title.setText("准备出发，\n从容上班。"); title.setTextColor(ink);
        title.setTextSize(32); title.setTypeface(Typeface.create("serif", Typeface.NORMAL)); title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(28), 0, dp(24)); root.addView(title);
        TextView detail = new TextView(this); detail.setText(getIntent().getStringExtra("detail")); detail.setTextColor(ink);
        detail.setTextSize(17); detail.setGravity(Gravity.CENTER); root.addView(detail);
        TextView note = new TextView(this); note.setText("闹钟最多持续 5 分钟"); note.setTextColor(ink); note.setTextSize(13);
        note.setPadding(0, dp(12), 0, dp(32)); root.addView(note);
        Button stop = new Button(this); stop.setText("停止闹钟"); stop.setTextColor(Color.WHITE); stop.setAllCaps(false);
        GradientDrawable face = new GradientDrawable(); face.setColor(accent); face.setCornerRadius(dp(12)); stop.setBackground(face);
        root.addView(stop, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
        stop.setOnClickListener(v -> { stopService(new Intent(this, WorkAlarmService.class)); finish(); });
        setContentView(root);
    }
    @Override protected void onResume() {
        super.onResume();
        if (!WorkAlarmService.ringing) finish();
    }
    @Override public void onBackPressed() { finish(); }
    @Override public void finish() {
        if (closing) return;
        closing = true;
        // Hand keyguard control back before removing the window that covered it.
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(false); setTurnScreenOn(false); }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        if (isTaskRoot()) finishAndRemoveTask();
        else super.finish();
    }
    @Override protected void onDestroy() { unregisterReceiver(closed); super.onDestroy(); }
}
