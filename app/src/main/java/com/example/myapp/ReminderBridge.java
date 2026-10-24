package com.example.myapp;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.webkit.WebView;
import androidx.core.app.ActivityCompat;
import org.json.JSONObject;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serializes reminder changes off the WebView/UI threads and reports actual results. */
final class ReminderBridge {
    static final int RINGTONE_REQUEST = 4201, NOTIFICATION_REQUEST = 4202, CALENDAR_REQUEST = 4203;
    private final MainActivity activity;
    private final WebView webView;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean destroyed, pageReady;
    ReminderBridge(MainActivity activity, WebView webView) { this.activity = activity; this.webView = webView; }

    void refresh() {
        if (destroyed) return;
        worker.execute(() -> {
            WorkReminders.reconcile(activity, System.currentTimeMillis());
            activity.runOnUiThread(() -> {
                if (!destroyed && pageReady) webView.evaluateJavascript("window.QingZhengReminders?.refresh()", null);
            });
        });
    }
    void changed() { if (!destroyed) worker.execute(() -> WorkReminders.reconcile(activity, System.currentTimeMillis())); }
    void pageReady() { pageReady = true; }
    void destroy() { destroyed = true; worker.shutdown(); }

    void request(String id, String action, String raw) {
        if (destroyed || id == null || !id.matches("[a-zA-Z0-9_-]{1,60}")) return;
        if (raw == null || raw.length() > 8192) { respond(id, null, "请求过大"); return; }
        worker.execute(() -> {
            try {
                JSONObject data = new JSONObject(raw);
                long now = System.currentTimeMillis();
                JSONObject result;
                switch (action) {
                    case "state": result = WorkReminders.state(activity, now); break;
                    case "save":
                        WorkReminders.save(activity, data, now);
                        result = WorkReminders.state(activity, now); break;
                    case "test": result = new JSONObject().put("fire", WorkReminders.test(activity, now)); break;
                    case "cancelTest": WorkReminders.cancelTest(activity); result = new JSONObject(); break;
                    case "calendars": result = new JSONObject().put("calendars", ReminderCalendar.calendars(activity)); break;
                    case "calendarPreview":
                        result = ReminderCalendar.preview(activity, now, data.getLong("calendar"), integer(data, "days")); break;
                    case "calendarExport":
                        result = ReminderCalendar.export(activity, now, data.getLong("calendar"), integer(data, "days"), data.getString("token")); break;
                    case "calendarClear": result = new JSONObject().put("removed", ReminderCalendar.clear(activity)); break;
                    case "clockPreview": result = clockPreview(now); break;
                    case "clock":
                        JSONObject plan = clockPreview(now);
                        if (!plan.getString("token").equals(data.getString("token")))
                            throw new IllegalStateException("下次提醒已变化，请重新核对");
                        JSONObject entry = plan.getJSONObject("entry");
                        Calendar at = Calendar.getInstance(); at.setTimeInMillis(entry.getLong("fire"));
                        Intent clock = new Intent(AlarmClock.ACTION_SET_ALARM)
                                .putExtra(AlarmClock.EXTRA_HOUR, at.get(Calendar.HOUR_OF_DAY))
                                .putExtra(AlarmClock.EXTRA_MINUTES, at.get(Calendar.MINUTE))
                                .putExtra(AlarmClock.EXTRA_MESSAGE, "上班 · " + entry.getString("name") + " · " + entry.getString("fireText"))
                                .putExtra(AlarmClock.EXTRA_SKIP_UI, false);
                        activity.runOnUiThread(() -> launch(id, clock)); return;
                    default:
                        activity.runOnUiThread(() -> systemAction(id, action)); return;
                }
                respond(id, result, null);
            } catch (SecurityException denied) { respond(id, null, "权限不足，请检查闹钟、通知或日历授权"); }
            catch (IllegalArgumentException | IllegalStateException problem) { respond(id, null, problem.getMessage()); }
            catch (Exception failure) { respond(id, null, "操作未完成，请检查设置后重试"); }
        });
    }
    private static int integer(JSONObject data, String key) throws Exception {
        Object number = data.get(key);
        if (!(number instanceof Number) || ((Number) number).doubleValue() != ((Number) number).intValue())
            throw new IllegalArgumentException("请输入整数天数");
        return ((Number) number).intValue();
    }
    private JSONObject clockPreview(long now) throws Exception {
        List<ReminderSchedule.Entry> entries = ReminderSchedule.upcoming(activity, now, WorkReminders.lead(activity), 1);
        if (entries.isEmpty()) throw new IllegalStateException("当前周期没有可提醒的工作班次");
        return new JSONObject().put("entry", entries.get(0).json())
                .put("token", ReminderSchedule.fingerprint(entries, 0, 0, WorkReminders.lead(activity)));
    }
    private void systemAction(String id, String action) {
        try {
            Intent intent;
            switch (action) {
                case "permissionExact":
                    if (Build.VERSION.SDK_INT < 31) { respond(id, new JSONObject(), null); return; }
                    intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + activity.getPackageName())); break;
                case "permissionNotifications":
                    if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            && (!WorkReminders.prefs(activity).getBoolean("askedNotifications", false)
                            || ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS))) {
                        WorkReminders.prefs(activity).edit().putBoolean("askedNotifications", true).apply();
                        ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_REQUEST);
                        respond(id, new JSONObject(), null); return;
                    }
                    intent = new Intent(Build.VERSION.SDK_INT >= 26 ? Settings.ACTION_APP_NOTIFICATION_SETTINGS : Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    if (Build.VERSION.SDK_INT >= 26) intent.putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
                    else intent.setData(Uri.parse("package:" + activity.getPackageName()));
                    break;
                case "permissionCalendar":
                    if (WorkReminders.prefs(activity).getBoolean("askedCalendar", false)
                            && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_CALENDAR)
                            && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.WRITE_CALENDAR)) {
                        launch(id, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.getPackageName())));
                        return;
                    }
                    WorkReminders.prefs(activity).edit().putBoolean("askedCalendar", true).apply();
                    ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR}, CALENDAR_REQUEST);
                    respond(id, new JSONObject(), null); return;
                case "permissionFullScreen":
                    intent = new Intent(Build.VERSION.SDK_INT >= 34 ? Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT : Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + activity.getPackageName())); break;
                case "battery":
                    intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.getPackageName())); break;
                case "ringtone":
                    intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "上班闹钟铃声")
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(WorkReminders.prefs(activity)
                                    .getString("ringtone", RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM).toString())));
                    activity.startActivityForResult(intent, RINGTONE_REQUEST);
                    respond(id, new JSONObject(), null); return;
                default: respond(id, null, "不支持的操作"); return;
            }
            launch(id, intent);
        } catch (RuntimeException unavailable) { respond(id, null, "系统未提供此设置入口，请在手机设置中手动操作"); }
    }
    private void launch(String id, Intent intent) {
        try { activity.startActivity(intent); respond(id, new JSONObject(), null); }
        catch (RuntimeException missing) { respond(id, null, "没有可处理此操作的系统应用，请手动设置"); }
    }
    private void respond(String id, JSONObject result, String error) {
        try {
            String json = new JSONObject().put("ok", error == null).put("data", result == null ? JSONObject.NULL : result)
                    .put("error", error == null ? "" : error).toString();
            activity.runOnUiThread(() -> {
                if (!destroyed) webView.evaluateJavascript("window.QingZhengReminders?.receive(" + JSONObject.quote(id) + "," + json + ")", null);
            });
        } catch (Exception ignored) { /* JSON contains only local primitives. */ }
    }
}
