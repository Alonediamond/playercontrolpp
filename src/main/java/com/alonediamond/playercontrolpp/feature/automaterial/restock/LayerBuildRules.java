package com.alonediamond.playercontrolpp.feature.automaterial.restock;

import com.alonediamond.playercontrolpp.integration.LitematicaIntegration.SelectionBounds;

import java.util.List;
import java.util.OptionalInt;

/** 分层推进与完成确认规则；不依赖游戏窗口、建造进程或容器时序。 */
public final class LayerBuildRules {
    private LayerBuildRules() {}

    /** 跳到指定方向下一实际存在的投影层，子区域之间的垂直空隙直接跳过。 */
    public static OptionalInt nextLayer(List<SelectionBounds> bounds, int currentY, int step) {
        if (step == 0) return OptionalInt.empty();
        boolean upward = step > 0;
        long best = upward ? Long.MAX_VALUE : Long.MIN_VALUE;
        for (SelectionBounds box : bounds) {
            long candidate = upward ? Math.max((long) currentY + 1, box.min().getY())
                    : Math.min((long) currentY - 1, box.max().getY());
            if (candidate < box.min().getY() || candidate > box.max().getY()) continue;
            best = upward ? Math.min(best, candidate) : Math.max(best, candidate);
        }
        return best == Long.MAX_VALUE || best == Long.MIN_VALUE ? OptionalInt.empty()
                : OptionalInt.of((int) best);
    }

    /** 只有完整扫描可提交确认；中间扫描状态不能作为完成证据。 */
    public static final class StableCompletion {
        private long firstTick = -1;
        private long lastTick = -1;
        private boolean repeated;

        public boolean confirm(long tick, boolean unknown, boolean missing, int stableTicks) {
            if (unknown || missing) {
                reset();
                return false;
            }
            if (firstTick < 0 || tick < lastTick) {
                firstTick = tick;
                lastTick = tick;
                repeated = false;
                return false;
            }
            if (tick > lastTick) repeated = true;
            lastTick = tick;
            return repeated && tick - firstTick >= Math.max(0, stableTicks);
        }

        public void reset() {
            firstTick = -1;
            lastTick = -1;
            repeated = false;
        }
    }
}
