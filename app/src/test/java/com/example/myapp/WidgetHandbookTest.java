package com.example.myapp;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Parcel;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.android.controller.ActivityController;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Calendar;
import java.util.Map;

/** Real Skia font metrics. No overflow allowance and no fake text measurements. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34}, qualifiers = "zh-rCN-mdpi", application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetHandbookTest {
    private View render(Activity activity, Context ctx, WidgetRenderer.Form form, int w, int h,
            int padding, float radius, String name, long duration) {
        FrameLayout host = new FrameLayout(ctx);
        float density = ctx.getResources().getDisplayMetrics().density;
        int px = Math.round(padding * density);
        host.setPadding(px, px, px, px);
        activity.setContentView(host);
        RemoteViews views = WidgetRenderFixture.views(ctx, form, w, h, name, radius, duration);
        Parcel parcel = Parcel.obtain();
        try {
            views.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            View root = RemoteViews.CREATOR.createFromParcel(parcel).apply(ctx, host);
            host.addView(root, new FrameLayout.LayoutParams(-1, -1));
            WidgetRenderFixture.measure(host, Math.round(w * density), Math.round(h * density));
            return root;
        } finally { parcel.recycle(); }
    }

    private void assertInside(View root, View child, String tag) {
        if (!child.isShown()) return;
        if (child != root) {
            Rect r = WidgetRenderFixture.bounds(root, child);
            assertTrue(tag + " outside card " + child.getId() + " " + r,
                    r.left >= 0 && r.top >= 0 && r.right <= root.getWidth() && r.bottom <= root.getHeight());
        }
        if (child instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) child;
            for (int i = 0; i < group.getChildCount(); i++) assertInside(root, group.getChildAt(i), tag);
        }
    }

    private void assertText(View root, int id, boolean complete, String tag) {
        TextView text = root.findViewById(id);
        assertTrue(tag + " missing visible core " + id, text.isShown());
        assertTrue(tag + " zero-size core " + id, text.getWidth() > 0 && text.getHeight() > 0);
        assertNotNull(tag + " missing text layout " + id, text.getLayout());
        assertEquals(tag + " wrapped text " + id, 1, text.getLayout().getLineCount());
        assertTrue(tag + " vertically cropped text " + id + " font=" + text.getTextSize()
                + " layout=" + text.getLayout().getHeight() + " height=" + text.getHeight(),
                text.getLayout().getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
        if (complete) {
            assertEquals(tag + " ellipsized text", 0, text.getLayout().getEllipsisCount(0));
            assertTrue(tag + " horizontally cropped text " + text.getText(),
                    text.getLayout().getLineWidth(0) <= text.getWidth() - text.getCompoundPaddingLeft()
                            - text.getCompoundPaddingRight() + .5f);
        }
    }

    @Test public void actualBoundsAndLargeFontsKeepCoreTextWhole() {
        try (ActivityController<Activity> c = Robolectric.buildActivity(Activity.class).setup().visible()) {
            WidgetRenderer.Form[] forms = {WidgetRenderer.Form.MINIMUM, WidgetRenderer.Form.WIDE, WidgetRenderer.Form.FULL};
            int[][] standard = {{128,128}, {360,128}, {304,320}};
            int[][] minimum = {{110,110}, {224,92}, {224,240}};
            for (boolean dark : new boolean[]{false,true}) for (float font : new float[]{1,1.2f,1.3f,1.5f,2}) {
                Context ctx = WidgetRenderFixture.context(c.get(), dark, font);
                for (int i = 0; i < forms.length; i++) for (int[] size : new int[][]{standard[i],minimum[i]}) {
                    for (int padding : new int[]{0,10,20}) {
                        String tag = forms[i] + " " + size[0] + "x" + size[1] + " padding=" + padding + " font=" + font;
                        View root = render(c.get(),ctx,forms[i],size[0],size[1],padding,28,"夜班",8_230_000);
                        assertInside(root, root, tag);
                        for (int id : new int[]{R.id.shift_name,R.id.today_date,R.id.live_clock,R.id.countdown_label})
                            if (id != R.id.countdown_label || root.findViewById(id).isShown()) assertText(root,id,false,tag);
                        assertText(root,R.id.countdown_chronometer,true,tag);
                    }
                }
            }
        }
    }

    @Test public void longNamesLongTimersRestAndLargeCornersStayBounded() {
        try (ActivityController<Activity> c = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Context ctx = WidgetRenderFixture.context(c.get(),true,1.3f);
            for (WidgetRenderer.Form form : WidgetRenderer.Form.values()) {
                for (float radius : new float[]{28,40,60}) for (String name : new String[]{"休息","WWWW特别长的设备巡检与应急值守夜班"}) {
                    View root=render(c.get(),ctx,form,(int)form.width,(int)form.height,10,radius,name,
                            name.equals("休息") ? -1 : 12345L * 3600000);
                    String tag=form+" radius="+radius+" name="+name;
                    assertInside(root,root,tag);
                    assertText(root,R.id.shift_name,false,tag);
                    assertText(root,name.equals("休息") ? R.id.countdown_text : R.id.countdown_chronometer,true,tag);
                    View inner=root.findViewById(R.id.card_content);
                    assertTrue(inner.getPaddingLeft() >= CornerInsets.safeInset(radius));
                    assertEquals(name,((TextView)root.findViewById(R.id.shift_name)).getText().toString());
                    if(form==WidgetRenderer.Form.FULL) {
                        assertNull("Transparent chibi has no portrait background",root.findViewById(R.id.portrait_position).getBackground());
                        assertNull(((ImageView)root.findViewById(R.id.companion_portrait)).getBackground());
                    }
                    if(form==WidgetRenderer.Form.WIDE) assertNull(root.findViewById(R.id.header_ornament));
                }
            }
        }
    }

    @Test @Config(sdk=34,qualifiers="zh-rCN-xhdpi")
    public void highDensityHostKeepsTheSameDpSafetyAndLiveText() {
        try (ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()) {
            Context ctx=WidgetRenderFixture.context(c.get(),false,1.2f);
            for (WidgetRenderer.Form form : WidgetRenderer.Form.values()) {
                View root=render(c.get(),ctx,form,(int)form.width,(int)form.height,10,40,"夜班",360_001_000);
                assertInside(root,root,"xhdpi "+form);
                assertText(root,R.id.countdown_chronometer,true,"xhdpi "+form);
                assertText(root,R.id.shift_name,false,"xhdpi "+form);
            }
        }
    }

    @Test public void customEmojiAndCivilTomorrowAreNotReplacedByStockArtwork() {
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()) {
            Calendar now=Calendar.getInstance();now.set(2026,Calendar.OCTOBER,20,2,30,0);
            android.os.SystemClock.setCurrentTimeMillis(now.getTimeInMillis());
            Calendar anchor=MyWidgetProvider.debugMidnight(now);anchor.add(Calendar.DAY_OF_YEAR,-1);
            Map<String,MyWidgetProvider.ShiftDef> defs=MyWidgetProvider.debugDefaultDefs();
            MyWidgetProvider.ShiftDef night=defs.get("夜班");night.emoji="🛠️";
            String[] cycle={"夜班","休息","白班"};
            MyWidgetProvider.ShiftSlot slot=MyWidgetProvider.debugEffectiveSlot(now,anchor,cycle,defs);
            assertEquals("夜班",slot.name);
            RemoteViews views=WidgetRenderer.build(c.get(),WidgetRenderer.Form.FULL,304,320,now,slot,
                    anchor,cycle,defs,MyWidgetProvider.countdownAt(now,slot,anchor,cycle,defs),28);
            FrameLayout host=new FrameLayout(c.get());c.get().setContentView(host);
            View root=views.apply(c.get(),host);host.addView(root);
            WidgetRenderFixture.measure(host,304,320);
            assertEquals(View.GONE,root.findViewById(R.id.shift_symbol).getVisibility());
            TextView emoji=root.findViewById(R.id.shift_emoji);
            assertEquals("🛠️",emoji.getText().toString());
            assertTrue(emoji.isShown());
            assertText(root,R.id.shift_emoji,true,"custom emoji");
            assertTrue(((TextView)root.findViewById(R.id.tomorrow_value)).getText().toString().contains("白班"));
            assertTrue(((TextView)root.findViewById(R.id.week_day_0)).getText().toString().startsWith("21\n"));
        }
    }

    @Test public void aThemeFlipMustUseANewLayoutIdRatherThanReapplyOldColors() {
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()) {
            Context light=WidgetRenderFixture.context(c.get(),false,1);
            Context dark=WidgetRenderFixture.context(c.get(),true,1);
            for(WidgetRenderer.Form form:WidgetRenderer.Form.values()) {
                RemoteViews day=WidgetRenderFixture.views(light,form,(int)form.width,(int)form.height,"夜班",28,8230000);
                RemoteViews night=WidgetRenderFixture.views(dark,form,(int)form.width,(int)form.height,"夜班",28,8230000);
                assertEquals(form.layout,day.getLayoutId());
                assertNotEquals("Host must reinflate theme colors and art",day.getLayoutId(),night.getLayoutId());
            }
        }
    }

    @Test public void nativePreviewImages() throws Exception {
        try (ActivityController<Activity> c = Robolectric.buildActivity(Activity.class).setup().visible()) {
            for (boolean dark : new boolean[]{false,true}) {
                Context ctx=WidgetRenderFixture.context(c.get(),dark,1);
                for (WidgetRenderer.Form form : new WidgetRenderer.Form[]{WidgetRenderer.Form.MINIMUM,WidgetRenderer.Form.WIDE,WidgetRenderer.Form.FULL}) {
                    Calendar now=Calendar.getInstance();now.set(2026,Calendar.OCTOBER,20,17,11,55);
                    now.set(Calendar.MILLISECOND,0);
                    android.os.SystemClock.setCurrentTimeMillis(now.getTimeInMillis());
                    Calendar anchor=MyWidgetProvider.debugMidnight(now);anchor.add(Calendar.DAY_OF_YEAR,-1);
                    String[] cycle={"白班","夜班","休息","白班","夜班","休息","休息","休息"};
                    Map<String,MyWidgetProvider.ShiftDef> defs=MyWidgetProvider.debugDefaultDefs();
                    MyWidgetProvider.ShiftSlot slot=MyWidgetProvider.debugEffectiveSlot(now,anchor,cycle,defs);
                    MyWidgetProvider.Countdown countdown=MyWidgetProvider.countdownAt(now,slot,anchor,cycle,defs);
                    RemoteViews views=WidgetRenderer.build(ctx,form,form.width,form.height,now,slot,anchor,cycle,defs,countdown,28);
                    WidgetTestClock.alignCountdown(views,now,countdown);
                    FrameLayout host=new FrameLayout(ctx);c.get().setContentView(host);
                    View root=views.apply(ctx,host);host.addView(root);
                    WidgetRenderFixture.measure(host,(int)form.width,(int)form.height);
                    Bitmap image=Bitmap.createBitmap(root.getWidth()*3,root.getHeight()*3,Bitmap.Config.ARGB_8888);
                    Canvas canvas=new Canvas(image);canvas.scale(3,3);root.draw(canvas);
                    File file=new File("build/widget-previews/"+(dark ? "night-" : "day-")+form.name().toLowerCase()+".png");
                    file.getParentFile().mkdirs();
                    try(FileOutputStream stream=new FileOutputStream(file)){image.compress(Bitmap.CompressFormat.PNG,100,stream);}
                    image.recycle();
                }
            }
        }
    }
}
