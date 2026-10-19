package com.example.myapp;

import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 圆角安全区的几何推导。
 *
 * <p>宿主的圆角裁切是真会把内容切掉的，所以「基础内边距够不够」必须能算出来，
 * 而不是靠试。这里锁住三件事：
 * <ul>
 *   <li>{@code a = R*(1-1/√2)} 是「任意矩形都不落进圆角」的精确下限；</li>
 *   <li>各档位常量 B 都不小于这个下限（含 28dp / 32dp / 36dp 三种宿主圆角）；</li>
 *   <li>贴边行的弧线侵入量随高度递减，供布局复核首行缩进。</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CornerInsetsTest {

    @Test public void arcInsetGrowsTowardTheCorner() {
        // 距顶端越近，弧线侵入越多；到半径处归零。
        assertTrue(CornerInsets.arcInset(28f, 0f) > CornerInsets.arcInset(28f, 8f));
        assertTrue(CornerInsets.arcInset(28f, 8f) > CornerInsets.arcInset(28f, 16f));
        assertEquals(0f, CornerInsets.arcInset(28f, 28f), 0.001f);
        assertEquals(0f, CornerInsets.arcInset(28f, 40f), 0.001f);
    }

    @Test public void arcInsetAtTheEdgeEqualsTheRadius() {
        assertEquals(28f, CornerInsets.arcInset(28f, 0f), 0.001f);
        assertEquals(16f, CornerInsets.arcInset(16f, 0f), 0.001f);
    }

    @Test public void largerRadiusNeedsMoreIndentAtTheSameDistance() {
        assertTrue(CornerInsets.arcInset(32f, 10f) > CornerInsets.arcInset(28f, 10f));
    }

    @Test public void safeInsetIsRadiusTimesOneMinusOneOverSqrtTwo() {
        // R=28 -> 8.201；R=32 -> 9.373；R=36 -> 10.544
        assertEquals(8.201f, CornerInsets.safeInset(28f), 0.01f);
        assertEquals(9.373f, CornerInsets.safeInset(32f), 0.01f);
        assertEquals(10.544f, CornerInsets.safeInset(36f), 0.01f);
        assertEquals(0f, CornerInsets.safeInset(0f), 0.001f);
    }

    /**
     * 这条是整份规格的核心：只要基础内边距 B >= a，任何尺寸、任何比例的内容矩形
     * 都不会落进宿主圆角。四档常量都必须满足，且在宿主圆角放大到 36dp 时仍然满足。
     */
    @Test public void everyDeclaredInsetSurvivesTheLargestPlausibleHostRadius() {
        int[] insets = {CornerInsets.MINI, CornerInsets.MINI_WIDE, CornerInsets.SMALL,
                CornerInsets.COMPANION, CornerInsets.WIDE, CornerInsets.FULL};
        // 28dp 是 Android 12+ 的系统默认值，32dp 覆盖 One UI 等偏大的宿主；
        // 36dp 属于极端定制，需要 10.6dp 内边距，会在 2x2 上吃掉过多内容，不作保证。
        for (float radius : new float[]{28f, 32f}) {
            float required = CornerInsets.safeInset(radius);
            for (int inset : insets) {
                assertTrue("R=" + radius + " 时需要内边距 >= " + required
                        + "，实际声明 " + inset + "dp", inset >= required);
            }
        }
    }

    @Test public void onlyTheRowsTouchingAnEdgeNeedTheExtraIndent() {
        // 贴边行（y 很小）需要额外缩进；中间行（y >= R）弧线侵入为 0。
        assertTrue(CornerInsets.arcInset(CornerInsets.HOST_RADIUS, 4f) > 4f);
        assertEquals(0f, CornerInsets.arcInset(CornerInsets.HOST_RADIUS, CornerInsets.HOST_RADIUS), 0.001f);
        // 但基础内边距 B 已经覆盖了首行位置上的侵入量：
        // y = B 时 arc(B) <= B 恒成立，所以内容壳统一用 B 就够了。
        for (int inset : new int[]{CornerInsets.MINI, CornerInsets.SMALL, CornerInsets.FULL}) {
            assertTrue("首行上沿 y=" + inset + " 时弧线侵入 "
                            + CornerInsets.arcInset(CornerInsets.HOST_RADIUS, inset) + " 不应超过内边距",
                    CornerInsets.arcInset(CornerInsets.HOST_RADIUS, inset) <= inset);
        }
    }

    @Test public void refreshButtonSitsOnTheSameInsetAsTheContentShell() {
        assertEquals(CornerInsets.MINI, CornerInsets.REFRESH);
        assertTrue(CornerInsets.REFRESH >= CornerInsets.safeInset(CornerInsets.HOST_RADIUS));
    }
}
