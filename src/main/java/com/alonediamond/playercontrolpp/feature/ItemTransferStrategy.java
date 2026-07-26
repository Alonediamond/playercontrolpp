package com.alonediamond.playercontrolpp.feature;

import net.minecraft.world.item.ItemStack;

/**
 * Calculates how many items to take from containers.
 * Designed to be extensible — add new strategies by implementing new static methods
 * and switching via configuration in the future.
 */
public class ItemTransferStrategy {

    /**
     * Default strategy: take stacks with generous rounding-up.
     * <ul>
     *   <li>If need &le; 64: take 1 stack (64 items) — round up to one full stack</li>
     *   <li>If need 65–1728: take ceil(need / 64) stacks</li>
     *   <li>If need &gt; 1728: prefer whole shulker boxes (27 slots &times; 64 = 1728 items each),
     *       fall back to stacks for the remainder</li>
     *   <li>Always take at least 1 stack even if only 1 item is missing</li>
     * </ul>
     *
     * @param neededTotal  total number of this item still needed
     * @param stackMaxSize max stack size for this item (usually 64)
     * @return a {@link TransferPlan} describing how many shulker boxes and stacks to take
     */
    public static TransferPlan calculate(int neededTotal, int stackMaxSize) {
        if (neededTotal <= 0) return TransferPlan.NONE;

        int shulkerCapacity = 27 * stackMaxSize; // one shulker box = 27 stacks
        int fullStacksNeeded;
        int shulkerBoxesToTake = 0;

        if (neededTotal > shulkerCapacity) {
            // Need more than one shulker box worth — take full boxes
            shulkerBoxesToTake = (neededTotal + shulkerCapacity - 1) / shulkerCapacity; // ceil division
            // After taking boxes, calculate remaining stacks
            int takenSoFar = shulkerBoxesToTake * shulkerCapacity;
            int remaining = neededTotal - takenSoFar;
            if (remaining > 0) {
                fullStacksNeeded = (remaining + stackMaxSize - 1) / stackMaxSize;
            } else {
                fullStacksNeeded = 0;
            }
        } else {
            // Less than one shulker box — use stacks
            fullStacksNeeded = (neededTotal + stackMaxSize - 1) / stackMaxSize;
            // Always take at least 1 stack
            if (fullStacksNeeded < 1) fullStacksNeeded = 1;
        }

        return new TransferPlan(shulkerBoxesToTake, fullStacksNeeded, shulkerCapacity, stackMaxSize);
    }

    /**
     * Describes how many items to transfer.
     */
    public static class TransferPlan {
        public static final TransferPlan NONE = new TransferPlan(0, 0, 0, 0);

        /** How many full shulker boxes to take (each 27&times;stackSize items). */
        public final int shulkerBoxes;
        /** Remaining full stacks to take (each stackSize items). */
        public final int stacks;
        /** Capacity of one shulker box in this item. */
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
