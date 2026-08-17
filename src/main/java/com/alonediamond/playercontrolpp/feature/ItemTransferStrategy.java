package com.alonediamond.playercontrolpp.feature;

/**
 * 决定从容器里取多少个某物品。
 *
 * <p>刻意不引用任何 Minecraft 类型，算术部分可以独立推敲和单测。
 */
public final class ItemTransferStrategy {

    /** 潜影盒的储物格数。 */
    public static final int SHULKER_SLOT_COUNT = 27;

    private ItemTransferStrategy() {}

    /**
     * 规划一次取物，向上取整到整组，免得差一个还得再跑一趟。
     *
     * <ul>
     *   <li>不超过一整盒：取 {@code ceil(需求 / 每组数量)} 组，至少 1 组；</li>
     *   <li>超过一整盒：先取塞得进需求的<em>整</em>盒数，余量再按组取。</li>
     * </ul>
     *
     * <p>盒数用向下取整而不是向上：用向上取整时，需求 1729 会算出
     * {@code ceil(1729/1728) = 2} 盒 = 3456 个，是需求的两倍，还会让 {@code remaining} 变负数，
     * 使"余量按组取"那条分支成为不可达的死代码。
     *
     * @param neededTotal  这个物品还缺多少
     * @param stackMaxSize 该物品的最大堆叠数（多数是 64，桶是 1）
     * @return 要取几个整盒、几组散装
     */
    public static TransferPlan calculate(int neededTotal, int stackMaxSize) {
        if (neededTotal <= 0) return TransferPlan.NONE;

        int shulkerCapacity = SHULKER_SLOT_COUNT * stackMaxSize;
        int shulkerBoxesToTake = neededTotal / shulkerCapacity;
        int remaining = neededTotal - shulkerBoxesToTake * shulkerCapacity;
        int fullStacksNeeded = ceilDiv(remaining, stackMaxSize);

        // 不允许规划出"什么都不取"：还差一个也意味着要取一组。
        if (shulkerBoxesToTake == 0 && fullStacksNeeded == 0) {
            fullStacksNeeded = 1;
        }

        return new TransferPlan(shulkerBoxesToTake, fullStacksNeeded, shulkerCapacity, stackMaxSize);
    }

    /** 向上取整的整数除法，{@code b} 必须为正。 */
    public static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /** 取物计划：整盒数 + 散装组数。 */
    public static class TransferPlan {
        public static final TransferPlan NONE = new TransferPlan(0, 0, 0, 0);

        /** 要取的整盒数，每盒装 {@link #shulkerCapacity} 个。 */
        public final int shulkerBoxes;
        /** 整盒之外还要取的散装组数，每组 {@link #stackSize} 个。 */
        public final int stacks;
        /** 该物品下一个潜影盒的容量。 */
        public final int shulkerCapacity;
        /** 该物品的最大堆叠数。 */
        public final int stackSize;

        TransferPlan(int shulkerBoxes, int stacks, int shulkerCapacity, int stackSize) {
            this.shulkerBoxes = shulkerBoxes;
            this.stacks = stacks;
            this.shulkerCapacity = shulkerCapacity;
            this.stackSize = stackSize;
        }

        public int totalItems() {
            return shulkerBoxes * shulkerCapacity + stacks * stackSize;
        }

        @Override
        public String toString() {
            return String.format("shulkers=%d stacks=%d (~%d items)",
                    shulkerBoxes, stacks, totalItems());
        }
    }
}
