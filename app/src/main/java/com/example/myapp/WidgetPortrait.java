package com.example.myapp;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/** Small, theme-keyed portrait only. Baking its bounds avoids launcher outline differences. */
final class WidgetPortrait {
    private static final Bitmap[] CACHE = new Bitmap[2];
    private WidgetPortrait() { }

    static synchronized Bitmap window(Context context) {
        boolean dark = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int key = dark ? 1 : 0;
        if (CACHE[key] != null) return CACHE[key];
        int width = 224, height = 288;
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        RectF bounds = new RectF(1, 1, width - 1, height - 1);
        Path shape = new Path();
        shape.addRoundRect(bounds, new float[]{106,106,106,106,24,24,24,24}, Path.Direction.CW);
        paint.setShader(new LinearGradient(0, 0, width, height,
                Color.parseColor(dark ? "#304C63" : "#F8F4FC"),
                Color.parseColor(dark ? "#1A2C45" : "#E5DDEF"), Shader.TileMode.CLAMP));
        canvas.drawPath(shape, paint);
        paint.setShader(null);
        canvas.save();
        canvas.clipPath(shape);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setColor(Color.parseColor(dark ? "#526780" : "#D4C8E3"));
        for (int y = 22; y < height; y += 30) {
            for (int x = -30; x < width; x += 44) canvas.drawArc(x, y, x + 58, y + 40, 0, 180, false, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        Bitmap source = BitmapFactory.decodeResource(context.getResources(),
                dark ? R.drawable.widget_character_night : R.drawable.widget_character_day, options);
        if (source != null) {
            float drawnWidth = width * 1.32f;
            float drawnHeight = drawnWidth * source.getHeight() / source.getWidth();
            canvas.drawBitmap(source, null, new RectF((width - drawnWidth) / 2, 0,
                    (width + drawnWidth) / 2, drawnHeight), paint);
            source.recycle();
        }
        canvas.restore();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setColor(Color.parseColor(dark ? "#657990" : "#CEC1DF"));
        canvas.drawPath(shape, paint);
        CACHE[key] = result;
        return result;
    }
}
