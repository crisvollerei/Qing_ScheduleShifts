package com.example.myapp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * 小部件布局的合法性检查。
 *
 * RemoteViews 只接受标注了 @RemoteView 的控件；使用普通 View、Space 等
 * 会导致整个小部件加载失败，桌面上只显示“载入出现问题”。
 * 这类错误编译期发现不了，只能靠测试或真机验证。
 */
public class WidgetLayoutResourcesTest {

    /** RemoteViews 允许的容器与控件（本项目实际用到的部分）。 */
    private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
            "LinearLayout", "FrameLayout", "RelativeLayout",
            "TextView", "ImageView", "Button",
            "Chronometer", "TextClock", "ProgressBar"
    ));

    private static Document parse(String name) throws Exception {
        File file = new File("src/main/res/layout/" + name);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        return factory.newDocumentBuilder().parse(file);
    }

    private static void assertOnlyRemoteViews(String layout) throws Exception {
        Document doc = parse(layout);
        Set<String> seen = new HashSet<>();
        collect(doc.getDocumentElement(), seen, layout);
        assertFalse(layout + " 不能为空", seen.isEmpty());
    }

    private static void collect(Element element, Set<String> seen, String layout) {
        String tag = element.getTagName();
        // 根节点同样受限，但它是布局容器本身
        assertTrue(
                layout + " 中使用了 RemoteViews 不支持的控件 <" + tag + ">："
                        + "它缺少 @RemoteView 注解，会导致小部件加载失败",
                ALLOWED.contains(tag));
        seen.add(tag);
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                collect((Element) children.item(i), seen, layout);
            }
        }
    }

    @Test
    public void fullLayoutUsesOnlyRemoteViewsCompatibleWidgets() throws Exception {
        assertOnlyRemoteViews("widget_layout.xml");
    }

    @Test
    public void compactLayoutUsesOnlyRemoteViewsCompatibleWidgets() throws Exception {
        assertOnlyRemoteViews("widget_layout_small.xml");
    }

    @Test
    public void wideLayoutUsesOnlyRemoteViewsCompatibleWidgets() throws Exception {
        assertOnlyRemoteViews("widget_layout_wide.xml");
    }

    @Test public void miniLayoutsUseOnlyRemoteViewsCompatibleWidgets() throws Exception {
        assertOnlyRemoteViews("widget_layout_mini.xml");
        assertOnlyRemoteViews("widget_layout_mini_wide.xml");
        assertOnlyRemoteViews("widget_preview.xml");
    }

    @Test public void providerDefaultAndResizeMinimumAreBothTwoByTwo() throws Exception {
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new File("src/main/res/xml/my_widget_info.xml"));
        Element provider = document.getDocumentElement();
        for (String size : Arrays.asList("minWidth", "minHeight", "minResizeWidth", "minResizeHeight")) {
            org.junit.Assert.assertEquals(size, "110dp", provider.getAttribute("android:" + size));
        }
        org.junit.Assert.assertEquals("2", provider.getAttribute("android:targetCellWidth"));
        org.junit.Assert.assertEquals("2", provider.getAttribute("android:targetCellHeight"));
        org.junit.Assert.assertEquals("@layout/widget_layout_mini", provider.getAttribute("android:initialLayout"));
    }

    @Test
    public void plainViewIsNotAllowed() throws Exception {
        // 明确记录这条约束：普通 View 与 Space 都没有 @RemoteView 注解。
        assertFalse(ALLOWED.contains("View"));
        assertFalse(ALLOWED.contains("Space"));
    }
    @Test public void everySizeKeepsAllRequiredInformationAndNativeSecondTicks() throws Exception {
        for (String layout : Arrays.asList("widget_layout.xml", "widget_layout_small.xml", "widget_layout_wide.xml",
                "widget_layout_mini.xml", "widget_layout_mini_wide.xml")) {
            Document document = parse(layout);
            Set<String> ids = new HashSet<>();
            NodeList elements = document.getElementsByTagName("*");
            boolean liveClock = false;
            for (int i = 0; i < elements.getLength(); i++) {
                Element element = (Element) elements.item(i);
                ids.add(element.getAttribute("android:id"));
                if (element.getTagName().equals("TextClock")) {
                    liveClock = "HH:mm:ss".equals(element.getAttribute("android:format24Hour"))
                            && "HH:mm:ss".equals(element.getAttribute("android:format12Hour"));
                }
            }
            assertTrue(layout + " must have a seconds clock", liveClock);
            for (String id : Arrays.asList("shift_title", "today_date", "shift_name", "shift_emoji",
                    "shift_time", "shift_status", "cycle_info", "progress_bar", "next_shifts",
                    "live_clock", "countdown_label", "countdown_chronometer", "countdown_text",
                    "countdown_detail", "refresh_button")) {
                assertTrue(layout + " missing " + id, ids.contains("@+id/" + id));
            }
        }
    }

}
