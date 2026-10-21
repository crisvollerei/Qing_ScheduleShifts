package com.example.myapp;

import android.content.Context;

import androidx.core.content.ContextCompat;

/** Appearance follows the web style, never the shift name or its working hours. */
final class WidgetShiftAppearance {
    private WidgetShiftAppearance() { }

    static String presetEmoji(String style) {
        if ("night".equals(style)) return "\uD83C\uDF19";
        if ("rest".equals(style)) return "\uD83C\uDF3F";
        if ("noon".equals(style)) return "\uD83C\uDF24\uFE0F";
        if ("morning".equals(style)) return "\uD83C\uDF05";
        if ("evening".equals(style)) return "\uD83C\uDF06";
        return "\u2600\uFE0F";
    }

    static int symbol(String emoji) {
        if ("\u2600\uFE0F".equals(emoji)) return R.drawable.widget_symbol_day;
        if ("\uD83C\uDF19".equals(emoji)) return R.drawable.widget_symbol_night;
        if ("\uD83C\uDF3F".equals(emoji)) return R.drawable.widget_symbol_rest;
        if ("\uD83C\uDF24\uFE0F".equals(emoji)) return R.drawable.widget_symbol_noon;
        if ("\uD83C\uDF05".equals(emoji)) return R.drawable.widget_symbol_morning;
        if ("\uD83C\uDF06".equals(emoji)) return R.drawable.widget_symbol_evening;
        if ("\uD83D\uDCCB".equals(emoji)) return R.drawable.widget_symbol_custom;
        return 0;
    }

    static int color(Context context, String style) {
        int resource = R.color.widget_shift_day;
        if ("night".equals(style)) resource = R.color.widget_shift_night;
        else if ("rest".equals(style)) resource = R.color.widget_shift_rest;
        else if ("noon".equals(style)) resource = R.color.widget_shift_noon;
        else if ("morning".equals(style)) resource = R.color.widget_shift_morning;
        else if ("evening".equals(style)) resource = R.color.widget_shift_evening;
        return ContextCompat.getColor(context, resource);
    }
}
