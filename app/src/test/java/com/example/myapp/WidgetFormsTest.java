package com.example.myapp;

import static org.junit.Assert.*;
import android.app.AlarmManager;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.widget.Chronometer;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=android.app.Application.class)
public class WidgetFormsTest {
    @SuppressWarnings("unchecked")
    @Test public void chosenFormsRemainDistinctAtTheSameHostSizeAndRefreshTogether() {
        Context context=RuntimeEnvironment.getApplication();
        AppWidgetManager manager=AppWidgetManager.getInstance(context);
        int[] ids=new int[4];
        for(int i=0;i<ids.length;i++) {
            ids[i]=Shadows.shadowOf(manager).createWidget((Class<? extends MyWidgetProvider>)MyWidgetProvider.PROVIDERS[i],
                    MyWidgetProvider.FORMS[i].layout);
            Bundle options=new Bundle();
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,304);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,304);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,320);
            options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,320);
            manager.updateAppWidgetOptions(ids[i],options);
        }
        assertEquals(4,MyWidgetProvider.widgetIds(context).length);
        for(int id:ids) ((TextView)Shadows.shadowOf(manager).getViewFor(id).findViewById(R.id.countdown_label)).setText("stale");
        new MyWidgetProvider().onReceive(context,new Intent(context,MyWidgetProvider.class).setAction(WidgetRefresh.ACTION_REFRESH));
        for(int i=0;i<ids.length;i++) {
            android.view.View root=Shadows.shadowOf(manager).getViewFor(ids[i]);
            assertNotEquals("stale",((TextView)root.findViewById(R.id.countdown_label)).getText().toString());
            assertEquals(i>=2,root.findViewById(R.id.companion_portrait)!=null);
            assertEquals(i==3,root.findViewById(R.id.week_title)!=null);
            assertEquals(i==2,root.findViewById(R.id.column_divider)!=null);
        }
        assertEquals(3,Shadows.shadowOf((AlarmManager)context.getSystemService(Context.ALARM_SERVICE)).getScheduledAlarms().size());
    }

    @Test public void fullOnlyWidgetKeepsRecoveryAndOtherFormsDisablingCannotCancelIt() {
        Context context=RuntimeEnvironment.getApplication();
        AppWidgetManager manager=AppWidgetManager.getInstance(context);
        int id=Shadows.shadowOf(manager).createWidget(MyWidgetProvider.Full.class,R.layout.widget_proportional_full);
        assertTrue(WidgetRefresh.hasWidgets(context));
        Chronometer before=Shadows.shadowOf(manager).getViewFor(id).findViewById(R.id.countdown_chronometer);
        Shadows.shadowOf((PowerManager)context.getSystemService(Context.POWER_SERVICE)).setIsInteractive(true);
        WidgetRefresh.recover(context);
        Chronometer after=Shadows.shadowOf(manager).getViewFor(id).findViewById(R.id.countdown_chronometer);
        assertNotSame(before,after);
        new MyWidgetProvider().onDisabled(context);
        assertEquals(3,Shadows.shadowOf((AlarmManager)context.getSystemService(Context.ALARM_SERVICE)).getScheduledAlarms().size());
    }
}
