package com.example.myapp;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class WidgetLayoutTest {
    @Test public void minimumAndNarrowSizesUseMiniLayout() {
        assertEquals(R.layout.widget_layout_mini, MyWidgetProvider.debugLayoutFor(110, 110));
        assertEquals(R.layout.widget_layout_mini, MyWidgetProvider.debugLayoutFor(110, 400));
        assertEquals(R.layout.widget_layout_mini, MyWidgetProvider.debugLayoutFor(155, 320));
        assertEquals(R.layout.widget_layout_mini, MyWidgetProvider.debugLayoutFor(180, 110));
        assertEquals(R.layout.widget_layout_mini, MyWidgetProvider.debugLayoutFor(240, 215));
    }
    @Test public void shortSizesReserveSideSpaceBeforeSplittingInformationColumns() {
        assertEquals(R.layout.widget_layout_mini, MyWidgetProvider.debugLayoutFor(224, 110));
        assertEquals(R.layout.widget_layout_mini_wide, MyWidgetProvider.debugLayoutFor(360, 110));
        assertEquals(R.layout.widget_layout_mini_wide, MyWidgetProvider.debugLayoutFor(500, 215));
    }
    @Test public void compactUsesActualDpNotLauncherCellGuesses() {
        assertEquals(R.layout.widget_layout_small, MyWidgetProvider.debugLayoutFor(156, 216));
        assertEquals(R.layout.widget_layout_small, MyWidgetProvider.debugLayoutFor(215, 320));
        assertEquals(R.layout.widget_layout_companion, MyWidgetProvider.debugLayoutFor(216, 216));
        assertEquals(R.layout.widget_layout_companion, MyWidgetProvider.debugLayoutFor(280, 240));
        assertEquals(R.layout.widget_layout_companion, MyWidgetProvider.debugLayoutFor(320, 216));
    }
    @Test public void wideUsesAspectRatioEvenWhenItIsAlsoTall() {
        assertEquals(R.layout.widget_layout_wide, MyWidgetProvider.debugLayoutFor(360, 216));
        assertEquals(R.layout.widget_layout_wide, MyWidgetProvider.debugLayoutFor(500, 320));
        assertEquals(R.layout.widget_layout_wide, MyWidgetProvider.debugLayoutFor(600, 440));
    }
    @Test public void squareAndPortraitUseFullLayout() {
        assertEquals(R.layout.widget_layout, MyWidgetProvider.debugLayoutFor(240, 360));
        assertEquals(R.layout.widget_layout, MyWidgetProvider.debugLayoutFor(320, 320));
        assertEquals(R.layout.widget_layout_small, MyWidgetProvider.debugLayoutFor(180, 420));
    }
}
