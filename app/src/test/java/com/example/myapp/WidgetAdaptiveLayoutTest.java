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
public class WidgetAdaptiveLayoutTest {
    @Test public void largeSystemFontsDoNotChangeTheDesignedGeometry() {
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()) {
            for(float font:new float[]{1,1.3f,2}) for(int[] size:WidgetRenderFixture.SIZES) {
                Context context=WidgetRenderFixture.context(c.get(),false,font);
                View root=WidgetRenderFixture.render(c.get(),context,size[0],size[1],"白班",28,-1);
                WidgetSafeAreaTest.check(root,root,size[0],size[1],28);
            }
        }
    }
    @Test @Config(qualifiers="zh-rCN-xhdpi") public void phoneDensityAndLongNamesKeepGeometryAndReadableNames() {
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()) {
            for(int[] size:WidgetRenderFixture.SIZES) {
                Context context=WidgetRenderFixture.context(c.get(),true,2);
                String name="WWWW中华人民共和国特别长的夜间值班";
                View root=WidgetRenderFixture.render(c.get(),context,size[0],size[1],name,36,-1);
                WidgetSafeAreaTest.check(root,root,size[0],size[1],36);
                TextView title=root.findViewById(R.id.shift_name);
                WidgetRenderer.Geometry g=new WidgetRenderer.Geometry(WidgetRenderFixture.sampleForm(size[0],size[1]),size[0],size[1],36);
                assertEquals(g.name,title.getTextSize()/2,0.01f);
                assertTrue(title.getContentDescription().toString().startsWith(name));
            }
        }
    }
    @Test public void reapplyRestoresPaddingWhenHostRadiusShrinks() {
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()) {
            View root=WidgetRenderFixture.render(c.get(),c.get(),110,110,"白班",48,-1);
            float before=((TextView)root.findViewById(R.id.shift_name)).getTextSize();
            WidgetRenderFixture.views(c.get(),110,110,"白班",28,-1).reapply(c.get(),root);
            WidgetRenderFixture.measure((View)root.getParent(),110,110);
            assertTrue(((TextView)root.findViewById(R.id.shift_name)).getTextSize()>before);
            WidgetSafeAreaTest.check(root,root,110,110,28);
        }
    }
    @Test @Config(sdk=28) public void allCompositionsApplyOnOlderHostsWithoutUnsupportedRemoteMethods() {
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()) {
            for(int[] size:WidgetRenderFixture.SIZES) {
                View root=WidgetRenderFixture.render(c.get(),c.get(),size[0],size[1],"白班",28,-1);
                assertNotNull(root.findViewById(R.id.countdown_chronometer));
            }
        }
    }
}
