package com.example.myapp;

import static org.junit.Assert.*;
import android.app.Application;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.widget.Button;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ServiceController;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28,34}, application = Application.class)
public class WorkAlarmServiceTest {
    Context context;
    @After public void cleanUp() { WorkAlarmService.ringing = false; }
    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf((Application) context).grantPermissions(context.getPackageName() + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        WorkReminders.prefs(context).edit().clear().putBoolean("sound", false).putBoolean("vibrate", false).commit();
    }
    @Test public void ringingUsesAnOngoingAlarmNotificationWithAnExplicitStopAction() {
        ServiceController<WorkAlarmService> controller = Robolectric.buildService(WorkAlarmService.class).create();
        try {
            WorkAlarmService service = controller.get();
            service.onStartCommand(new Intent(context, WorkAlarmService.class).putExtra("test", true), 0, 1);
            Notification notice = Shadows.shadowOf(service).getLastForegroundNotification();
            assertNotNull(notice); assertEquals(Notification.CATEGORY_ALARM, notice.category);
            assertTrue((notice.flags & Notification.FLAG_ONGOING_EVENT) != 0);
            assertEquals("停止闹钟", notice.actions[0].title);
            assertEquals(WorkAlarmService.STOP, Shadows.shadowOf(notice.actions[0].actionIntent).getSavedIntent().getAction());
            assertTrue(WorkReminders.prefs(context).contains("ringingDetail"));
        } finally { controller.destroy(); }
        assertFalse(WorkReminders.prefs(context).contains("ringingDetail"));
    }
    @Test public void ringingStopsAutomaticallyAndIsNotRestartedWithANullIntent() {
        ServiceController<WorkAlarmService> controller = Robolectric.buildService(WorkAlarmService.class).create();
        try {
            WorkAlarmService service = controller.get();
            assertEquals(android.app.Service.START_NOT_STICKY,
                    service.onStartCommand(new Intent(context, WorkAlarmService.class).putExtra("test", true), 0, 1));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(WorkAlarmService.RING_MS + 1, TimeUnit.MILLISECONDS);
            assertTrue(Shadows.shadowOf(service).isStoppedBySelf());
            assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(null, 0, 2));
        } finally { controller.destroy(); }
    }
    @Test public void stopScreenUsesTheStoredThemeAndStopsTheService() {
        WorkReminders.prefs(context).edit().putString("ringingDetail", "测试").putString("theme", "dark").commit();
        WorkAlarmService.ringing = true;
        try (var controller = Robolectric.buildActivity(WorkAlarmActivity.class,
                new Intent(context, WorkAlarmActivity.class).putExtra("detail", "测试闹钟")).setup()) {
            WorkAlarmActivity activity = controller.get();
            assertEquals(android.graphics.Color.parseColor("#11182b"), activity.getWindow().getStatusBarColor());
            android.view.ViewGroup content = activity.findViewById(android.R.id.content);
            android.view.ViewGroup column = (android.view.ViewGroup) content.getChildAt(0);
            Button stop = (Button) column.getChildAt(column.getChildCount() - 1);
            assertEquals("停止闹钟", stop.getText()); stop.performClick();
            assertTrue(activity.isFinishing());
        }
    }
    @Test public void staleRestoredScreenDoesNotPretendTheServiceIsStillRinging() {
        WorkReminders.prefs(context).edit().putString("ringingDetail", "上次进程残留的状态").commit();
        WorkAlarmService.ringing = false;
        try (var controller = Robolectric.buildActivity(WorkAlarmActivity.class).setup()) {
            assertTrue(controller.get().isFinishing());
        }
    }
}
