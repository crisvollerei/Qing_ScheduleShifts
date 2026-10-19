package com.example.myapp;
import static org.junit.Assert.*;
import org.junit.Test;
public class WidgetLayoutTest {
    @Test public void fourExplicitFormsHaveOneReferenceCanvasEach() {
        assertEquals(4,WidgetRenderer.Form.values().length);
        assertEquals(4,MyWidgetProvider.PROVIDERS.length);
        assertEquals(R.layout.widget_fixed_minimum,WidgetRenderer.Form.MINIMUM.layout);
        assertEquals(R.layout.widget_fixed_compact,WidgetRenderer.Form.COMPACT.layout);
        assertEquals(R.layout.widget_proportional_wide,WidgetRenderer.Form.WIDE.layout);
        assertEquals(R.layout.widget_proportional_full,WidgetRenderer.Form.FULL.layout);
    }
    @Test public void fitUsesTheSmallerAxisWhileCentersFollowBothAxes() {
        for(WidgetRenderer.Form form:WidgetRenderer.Form.values())
            for(float[] ratio:new float[][]{{1,1},{1.5f,1},{1,1.5f},{1.6f,1.6f}}) {
                WidgetRenderer.Geometry g=new WidgetRenderer.Geometry(form,form.width*ratio[0],form.height*ratio[1],28);
                assertSame(form,g.form);
                assertEquals(Math.min(ratio[0],ratio[1]),g.scale,0.001f);
                float[] box=g.box(20,30,80,40);
                assertEquals(2,box[2]/box[3],0.001f);
                assertEquals(60/form.width,(box[0]+box[2]/2)/g.width,0.001f);
                assertEquals(50/form.height,(box[1]+box[3]/2)/g.height,0.001f);
            }
    }
}
