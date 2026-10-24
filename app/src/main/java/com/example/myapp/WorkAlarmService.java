package com.example.myapp;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import androidx.core.app.NotificationCompat;

/** Foreground only while ringing; no permanent polling service. */
public class WorkAlarmService extends Service {
    static final String STOP = "com.example.myapp.STOP_WORK_ALARM";
    static final int NOTICE = 4101;
    static final long RING_MS = 5 * 60000L;
    static volatile boolean ringing;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wake;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private final AudioManager.OnAudioFocusChangeListener focusChanges = change -> { };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        release();
        ringing = true;
        String name = intent.getStringExtra("name");
        boolean test = intent.getBooleanExtra("test", false);
        long start = intent.getLongExtra("start", 0);
        String detail = test ? "这是测试闹钟，不会改变排班" : ReminderSchedule.format(start) + " · " + name;
        WorkReminders.ensureChannel(this);
        PendingIntent screen = PendingIntent.getActivity(this, 4102,
                new Intent(this, WorkAlarmActivity.class).putExtra("detail", detail)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 4103, new Intent(this, WorkAlarmService.class).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, WorkReminders.CHANNEL)
                .setSmallIcon(R.drawable.ic_reminder).setContentTitle(test ? "测试闹钟" : "准备上班了")
                .setContentText(detail).setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true).setContentIntent(screen).addAction(0, "停止闹钟", stop);
        if (WorkReminders.fullScreenAllowed(this)) builder.setFullScreenIntent(screen, true);
        startForeground(NOTICE, builder.build());
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":work-alarm");
        wake.acquire(RING_MS + 30000L);
        if (WorkReminders.prefs(this).getBoolean("sound", true)) {
            audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                        .setOnAudioFocusChangeListener(focusChanges).build();
                audio.requestAudioFocus(focus);
            } else audio.requestAudioFocus(focusChanges, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            play(false);
        }
        if (WorkReminders.prefs(this).getBoolean("vibrate", true)) {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            long[] pattern = {0, 700, 500};
            if (vibrator != null && vibrator.hasVibrator()) {
                AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build();
                if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), attributes);
                else vibrator.vibrate(pattern, 0, attributes);
            }
        }
        WorkReminders.prefs(this).edit().putString("ringingDetail", detail).apply();
        handler.postDelayed(this::stopSelf, RING_MS);
        return START_NOT_STICKY;
    }
    private void play(boolean fallback) {
        try {
            String selected = WorkReminders.prefs(this).getString("ringtone", "");
            Uri uri = fallback || selected.isEmpty() ? RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) : Uri.parse(selected);
            if (player != null) player.release();
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            player.setDataSource(this, uri); player.setLooping(true);
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnErrorListener((media, what, extra) -> {
                if (!fallback) play(true); else soundFailed();
                return true;
            });
            player.prepareAsync();
        } catch (Exception unavailable) {
            if (!fallback) play(true); else soundFailed();
        }
    }
    private void soundFailed() {
        WorkReminders.prefs(this).edit().putString("lastFailure", "闹钟铃声播放失败，请检查系统铃声、音量并重新测试").apply();
    }
    private void release() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
        if (vibrator != null) { vibrator.cancel(); vibrator = null; }
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
        if (audio != null) {
            if (Build.VERSION.SDK_INT >= 26 && focus != null) audio.abandonAudioFocusRequest(focus);
            else audio.abandonAudioFocus(focusChanges);
            audio = null; focus = null;
        }
    }
    @Override public void onDestroy() {
        ringing = false;
        release(); stopForeground(true);
        WorkReminders.prefs(this).edit().remove("ringingDetail").apply();
        sendBroadcast(new Intent(WorkAlarmActivity.CLOSED).setPackage(getPackageName()));
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
