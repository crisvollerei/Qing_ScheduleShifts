package com.example.myapp;
import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.view.*;
import android.widget.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,qualifiers="zh-rCN-mdpi",application=android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetSafeAreaTest {
    static float cornerOverflow(Rect rect,int w,int h,float r) {
        float worst=0;
        for(float[] p:new float[][]{{rect.left,rect.top},{rect.right,rect.top},{rect.left,rect.bottom},{rect.right,rect.bottom}}) {
            float x=Math.max(r,Math.min(w-r,p[0])), y=Math.max(r,Math.min(h-r,p[1]));
            worst=Math.max(worst,(float)Math.hypot(p[0]-x,p[1]-y)-r);
        }
        return worst;
    }
    static void check(View root,View view,int w,int h,float radius) {
        if(!view.isShown()) return;
        if((view instanceof TextView && ((TextView)view).getText().length()>0) || view.getId()==R.id.refresh_button) {
            Rect bounds=WidgetRenderFixture.bounds(root,view);
            float density=view.getResources().getDisplayMetrics().density;
            Rect dp=new Rect(Math.round(bounds.left/density),Math.round(bounds.top/density),Math.round(bounds.right/density),Math.round(bounds.bottom/density));
            String label=w+"x"+h+" "+view.getResources().getResourceEntryName(view.getId())+" "+dp;
            assertTrue(label,dp.left>=0 && dp.top>=0 && dp.right<=w && dp.bottom<=h && dp.width()>0 && dp.height()>0);
            assertTrue(label+" clipped by rounded host",cornerOverflow(dp,w,h,radius)<=0.6f);
            if(view instanceof TextView) {
                TextView text=(TextView)view;
                assertNotNull(label,text.getLayout());
                // Layout struts can exceed the row at fractional font sizes; test actual glyph ink.
                String value=text.getText().toString();
                for(int line=0;line<text.getLayout().getLineCount();line++) {
                    Rect ink=new Rect();
                    text.getPaint().getTextBounds(value,text.getLayout().getLineStart(line),text.getLayout().getLineEnd(line),ink);
                    int baseline=text.getBaseline()+text.getLayout().getLineBaseline(line)-text.getLayout().getLineBaseline(0);
                    assertTrue(label+" clips visible glyphs: "+ink+" baseline="+baseline,
                            baseline+ink.top>=-1 && baseline+ink.bottom<=text.getHeight()+1);
                }
                boolean ellipsizable=view.getId()==R.id.shift_name || view.getId()==R.id.shift_time || view.getId()==R.id.countdown_detail;
                if(!ellipsizable) {
                    assertNotNull(label,text.getLayout());
                    for(int line=0;line<text.getLayout().getLineCount();line++) assertEquals(label,0,text.getLayout().getEllipsisCount(line));
                    if(text.getMaxLines()==1) assertTrue(label+" clips "+text.getText(),text.getPaint().measureText(text.getText().toString())<=text.getWidth()-text.getPaddingLeft()-text.getPaddingRight()+1);
                }
                Rect hit=WidgetRenderFixture.bounds(root,R.id.refresh_touch_target);
                assertFalse(label+" overlaps refresh",Rect.intersects(bounds,hit));
            }
        }
        if(view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) check(root,((ViewGroup)view).getChildAt(i),w,h,radius);
    }
    @Test public void allVisibleInformationSurvivesRoundedCornersAndActualTouchTargets() {
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()) {
            for(int[] size:WidgetRenderFixture.SIZES) for(float r:new float[]{28,36,48}) {
                View root=WidgetRenderFixture.render(c.get(),c.get(),size[0],size[1],"白班",r,-1);
                check(root,root,size[0],size[1],r);
            }
        }
    }
    @Test public void cornerDetectorActuallyRejectsAnUnsafeRectangle() {
        assertTrue(cornerOverflow(new Rect(0,0,20,20),110,110,28)>0);
        assertEquals(0,cornerOverflow(new Rect(16,16,94,94),110,110,28),0.01f);
    }
    @Test public void wideColumnsKeepTheCountdownIndependentOfTheShiftColumn() {
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()) {
            View root=WidgetRenderFixture.render(c.get(),c.get(),360,128,"白班",28,-1);
            Rect name=WidgetRenderFixture.bounds(root,R.id.shift_name), timer=WidgetRenderFixture.bounds(root,R.id.countdown_chronometer);
            assertTrue(name.right+12<=timer.left);
            assertEquals(1,root.findViewById(R.id.column_divider).getWidth());
        }
    }
}
