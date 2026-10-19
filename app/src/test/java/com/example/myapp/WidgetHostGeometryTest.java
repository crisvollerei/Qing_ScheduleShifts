package com.example.myapp;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Parcel;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
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
@Config(sdk = {28, 34}, qualifiers = "zh-rCN-mdpi", application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WidgetHostGeometryTest {
    private Rect visibleInk(View root, View view) {
        Rect result = new Rect();
        if (!view.isShown()) return result;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            String value = text.getText().toString();
            if (value.isEmpty()) return result;
            Rect origin = WidgetRenderFixture.bounds(root, text);
            for (int line = 0; line < text.getLayout().getLineCount(); line++) {
                Rect ink = new Rect();
                text.getPaint().getTextBounds(value, text.getLayout().getLineStart(line), text.getLayout().getLineEnd(line), ink);
                int baseline = text.getBaseline() + text.getLayout().getLineBaseline(line) - text.getLayout().getLineBaseline(0);
                ink.offset(origin.left + text.getTotalPaddingLeft() + Math.round(text.getLayout().getLineLeft(line)), origin.top + baseline);
                result.union(ink);
            }
        } else if (view.getId() == R.id.companion_portrait) {
            result.set(WidgetRenderFixture.bounds(root, view));
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) result.union(visibleInk(root, group.getChildAt(i)));
        }
        return result;
    }

    private void checkVisibleBounds(View root, View view, int width, int height) {
        if (!view.isShown()) return;
        if (view instanceof TextView || view.getId() == R.id.refresh_button || view.getId() == R.id.companion_portrait) {
            Rect bounds = view instanceof TextView ? visibleInk(root, view) : WidgetRenderFixture.bounds(root, view);
            if (!bounds.isEmpty()) {
                String label = view.getResources().getResourceEntryName(view.getId()) + " " + bounds;
                assertTrue(label, bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height);
                assertTrue(label + " has visible ink clipped by the host corner",
                        WidgetSafeAreaTest.cornerOverflow(bounds, width, height, 28) <= 0.6f);
                if (view instanceof TextView) {
                    Rect row = WidgetRenderFixture.bounds(root, view);
                    assertTrue(label + " clips visible glyphs in its own row", bounds.top >= row.top - 1 && bounds.bottom <= row.bottom + 1);
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) checkVisibleBounds(root, group.getChildAt(i), width, height);
        }
    }

    private View render(Activity activity, Context context, WidgetRenderer.Form form,
            int reportedWidth, int reportedHeight, int actualWidth, int actualHeight) {
        FrameLayout host = new FrameLayout(context);
        activity.setContentView(host);
        RemoteViews payload = WidgetRenderFixture.views(context, form, reportedWidth, reportedHeight, "白班", 28, -1);
        Parcel parcel = Parcel.obtain();
        try {
            payload.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            payload = RemoteViews.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
        View root = payload.apply(context, host);
        float density = context.getResources().getDisplayMetrics().density;
        int w = Math.round(actualWidth * density), h = Math.round(actualHeight * density);
        host.addView(root, new FrameLayout.LayoutParams(w, h));
        WidgetRenderFixture.measure(host, w, h);
        return root;
    }

    @Test public void extraHostSpaceMustNotEnlargeTheRefreshFaceOrDivider() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            for (WidgetRenderer.Form form : WidgetRenderer.Form.values()) {
                int width = (int) form.width, height = (int) form.height;
                View root = render(activity, activity, form, width, height, width + 64, height + 48);
                Rect face = WidgetRenderFixture.bounds(root, R.id.refresh_button);
                assertEquals(form + " refresh face width must be bounded", 26, face.width(), 1);
                assertEquals(form + " refresh face height must be bounded", 26, face.height(), 1);
                Rect target = WidgetRenderFixture.bounds(root, R.id.refresh_touch_target);
                assertEquals(form + " face must be centered within the touch target " + face + " " + target,
                        target.exactCenterX(), face.exactCenterX(), 1);
                assertEquals(target.exactCenterY(), face.exactCenterY(), 1);
                if (form == WidgetRenderer.Form.WIDE) {
                    Rect divider = WidgetRenderFixture.bounds(root, R.id.column_divider);
                    assertEquals("A separator must not become a rectangle", 1, divider.width(), 1);
                    assertEquals(48, divider.height(), 1);
                }
            }
        }
    }

    @Test public void measuredAreaAnchorsNeverStretchOrOverlapIndependentGroups() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            for (WidgetRenderer.Form form : WidgetRenderer.Form.values()) {
                int w = (int) form.width, h = (int) form.height;
                View reference = render(activity, activity, form, w, h, w, h);
                int[] ids = form == WidgetRenderer.Form.FULL
                        ? new int[]{R.id.card_header, R.id.hero_section, R.id.countdown_block, R.id.week_strip}
                        : new int[]{R.id.card_header, R.id.hero_section, R.id.countdown_block, R.id.clock_row};
                Rect[] boxes = new Rect[ids.length];
                for (int i = 0; i < ids.length; i++) boxes[i] = WidgetRenderFixture.bounds(reference, ids[i]);
                float[] centersY = form == WidgetRenderer.Form.MINIMUM ? new float[]{19,47,83,111}
                        : form == WidgetRenderer.Form.COMPACT ? new float[]{25,72,120,159}
                        : form == WidgetRenderer.Form.WIDE ? new float[]{25,84,84,25} : new float[]{41,118,203,271};
                for (int[] delta : new int[][]{{64,48}, {80,0}, {0,80}, {-12,-12}}) {
                    View root = render(activity, activity, form, w, h, w + delta[0], h + delta[1]);
                    for (int i = 0; i < ids.length; i++) {
                        Rect box = WidgetRenderFixture.bounds(root, ids[i]);
                        String message = form + " " + (w + delta[0]) + "x" + (h + delta[1]) + " "
                                + root.getResources().getResourceEntryName(ids[i]);
                        double expectedWidth = form == WidgetRenderer.Form.FULL
                                ? 264d * (w + delta[0]) / w : boxes[i].width();
                        assertEquals(message, expectedWidth, box.width(), 1);
                        assertEquals(message, boxes[i].height(), box.height(), 1);
                        float centerX = form == WidgetRenderer.Form.WIDE ? (i < 2 ? 78 : 214) : w / 2f;
                        assertEquals(message, (double) centerX * (w + delta[0]) / w, box.exactCenterX(), 1);
                        assertEquals(message, (double) centersY[i] * (h + delta[1]) / h, box.exactCenterY(), 1);
                        // Row boxes include font struts; collision means visible information, not blank leading.
                        for (int j = 0; j < i; j++) assertFalse(message + " has visible content overlapping "
                                        + root.getResources().getResourceEntryName(ids[j]),
                                Rect.intersects(visibleInk(root, root.findViewById(ids[i])),
                                        visibleInk(root, root.findViewById(ids[j]))));
                    }
                    View icon = root.findViewById(R.id.refresh_button);
                    assertEquals(26, icon.getWidth(), 1);
                    assertEquals(26, icon.getHeight(), 1);
                    if (form == WidgetRenderer.Form.FULL) {
                        assertEquals(3, root.findViewById(R.id.progress_bar).getHeight(), 1);
                        assertEquals(80, root.findViewById(R.id.companion_portrait).getWidth(), 1);
                    } else if (form == WidgetRenderer.Form.WIDE) {
                        View portrait = root.findViewById(R.id.companion_portrait);
                        assertEquals(48,portrait.getWidth(),1);
                        assertEquals(portrait.getWidth(),portrait.getHeight(),1);
                        assertFalse(Rect.intersects(WidgetRenderFixture.bounds(root,portrait),
                                WidgetRenderFixture.bounds(root,R.id.refresh_touch_target)));
                    }
                    checkVisibleBounds(root, root, w + delta[0], h + delta[1]);
                }
            }
        }
    }

    @Test @Config(sdk = 34, qualifiers = "zh-rCN-xhdpi")
    public void exportNativeBoardWithDeliberatelyInaccurateLauncherSizes() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            for (boolean dark : new boolean[]{false, true}) {
                Context context = WidgetRenderFixture.context(activity, dark, 1.3f);
                Bitmap board = Bitmap.createBitmap(1584,912,Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(board);
                canvas.drawColor(dark ? Color.rgb(11,20,33) : Color.rgb(226,231,238));
                Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
                label.setColor(dark ? Color.LTGRAY : Color.DKGRAY); label.setTextSize(20);
                int[][] samples = {{128,128,192,176,16,48}, {184,184,248,232,432,48},
                        {360,128,424,176,16,536}, {304,320,304,400,960,48}};
                WidgetRenderer.Form[] forms = {WidgetRenderer.Form.MINIMUM, WidgetRenderer.Form.COMPACT,
                        WidgetRenderer.Form.WIDE, WidgetRenderer.Form.FULL};
                for (int i = 0; i < samples.length; i++) {
                    int[] s = samples[i];
                    canvas.drawText(forms[i] + " reported " + s[0] + "x" + s[1] + " / actual " + s[2] + "x" + s[3],
                            s[4], s[5] - 18, label);
                    View root = render(activity, context, forms[i], s[0], s[1], s[2], s[3]);
                    canvas.save(); canvas.translate(s[4],s[5]); root.draw(canvas); canvas.restore();
                }
                File directory = new File("../.qa/widget-previews"); directory.mkdirs();
                try (FileOutputStream out = new FileOutputStream(new File(directory,"host-mismatch-" + (dark ? "dark" : "light") + ".png"))) {
                    board.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                board.recycle();
            }
        }
    }
}
