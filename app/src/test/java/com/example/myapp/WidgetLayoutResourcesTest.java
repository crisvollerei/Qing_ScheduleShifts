package com.example.myapp;
import static org.junit.Assert.*;
import org.junit.Test;
import org.w3c.dom.*;
import java.io.File;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
public class WidgetLayoutResourcesTest {
    private Document parse(String path) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File("src/main/res/"+path));
    }
    @Test public void threeCompositionsAndTimerUseOnlyRemoteViewsWidgets() throws Exception {
        Set<String> allowed=new HashSet<>(Arrays.asList("FrameLayout","LinearLayout","RelativeLayout","TextView","TextClock","Chronometer","ImageView","ProgressBar"));
        for(String file:Arrays.asList("widget_handbook_minimum.xml","widget_handbook_wide.xml","widget_handbook_full.xml","widget_countdown_timer.xml")) {
            NodeList elements=parse("layout/"+file).getElementsByTagName("*");
            for(int i=0;i<elements.getLength();i++) assertTrue(file,allowed.contains(elements.item(i).getNodeName()));
        }
    }
    @Test public void everyCompositionKeepsTheCoreAndOneTimerHost() throws Exception {
        for(String file:Arrays.asList("widget_handbook_minimum.xml","widget_handbook_wide.xml","widget_handbook_full.xml")) {
            Set<String> ids=new HashSet<>(); NodeList elements=parse("layout/"+file).getElementsByTagName("*");
            for(int i=0;i<elements.getLength();i++) {
                Element element=(Element) elements.item(i);
                String id=element.getAttribute("android:id");
                if(!id.isEmpty()) assertTrue(file+" duplicate "+id,ids.add(id));
                if(element.getTagName().equals("TextClock")) {
                    assertEquals("HH:mm:ss",element.getAttribute("android:format12Hour"));
                    assertEquals("HH:mm:ss",element.getAttribute("android:format24Hour"));
                }
            }
            for(String id:Arrays.asList("widget_container","card_content","today_date","shift_name","shift_emoji","shift_status","shift_time","live_clock","countdown_label","countdown_container","countdown_chronometer","countdown_text","countdown_detail"))
                assertTrue(file+" missing "+id,ids.contains("@+id/"+id));
        }
    }
    @Test public void pickerAndInitialViewUseTheSameNewCardWithoutIncreasingTheMinimum() throws Exception {
        Element provider=parse("xml/my_widget_info.xml").getDocumentElement();
        assertEquals("184dp",provider.getAttribute("android:minWidth"));
        assertEquals("184dp",provider.getAttribute("android:minHeight"));
        assertEquals("168dp",provider.getAttribute("android:minResizeWidth"));
        assertEquals("168dp",provider.getAttribute("android:minResizeHeight"));
        assertEquals("2",provider.getAttribute("android:targetCellWidth"));
        assertEquals("2",provider.getAttribute("android:targetCellHeight"));
        assertEquals("@layout/widget_handbook_minimum",provider.getAttribute("android:initialLayout"));
        assertEquals("@layout/widget_handbook_minimum",provider.getAttribute("android:previewLayout"));
        assertEquals("keyguard",provider.getAttribute("android:widgetCategory"));
        Element modern=parse("xml-v28/my_widget_info.xml").getDocumentElement();
        assertEquals("hide_from_picker",modern.getAttribute("android:widgetFeatures"));
        assertEquals("home_screen",modern.getAttribute("android:widgetCategory"));
        assertEquals(provider.getAttribute("android:initialLayout"),modern.getAttribute("android:initialLayout"));
    }
    @Test public void oldLayoutsAreRemovedRatherThanUsedAsSizeFallbacks() {
        for(String name:Arrays.asList("widget_layout","widget_layout_mini","widget_layout_small","widget_layout_companion","widget_layout_compact","widget_layout_wide","widget_layout_mini_wide","widget_preview"))
            assertFalse(name,new File("src/main/res/layout/"+name+".xml").exists());
    }
}
