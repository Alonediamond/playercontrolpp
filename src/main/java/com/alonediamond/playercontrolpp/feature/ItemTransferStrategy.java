package com.alonediamond.playercontrolpp.feature;

/**
 * 备货取物共用的算术常量与小工具。
 *
 * <p>取多少不在这里计划：整盒按「实时缺口 &gt; 阈值就连拿」、散装按「缺口补到 0」
 * 在 {@code ItemTransferExecutor} 里逐次点击重判。这里只留下各处共用的常数与除法。
 */
public final class ItemTransferStrategy {

    /** 潜影盒的储物格数。 */
    public static final int SHULKER_SLOT_COUNT = 27;

    private ItemTransferStrategy() {}

    /** 向上取整的整数除法，{@code b} 必须为正。 */
    public static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
