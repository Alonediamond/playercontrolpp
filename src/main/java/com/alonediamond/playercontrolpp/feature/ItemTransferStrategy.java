package com.alonediamond.playercontrolpp.feature;

/**
 * Decides how much of an item to take from a container.
 *
 * <p>Deliberately free of Minecraft types so the arithmetic can be reasoned about — and tested —
 * on its own.
 */
public final class ItemTransferStrategy {

    /** Storage slots in a shulker box. */
    public static final int SHULKER_SLOT_COUNT = 27;

    private ItemTransferStrategy() {}

    /**
     * Plan a pickup, rounding up to whole stacks so the player is not left one item short.
     *
     * <ul>
     *   <li>Up to one shulker box worth: {@code ceil(need / stackSize)} stacks, minimum 1.</li>
     *   <li>More than that: as many <em>whole</em> boxes as fit inside the need, then stacks for
     *       whatever is left over.</li>
     * </ul>
     *
     * <p>The box count uses floor, not ceil. With ceil, needing 1729 items asked for
     * {@code ceil(1729/1728) = 2} boxes — 3456 items, twice the requirement — and left
     * {@code remaining} negative, so the leftover-stacks branch was unreachable dead code.
     *
     * @param neededTotal  how many of this item are still missing
     * @param stackMaxSize max stack size for this item (64 for most, 1 for buckets)
     * @return how many whole boxes and loose stacks to take
     */
    public static TransferPlan calculate(int neededTotal, int stackMaxSize) {
        if (neededTotal <= 0) return TransferPlan.NONE;

        int shulkerCapacity = SHULKER_SLOT_COUNT * stackMaxSize;
        int shulkerBoxesToTake = neededTotal / shulkerCapacity;
        int remaining = neededTotal - shulkerBoxesToTake * shulkerCapacity;
        int fullStacksNeeded = ceilDiv(remaining, stackMaxSize);

        // Never plan a no-op: one item missing still means one stack.
        if (shulkerBoxesToTake == 0 && fullStacksNeeded == 0) {
            fullStacksNeeded = 1;
        }

        return new TransferPlan(shulkerBoxesToTake, fullStacksNeeded, shulkerCapacity, stackMaxSize);
    }

    /** Integer division rounding away from zero. {@code b} must be positive. */
    public static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /** How many items to move, expressed as whole boxes plus loose stacks. */
    public static class TransferPlan {
        public static final TransferPlan NONE = new TransferPlan(0, 0, 0, 0);

        /** Whole shulker boxes to take, each holding {@link #shulkerCapacity} items. */
        public final int shulkerBoxes;
        /** Loose stacks to take on top of the boxes, each holding {@link #stackSize} items. */
        public final int stacks;
        /** Capacity of one shulker box for this item. */
        public final int shulkerCapacity;
        /** Max stack size for this item. */
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
