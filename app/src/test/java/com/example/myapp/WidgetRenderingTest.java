package com.example.myapp;
import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Context;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
import java.io.*;
import java.util.Calendar;
import java.util.Map;
import java.util.concurrent.TimeUnit;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, qualifiers="zh-rCN-mdpi", application=android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetRenderingTest {
    private void assertCore(View root) {
        for(int id:new int[]{R.id.today_date,R.id.shift_name,R.id.live_clock,R.id.countdown_label,R.id.refresh_touch_target}) {
            View view=root.findViewById(id);
            assertEquals(View.VISIBLE,view.getVisibility());
            assertTrue(view.isShown());
            assertTrue(view.getWidth()>0 && view.getHeight()>0);
        }
        TextView timer=root.findViewById(R.id.countdown_chronometer);
        TextView clock=root.findViewById(R.id.live_clock);
        assertTrue(timer.getText().toString(),timer.getText().toString().matches("[0-9]{2}:[0-9]{2}:[0-9]{2}"));
        assertTrue("Countdown must dominate the current time",timer.getTextSize()>=clock.getTextSize()*1.6f);
        assertEquals(Gravity.LEFT,timer.getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK);
        assertEquals(0,timer.getLayout().getEllipsisCount(0));
        if(root.findViewById(R.id.week_title)!=null) {
            assertEquals(clock.getTextSize(),((TextView)root.findViewById(R.id.week_title)).getTextSize(),0.01f);
        }
    }
    @Test public void allSupportedShapesShareTheNewHierarchyInBothThemes() {
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()) {
            for(boolean dark:new boolean[]{false,true}) for(int[] size:WidgetRenderFixture.SIZES) {
                Context context=WidgetRenderFixture.context(c.get(),dark,1.3f);
                View root=WidgetRenderFixture.render(c.get(),context,size[0],size[1],"白班",28,-1);
                assertCore(root);
                assertNull("The supporting clock has no separate panel",root.findViewById(R.id.clock_row).getBackground());
            }
        }
    }
    @Test public void fullCardUsesActualWeekAndCompactCycleInsteadOfTheOldTimePanel() {
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()) {
            View root=WidgetRenderFixture.render(c.get(),c.get(),304,320,"白班",28,-1);
            assertCore(root);
            assertEquals("周期 1/8",((TextView)root.findViewById(R.id.cycle_info)).getText().toString());
            assertEquals("20\n🌙",((TextView)root.findViewById(R.id.week_day_0)).getText().toString());
            assertTrue(root.findViewById(R.id.week_day_0).getContentDescription().toString().contains("夜班"));
            assertTrue(((TextView)root.findViewById(R.id.countdown_detail)).getText().toString().contains("夜班"));
            assertEquals(28,root.findViewById(R.id.progress_bar).getWidth());
            assertEquals(80,root.findViewById(R.id.companion_portrait).getWidth());
        }
    }
    @Test public void nativeTimersContinueWithoutProviderUpdatesInAllFourCompositions() {
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()) {
            for(int[] size:new int[][]{{110,110},{184,184},{360,128},{304,320}}) {
                View root=WidgetRenderFixture.render(c.get(),c.get(),size[0],size[1],"白班",28,-1);
                Chronometer timer=root.findViewById(R.id.countdown_chronometer);
                TextClock clock=root.findViewById(R.id.live_clock);
                String a=timer.getText().toString(), b=clock.getText().toString();
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(2,TimeUnit.SECONDS);
                assertNotEquals(a,timer.getText().toString()); assertNotEquals(b,clock.getText().toString());
            }
        }
    }
    @Test public void restAndFiveDigitCountdownsHaveNoClippedNumbers() {
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()) {
            for(int[] size:new int[][]{{110,110},{184,184},{360,128},{304,320}}) {
                View root=WidgetRenderFixture.render(c.get(),c.get(),size[0],size[1],"休息",28,-1);
                assertEquals(View.VISIBLE,root.findViewById(R.id.countdown_text).getVisibility());
                assertEquals("—",((TextView)root.findViewById(R.id.countdown_text)).getText().toString());
                root=WidgetRenderFixture.render(c.get(),c.get(),size[0],size[1],"白班",28,3659L*86400000L);
                TextView timer=root.findViewById(R.id.countdown_chronometer);
                assertTrue(timer.getText().toString(),timer.getText().toString().matches("[0-9]{5}:[0-9]{2}:[0-9]{2}"));
                assertTrue(timer.getPaint().measureText(timer.getText().toString())<=timer.getWidth()+1);
            }
        }
    }
    @Test public void previousNightsWorkRangeAndCountdownStayOnTheEffectiveShift() {
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()) {
            Calendar now=Calendar.getInstance();
            now.set(2026,Calendar.OCTOBER,20,1,10,0);now.set(Calendar.MILLISECOND,0);
            android.os.SystemClock.setCurrentTimeMillis(now.getTimeInMillis());
            Calendar anchor=MyWidgetProvider.debugMidnight(now);anchor.add(Calendar.DAY_OF_YEAR,-1);
            Map<String,MyWidgetProvider.ShiftDef> defs=MyWidgetProvider.debugDefaultDefs();
            String[] cycle={"夜班","休息"};
            MyWidgetProvider.ShiftSlot slot=MyWidgetProvider.debugEffectiveSlot(now,anchor,cycle,defs);
            MyWidgetProvider.Countdown countdown=MyWidgetProvider.countdownAt(now,slot,anchor,cycle,defs);
            for(int[] size:new int[][]{{184,184},{360,128},{304,320}}) {
                RemoteViews views=MyWidgetProvider.buildViews(c.get(),WidgetRenderFixture.sampleForm(size[0],size[1]),size[0],size[1],now,slot,anchor,cycle,defs,countdown,28);
                WidgetTestClock.alignCountdown(views,now,countdown);
                FrameLayout host=new FrameLayout(c.get());c.get().setContentView(host);
                View root=views.apply(c.get(),host);host.addView(root);
                WidgetRenderFixture.measure(host,size[0],size[1]);
                assertEquals("夜班",((TextView)root.findViewById(R.id.shift_name)).getText().toString());
                assertEquals("昨 20:00–今 08:00",root.findViewById(R.id.shift_time).getContentDescription().toString());
                assertEquals("距下班",((TextView)root.findViewById(R.id.countdown_label)).getText().toString());
                assertEquals("06:50:00",((TextView)root.findViewById(R.id.countdown_chronometer)).getText().toString());
            }
        }
    }
    @Test public void longestSupportedCycleKeepsItsCompactPositionAndFullAccessibleDescription() {
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()) {
            Calendar now=Calendar.getInstance();now.set(2026,Calendar.OCTOBER,19,12,0,0);now.set(Calendar.MILLISECOND,0);
            android.os.SystemClock.setCurrentTimeMillis(now.getTimeInMillis());
            Calendar anchor=MyWidgetProvider.debugMidnight(now);anchor.add(Calendar.DAY_OF_YEAR,-3659);
            String[] cycle=new String[3660];java.util.Arrays.fill(cycle,"白班");
            Map<String,MyWidgetProvider.ShiftDef> defs=MyWidgetProvider.debugDefaultDefs();
            MyWidgetProvider.ShiftSlot slot=MyWidgetProvider.debugEffectiveSlot(now,anchor,cycle,defs);
            MyWidgetProvider.Countdown countdown=MyWidgetProvider.countdownAt(now,slot,anchor,cycle,defs);
            for(int width:new int[]{224,304}) {
                RemoteViews views=MyWidgetProvider.buildViews(c.get(),WidgetRenderer.Form.FULL,width,320,now,slot,anchor,cycle,defs,countdown,28);
                WidgetTestClock.alignCountdown(views,now,countdown);
                FrameLayout host=new FrameLayout(c.get());c.get().setContentView(host);
                View root=views.apply(c.get(),host);host.addView(root);
                WidgetRenderFixture.measure(host,width,320);
                TextView position=root.findViewById(R.id.cycle_info);
                assertEquals("周期 3660/3660",position.getText().toString());
                assertTrue(position.getContentDescription().toString(),position.getContentDescription().toString().contains("3660"));
                assertTrue(position.getPaint().measureText(position.getText().toString())<=position.getWidth()+1);
                assertEquals(3660,((ProgressBar)root.findViewById(R.id.progress_bar)).getProgress());
            }
        }
    }
    @Test public void onePayloadCanReloadItsBackgroundAndPortraitForAnotherHostTheme() {
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()) {
            RemoteViews views=WidgetRenderFixture.views(c.get(),304,320,"白班",28,-1);
            Context light=WidgetRenderFixture.context(c.get(),false,1),dark=WidgetRenderFixture.context(c.get(),true,1);
            View a=views.apply(light,new FrameLayout(light)),b=views.apply(dark,new FrameLayout(dark));
            assertNotEquals(((TextView)a.findViewById(R.id.live_clock)).getCurrentTextColor(),((TextView)b.findViewById(R.id.live_clock)).getCurrentTextColor());
            android.util.TypedValue value=new android.util.TypedValue();
            dark.getResources().getValue(R.drawable.widget_companion,value,true);
            assertTrue(value.string.toString(),value.string.toString().contains("widget_companion_night"));
        }
    }
    private void drawCard(Activity activity, Canvas board, Context context, int w,int h,int x,int y) {
        View root=WidgetRenderFixture.render(activity,context,w,h,"白班",28,-1);
        assertCore(root);
        Bitmap bitmap=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap)); board.drawBitmap(bitmap,x,y,null); bitmap.recycle();
    }
    @Test public void initialPickerLayoutsAlreadyContainTheReferenceGroupGeometry() {
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()) {
            for(WidgetRenderer.Form form:WidgetRenderer.Form.values()) {
                FrameLayout host=new FrameLayout(c.get());c.get().setContentView(host);
                View root=new RemoteViews(c.get().getPackageName(),form.layout).apply(c.get(),host);
                host.addView(root);WidgetRenderFixture.measure(host,(int)form.width,(int)form.height);
                Rect hero=WidgetRenderFixture.bounds(root,R.id.hero_section);
                Rect expected=form==WidgetRenderer.Form.MINIMUM?new Rect(12,34,116,60)
                        :form==WidgetRenderer.Form.COMPACT?new Rect(16,54,168,90)
                        :form==WidgetRenderer.Form.WIDE?new Rect(16,56,140,112):new Rect(20,78,284,158);
                assertEquals(expected,hero);
                assertEquals(48,root.findViewById(R.id.refresh_touch_target).getWidth());
                assertEquals(48,root.findViewById(R.id.refresh_touch_target).getHeight());
                assertEquals(26,root.findViewById(R.id.refresh_button).getWidth());
            }
        }
    }
    @Test @Config(qualifiers="zh-rCN-xhdpi") public void exportOneFullFormUnderIndependentAxisChanges() throws Exception {
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()) {
            Context context=WidgetRenderFixture.context(c.get(),false,1);
            Bitmap board=Bitmap.createBitmap(2128,880,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(board);canvas.drawColor(Color.rgb(226,231,238));
            Paint label=new Paint(Paint.ANTI_ALIAS_FLAG);label.setColor(Color.DKGRAY);label.setTextSize(20);
            canvas.drawText("FULL 304 x 320 dp",16,30,label);
            canvas.drawText("WIDTH ONLY 400 x 320 dp",656,30,label);
            canvas.drawText("HEIGHT ONLY 304 x 400 dp",1488,30,label);
            drawCard(c.get(),canvas,context,304,320,16,48);
            drawCard(c.get(),canvas,context,400,320,656,48);
            drawCard(c.get(),canvas,context,304,400,1488,48);
            File dir=new File("../.qa/widget-previews");dir.mkdirs();
            try(FileOutputStream out=new FileOutputStream(new File(dir,"proportional-native-axis-comparison.png"))) {
                board.compress(Bitmap.CompressFormat.PNG,100,out);
            }
            board.recycle();
        }
    }
    @Test @Config(qualifiers="zh-rCN-xhdpi") public void exportNativeReferenceBoardsInLightAndDark() throws Exception {
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()) {
            for(boolean dark:new boolean[]{false,true}) {
                Context context=WidgetRenderFixture.context(c.get(),dark,dark?2:1);
                Bitmap board=Bitmap.createBitmap(1392,752,Bitmap.Config.ARGB_8888); Canvas canvas=new Canvas(board);
                canvas.drawColor(dark?Color.rgb(11,20,33):Color.rgb(226,231,238));
                Paint label=new Paint(Paint.ANTI_ALIAS_FLAG);label.setColor(dark?Color.LTGRAY:Color.DKGRAY);label.setTextSize(20);
                canvas.drawText("COMPACT 184 x 184 dp",16,30,label);drawCard(c.get(),canvas,context,184,184,16,48);
                canvas.drawText("MINIMUM 110 x 110 dp",420,76,label);drawCard(c.get(),canvas,context,110,110,420,96);
                canvas.drawText("WIDE 360 x 128 dp",16,460,label);drawCard(c.get(),canvas,context,360,128,16,480);
                canvas.drawText("FULL 304 x 320 dp",768,30,label);drawCard(c.get(),canvas,context,304,320,768,48);
                File dir=new File("../.qa/widget-previews");dir.mkdirs();
                try(FileOutputStream out=new FileOutputStream(new File(dir,"proportional-native-"+(dark?"dark":"light")+".png"))) {board.compress(Bitmap.CompressFormat.PNG,100,out);}
                board.recycle();
            }
        }
    }
}
