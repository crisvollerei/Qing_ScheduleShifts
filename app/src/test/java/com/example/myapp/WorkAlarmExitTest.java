package com.example.myapp;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Looper;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34}, application = Application.class)
public class WorkAlarmExitTest {
    private static final int SCREEN_FLAGS = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
    private Context context;

    public static class ObservedAlarmActivity extends WorkAlarmActivity {
        int taskRemovals;
        boolean releasedBeforeRemoval;

        @Override public void finishAndRemoveTask() {
            taskRemovals++;
            releasedBeforeRemoval = !Shadows.shadowOf(this).getShowWhenLocked()
                    && !Shadows.shadowOf(this).getTurnScreenOn()
                    && (getWindow().getAttributes().flags & SCREEN_FLAGS) == 0;
            super.finishAndRemoveTask();
        }
    }

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        Shadows.shadowOf((Application) context).grantPermissions(
                context.getPackageName() + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        WorkReminders.prefs(context).edit().clear().commit();
        WorkAlarmService.ringing = true;
    }
    @After public void cleanUp() { WorkAlarmService.ringing = false; }

    private ActivityController<ObservedAlarmActivity> screen(boolean root) {
        ActivityController<ObservedAlarmActivity> controller = Robolectric.buildActivity(ObservedAlarmActivity.class,
                new Intent(context, ObservedAlarmActivity.class).putExtra("detail", "Test alarm"));
        Shadows.shadowOf(controller.get()).setIsTaskRoot(root);
        return controller.setup();
    }
    private Button stop(WorkAlarmActivity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        ViewGroup column = (ViewGroup) content.getChildAt(0);
        return (Button) column.getChildAt(column.getChildCount() - 1);
    }
    private void assertReleased(WorkAlarmActivity activity) {
        assertTrue(activity.isFinishing());
        assertFalse(Shadows.shadowOf(activity).getShowWhenLocked());
        assertFalse(Shadows.shadowOf(activity).getTurnScreenOn());
        assertEquals(0, activity.getWindow().getAttributes().flags & SCREEN_FLAGS);
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
    }

    @Test public void alarmTaskIsSeparateFromTheMainPageAndExcludedFromRecents() throws Exception {
        ActivityInfo alarm = context.getPackageManager().getActivityInfo(new ComponentName(context, WorkAlarmActivity.class), 0);
        ActivityInfo main = context.getPackageManager().getActivityInfo(new ComponentName(context, MainActivity.class), 0);
        assertEquals(context.getPackageName() + ".work_alarm", alarm.taskAffinity);
        assertNotEquals(main.taskAffinity, alarm.taskAffinity);
        assertTrue((alarm.flags & ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS) != 0);
        assertFalse(alarm.exported);
    }
    @Test public void stopButtonReleasesLockScreenBeforeRemovingTheStandaloneTask() {
        try (var controller = screen(true)) {
            ObservedAlarmActivity activity = controller.get();
            assertTrue(Shadows.shadowOf(activity).getShowWhenLocked());
            assertTrue(Shadows.shadowOf(activity).getTurnScreenOn());
            stop(activity).performClick();
            assertReleased(activity);
            assertEquals(1, activity.taskRemovals);
            assertTrue(activity.releasedBeforeRemoval);
            Intent stopped = Shadows.shadowOf(activity).getNextStoppedService();
            assertNotNull(stopped);
            assertEquals(new ComponentName(context, WorkAlarmService.class), stopped.getComponent());
        }
    }
    @Test public void notificationAndTimeoutClosureUseTheSameLockScreenCleanup() {
        try (var controller = screen(true)) {
            ObservedAlarmActivity activity = controller.get();
            WorkAlarmService.ringing = false;
            context.sendBroadcast(new Intent(WorkAlarmActivity.CLOSED).setPackage(context.getPackageName()));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertReleased(activity);
            assertEquals(1, activity.taskRemovals);
            assertTrue(activity.releasedBeforeRemoval);
        }
    }
    @Test public void staleRestoredScreenReleasesLockScreenAndRemovesItsTask() {
        WorkAlarmService.ringing = false;
        try (var controller = screen(true)) {
            assertReleased(controller.get());
            assertEquals(1, controller.get().taskRemovals);
            assertTrue(controller.get().releasedBeforeRemoval);
        }
    }
    @Test public void unexpectedSharedTaskIsNotRemovedWithTheMainPage() {
        try (var controller = screen(false)) {
            ObservedAlarmActivity activity = controller.get();
            stop(activity).performClick();
            assertReleased(activity);
            assertEquals(0, activity.taskRemovals);
        }
    }
    @Test public void duplicateClosuresRemoveTheAlarmTaskOnlyOnce() {
        try (var controller = screen(true)) {
            ObservedAlarmActivity activity = controller.get();
            stop(activity).performClick();
            context.sendBroadcast(new Intent(WorkAlarmActivity.CLOSED).setPackage(context.getPackageName()));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            activity.finish();
            assertReleased(activity);
            assertEquals(1, activity.taskRemovals);
        }
    }
    @Test public void closingAlsoClearsLegacyWindowFlags() {
        try (var controller = screen(true)) {
            ObservedAlarmActivity activity = controller.get();
            activity.getWindow().addFlags(SCREEN_FLAGS);
            activity.finish();
            assertReleased(activity);
            assertTrue(activity.releasedBeforeRemoval);
        }
    }
    @Test public void backClosesTheScreenWithoutStoppingTheRingingService() {
        try (var controller = screen(true)) {
            ObservedAlarmActivity activity = controller.get();
            activity.onBackPressed();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertReleased(activity);
            assertEquals(1, activity.taskRemovals);
            assertNull(Shadows.shadowOf(activity).getNextStoppedService());
            assertTrue(WorkAlarmService.ringing);
        }
    }
}
