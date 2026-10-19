package com.example.myapp;
import static org.junit.Assert.*;
import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.android.controller.ActivityController;
@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,34},qualifiers="zh-rCN-mdpi",application=android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetGroupTransformTest {
    @Test public void nativeGroupsFollowCenterAnchorsWithOneUniformScale() {
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()) {
            for(WidgetRenderer.Form form:WidgetRenderer.Form.values())
                for(float[] ratio:new float[][]{{1,1},{1.4f,1},{1,1.4f},{1.6f,1.6f},{0.9f,0.9f}}) {
                    int w=Math.round(form.width*ratio[0]),h=Math.round(form.height*ratio[1]);
                    View root=WidgetRenderFixture.render(c.get(),c.get(),form,w,h,"白班",28,-1);
                    WidgetRenderer.Geometry g=new WidgetRenderer.Geometry(form,w,h,28);
                    Rect hero=WidgetRenderFixture.bounds(root,R.id.hero_section);
                    float[] expected=form==WidgetRenderer.Form.MINIMUM?g.box(12,34,104,26)
                            :form==WidgetRenderer.Form.COMPACT?g.box(16,54,152,36)
                            :form==WidgetRenderer.Form.WIDE?g.box(16,56,124,56):g.box(20,78,264,80);
                    if(form==WidgetRenderer.Form.FULL) {
                        expected[0]=g.edge+(w-2*g.edge)*20/304;
                        expected[2]=(w-2*g.edge)*264/304;
                    }
                    assertEquals(expected[0]+expected[2]/2,hero.exactCenterX(),1);
                    assertEquals(expected[1]+expected[3]/2,hero.exactCenterY(),1);
                    assertEquals(expected[2],hero.width(),1);
                    assertEquals(expected[3],hero.height(),1);
                    assertEquals(g.name,((TextView)root.findViewById(R.id.shift_name)).getTextSize(),0.01f);
                    assertEquals(g.support,((TextView)root.findViewById(R.id.live_clock)).getTextSize(),0.01f);
                    assertEquals(form==WidgetRenderer.Form.FULL||form==WidgetRenderer.Form.WIDE,
                            root.findViewById(R.id.companion_portrait)!=null);
                    assertTrue(root.findViewById(R.id.refresh_touch_target).getWidth()>=48);
                    assertTrue(root.findViewById(R.id.refresh_touch_target).getHeight()>=48);
                    WidgetSafeAreaTest.check(root,root,w,h,28);
                }
        }
    }
}
