package com.example.myapp;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.android.controller.ActivityController;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28,34}, qualifiers = "zh-rCN-mdpi", application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetPortraitResizeTest {
    @Test public void widePortraitReservesItsOwnRightColumnAtEverySupportedSize() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            for (int radius : new int[]{28,36,48})
            for (int[] size : new int[][]{{360,128}, {500,128}, {224,92}, {360,92}, {360,168}}) {
                View root = WidgetRenderFixture.render(activity, activity, WidgetRenderer.Form.WIDE,
                        size[0], size[1], "白班", radius, -1);
                ImageView portrait = root.findViewById(R.id.companion_portrait);
                assertNotNull("The wide card must keep its portrait at all sizes", portrait);
                WidgetRenderer.Geometry g = new WidgetRenderer.Geometry(WidgetRenderer.Form.WIDE, size[0], size[1], radius);
                Rect image = WidgetRenderFixture.bounds(root, portrait);
                Rect timer = WidgetRenderFixture.bounds(root, R.id.countdown_block);
                Rect refresh = WidgetRenderFixture.bounds(root, R.id.refresh_touch_target);
                assertEquals(48 * g.scale, image.width(), 1);
                assertEquals(image.width(), image.height(), 1);
                float[] expected = g.box(292,68,48,48);
                // Padding, weighted cell edges and child centering round separately to pixels.
                assertEquals(expected[0]+expected[2]/2,image.exactCenterX(),1.5f);
                assertEquals(expected[1]+expected[3]/2,image.exactCenterY(),1.5f);
                assertTrue("Portrait must be to the right of the countdown", image.left >= timer.right);
                assertFalse("Portrait must not cover the refresh target", Rect.intersects(image, refresh));
                assertTrue("Portrait must stay clear of the host corner " + image,
                        WidgetSafeAreaTest.cornerOverflow(image,size[0],size[1],Math.min(radius,size[1]/2f)) <= 0.6f);
                TextView name = root.findViewById(R.id.shift_name);
                assertEquals("The normal two-character name must remain complete", 0, name.getLayout().getEllipsisCount(0));
                assertEquals(View.VISIBLE, root.findViewById(R.id.shift_status).getVisibility());
                WidgetSafeAreaTest.check(root, root, size[0], size[1], radius);
            }
        }
    }

    @Test public void fullBandsUseHorizontalSpaceWhileKeepingArtworkAndFontsUniform() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            for (int width : new int[]{224,304,400,500}) {
                View root = WidgetRenderFixture.render(activity, activity, WidgetRenderer.Form.FULL, width,320,"白班",28,-1);
                WidgetRenderer.Geometry g = new WidgetRenderer.Geometry(WidgetRenderer.Form.FULL,width,320,28);
                Rect hero = WidgetRenderFixture.bounds(root,R.id.hero_section);
                assertEquals("Full card must expand its rows, not keep a centered fixed-width island", width * 264d / 304, hero.width(), 1);
                for (int id : new int[]{R.id.card_header,R.id.countdown_block,R.id.week_strip}) {
                    Rect band = WidgetRenderFixture.bounds(root,id);
                    assertEquals(hero.left,band.left,1);
                    assertEquals(hero.right,band.right,1);
                }
                View image = root.findViewById(R.id.companion_portrait);
                assertEquals(80 * g.scale,image.getWidth(),1);
                assertEquals(image.getWidth(),image.getHeight(),1);
                assertEquals(28 * g.scale,((TextView)root.findViewById(R.id.shift_name)).getTextSize(),0.01f);
                assertEquals(hero.left,WidgetRenderFixture.bounds(root,R.id.week_day_0).left);
                assertEquals(hero.right,WidgetRenderFixture.bounds(root,R.id.week_day_6).right);
                WidgetSafeAreaTest.check(root,root,width,320,28);
            }
            // The launcher may remeasure a cached payload before sending new size options.
            View root = WidgetRenderFixture.render(activity,activity,WidgetRenderer.Form.FULL,304,320,"白班",28,-1);
            FrameLayout host = (FrameLayout) root.getParent();
            for (int width : new int[]{400,500,304}) {
                root.setLayoutParams(new FrameLayout.LayoutParams(width,320));
                WidgetRenderFixture.measure(host,width,320);
                assertEquals(width * 264d / 304,root.findViewById(R.id.hero_section).getWidth(),1);
                assertEquals(80,root.findViewById(R.id.companion_portrait).getWidth(),1);
            }
        }
    }

    private void draw(Activity activity, Context context, Canvas canvas, WidgetRenderer.Form form,
            int width, int height, int x, int y) {
        View root = WidgetRenderFixture.render(activity,context,form,width,height,"白班",28,-1);
        canvas.save(); canvas.translate(x,y); root.draw(canvas); canvas.restore();
    }

    @Test @Config(sdk = 34, qualifiers = "zh-rCN-xhdpi")
    public void exportWidePortraitAndFullWidthComparison() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            for (boolean dark : new boolean[]{false,true}) {
                Context context = WidgetRenderFixture.context(controller.get(),dark,1.3f);
                Bitmap board = Bitmap.createBitmap(1728,1040,Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(board);
                canvas.drawColor(dark ? Color.rgb(11,20,33) : Color.rgb(226,231,238));
                Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
                label.setColor(dark ? Color.LTGRAY : Color.DKGRAY); label.setTextSize(20);
                canvas.drawText("FULL 304 x 320",16,30,label);
                canvas.drawText("FULL 500 x 320 / SAME HEIGHT",656,30,label);
                canvas.drawText("WIDE 360 x 128",16,726,label);
                canvas.drawText("WIDE 440 x 128",768,726,label);
                draw(controller.get(),context,canvas,WidgetRenderer.Form.FULL,304,320,16,48);
                draw(controller.get(),context,canvas,WidgetRenderer.Form.FULL,500,320,656,48);
                draw(controller.get(),context,canvas,WidgetRenderer.Form.WIDE,360,128,16,744);
                draw(controller.get(),context,canvas,WidgetRenderer.Form.WIDE,440,128,768,744);
                File directory = new File("../.qa/widget-previews"); directory.mkdirs();
                try (FileOutputStream out = new FileOutputStream(new File(directory,"portrait-resize-" + (dark ? "dark" : "light") + ".png"))) {
                    board.compress(Bitmap.CompressFormat.PNG,100,out);
                }
                board.recycle();
            }
        }
    }
}
