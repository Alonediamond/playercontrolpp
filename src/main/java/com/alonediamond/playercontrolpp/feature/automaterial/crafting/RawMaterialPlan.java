package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import com.alonediamond.playercontrolpp.integration.LitematListIntegration.RawNode;
import net.minecraft.world.item.Item;
import java.util.ArrayDeque;
import java.util.Deque;

/** 原材料分批计划与父子帧推进；不驱动采集、挖矿、开盒或合成。 */
final class RawMaterialPlan {
    private final Deque<Frame> frames = new ArrayDeque<>();

    /**
     * 计划里的一帧：把 {@code item} 从它的子材料做出 {@code need} 个。
     *
     * <p>{@code need} 恒不超过物品的堆叠上限：一帧就是"一批"。顶层物品的缺口按堆叠上限
     * 切成若干批（整组在前、余数在后）；往下分解出来的子材料同样切段。同一批分解出来的
     * 若干分帧共用一个 {@code parent}，只有最后一段的 {@code advanceParent} 为 true——
     * 它最后弹出，由它把父帧的 {@code nextChild} 往前推一格，父帧才不会把"一个子材料"
     * 数成好几次。
     */
    static final class Frame {
        final Item item;
        final RawNode node;
        /** 这一批要做多少个，恒 ≤ 物品堆叠上限。 */
        final int need;
        /** 绝对终点：持有量到它就收工（顶层＝清单需求总量；子材料＝清单预留量＋这一批要消耗的量）。 */
        final int targetTotal;
        /** null = 顶层物品的批次；非 null = 从父帧某个子材料分解出来的。 */
        final Frame parent;
        /** 弹出时是否推进父帧：同一个子材料切成多段时，只有最后一段负责推进。 */
        final boolean advanceParent;
        int nextChild;
        /** 为这一帧开过几次盒子取料。每个子材料最多各一次，且总数有上限，避免来回开关盒子。 */
        int unpackAttempts;

        Frame(Item item, RawNode node, int need, int targetTotal, Frame parent, boolean advanceParent) {
            this.item = item;
            this.node = node;
            this.need = need;
            this.targetTotal = targetTotal;
            this.parent = parent;
            this.advanceParent = advanceParent;
        }
    }

    Frame current() { return frames.peek(); }
    boolean isEmpty() { return frames.isEmpty(); }
    void clear() { frames.clear(); }

    /** 只有一个子材料的最后一批完成后才推进父帧。返回 true 表示整份计划结束。 */
    boolean finishFrame() {
        Frame done = frames.poll();
        if (frames.isEmpty()) return true;
        if (done != null && done.advanceParent && done.parent != null) done.parent.nextChild++;
        return false;
    }

    int pushBatches(Item item, RawNode node, int need, int targetTotal, Frame parent) {
        int maxStack = Math.max(1, item.getDefaultMaxStackSize());
        int count = (Math.max(1, need) + maxStack - 1) / maxStack;
        int[] chunks = new int[count];
        int remaining = Math.max(1, need);
        for (int i = 0; i < count; i++) {
            chunks[i] = Math.min(maxStack, remaining);
            remaining -= chunks[i];
        }
        for (int i = count - 1; i >= 0; i--) {
            frames.push(new Frame(item, node, chunks[i], targetTotal, parent,
                    parent != null && i == count - 1));
        }
        return count;
    }

    static int scaleCount(int childCount, int need, int parentCount) {
        if (parentCount <= 0) return childCount;
        long scaled = ((long) childCount * need + parentCount - 1) / parentCount;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, scaled));
    }
}
