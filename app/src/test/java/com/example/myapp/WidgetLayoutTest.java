package com.example.myapp;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class WidgetLayoutTest {
    @Test public void compactUsesActualDpNotLauncherCellGuesses() {
        assertEquals(R.layout.widget_layout_small, MyWidgetProvider.debugLayoutFor(156, 216));
        assertEquals(R.layout.widget_layout_small, MyWidgetProvider.debugLayoutFor(280, 240));
    }
    @Test public void wideUsesAspectRatioEvenWhenItIsAlsoTall() {
        assertEquals(R.layout.widget_layout_wide, MyWidgetProvider.debugLayoutFor(320, 216));
        assertEquals(R.layout.widget_layout_wide, MyWidgetProvider.debugLayoutFor(500, 320));
        assertEquals(R.layout.widget_layout_wide, MyWidgetProvider.debugLayoutFor(600, 440));
    }
    @Test public void squareAndPortraitUseFullLayout() {
        assertEquals(R.layout.widget_layout, MyWidgetProvider.debugLayoutFor(240, 360));
        assertEquals(R.layout.widget_layout, MyWidgetProvider.debugLayoutFor(320, 320));
        assertEquals(R.layout.widget_layout_small, MyWidgetProvider.debugLayoutFor(180, 420));
    }
}
