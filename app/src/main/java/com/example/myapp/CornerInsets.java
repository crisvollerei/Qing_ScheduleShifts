package com.example.myapp;

/** Geometry shared by the fixed-height RemoteViews layouts and their regression tests. */
final class CornerInsets {
    private CornerInsets() { }

    static final float HOST_RADIUS = 28f;

    /** Uniform inset of the largest rectangle contained by all four rounded corners. */
    static float safeInset(float radius) {
        if (radius <= 0f) return 0f;
        return radius * (1f - (float) (1d / Math.sqrt(2d)));
    }

    /** Horizontal depth of the circular arc at a given distance from the top/bottom. */
    static float arcInset(float radius, float distanceFromEdge) {
        if (radius <= 0f || distanceFromEdge >= radius) return 0f;
        if (distanceFromEdge <= 0f) return radius;
        float dy = radius - distanceFromEdge;
        return radius - (float) Math.sqrt(Math.max(0d, radius * radius - dy * dy));
    }

    static float extraIndent(float radius, float distanceFromEdge) {
        return arcInset(radius, distanceFromEdge);
    }

    static final int MINIMUM = 10;
    static final int COMPACT = 16;
    static final int FULL = 20;
}
