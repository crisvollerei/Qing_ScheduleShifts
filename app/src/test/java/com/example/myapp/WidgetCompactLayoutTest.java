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
@Config(sdk=34,qualifiers="zh-rCN-mdpi",application=android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetCompactLayoutTest {
    @Test public void statusAndNameRemainTogetherAcrossIntermediateSizes() {
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()) {
            for(int[] size:new int[][]{{148,148},{184,184},{224,168},{266,210},{180,420}}) {
                View root=WidgetRenderFixture.render(c.get(),c.get(),WidgetRenderer.Form.COMPACT,size[0],size[1],"白班",28,-1);
                WidgetRenderer.Geometry g=new WidgetRenderer.Geometry(WidgetRenderer.Form.COMPACT,size[0],size[1],28);
                TextView name=root.findViewById(R.id.shift_name),status=root.findViewById(R.id.shift_status);
                assertEquals(View.VISIBLE,status.getVisibility());
                Rect a=WidgetRenderFixture.bounds(root,name),b=WidgetRenderFixture.bounds(root,status);
                assertEquals(a.right,b.left);
                assertEquals(6*g.scale,status.getPaddingLeft(),1);
                assertEquals(24*g.scale,name.getTextSize(),0.01f);
                assertEquals(28*g.scale,((TextView)root.findViewById(R.id.countdown_chronometer)).getTextSize(),0.01f);
            }
        }
    }
    @Test public void longNamesEllipsizeWithoutRemovingTheStatus() {
        try(ActivityController<Activity> c=Robolectric.buildActivity(Activity.class).setup().visible()) {
            String name="中华人民共和国特别长的夜间值班名称";
            View root=WidgetRenderFixture.render(c.get(),c.get(),184,184,name,28,-1);
            TextView title=root.findViewById(R.id.shift_name);
            assertTrue(title.getLayout().getEllipsisCount(0)>0);
            assertTrue(title.getContentDescription().toString().startsWith(name));
            assertEquals(View.VISIBLE,root.findViewById(R.id.shift_status).getVisibility());
            WidgetRenderFixture.views(c.get(),184,184,"白班",28,-1).reapply(c.get(),root);
            WidgetRenderFixture.measure((View)root.getParent(),184,184);
            assertEquals(View.VISIBLE,root.findViewById(R.id.shift_status).getVisibility());
        }
    }
}
