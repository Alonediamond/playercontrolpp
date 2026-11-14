package com.alonediamond.playercontrolpp.config;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 秒数编辑与整数 tick 之间的纯数值转换，不依赖游戏或 malilib 初始化。 */
public final class TickDuration {
    private static final BigDecimal TICKS_PER_SECOND = BigDecimal.valueOf(20);

    private TickDuration() {}

    public static String formatSeconds(int ticks) {
        return BigDecimal.valueOf(ticks).divide(TICKS_PER_SECOND).stripTrailingZeros().toPlainString();
    }

    public static double seconds(int ticks) {
        return ticks / 20.0;
    }

    public static int parseSeconds(String seconds, int minTicks, int maxTicks) {
        return toTicks(new BigDecimal(seconds.trim()), minTicks, maxTicks);
    }

    public static int toTicks(double seconds, int minTicks, int maxTicks) {
        if (!Double.isFinite(seconds)) throw new NumberFormatException("Non-finite seconds");
        return toTicks(BigDecimal.valueOf(seconds), minTicks, maxTicks);
    }

    public static int clampTicks(int ticks, int minTicks, int maxTicks) {
        if (minTicks > maxTicks) throw new IllegalArgumentException("Invalid tick bounds");
        return Math.max(minTicks, Math.min(maxTicks, ticks));
    }

    private static int toTicks(BigDecimal seconds, int minTicks, int maxTicks) {
        if (minTicks > maxTicks) throw new IllegalArgumentException("Invalid tick bounds");
        BigDecimal rounded = seconds.multiply(TICKS_PER_SECOND).setScale(0, RoundingMode.HALF_UP);
        if (rounded.compareTo(BigDecimal.valueOf(minTicks)) < 0) return minTicks;
        if (rounded.compareTo(BigDecimal.valueOf(maxTicks)) > 0) return maxTicks;
        return rounded.intValueExact();
    }
}
