package com.alonediamond.playercontrolpp.inventory.shulker;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/** 根据当前待存物品与盒内堆栈，判断潜影盒是否还能接收至少一件。 */
public final class ShulkerStorageCapacity {
    private static final int SLOT_COUNT = 27;

    private ShulkerStorageCapacity() {}

    public static boolean canStoreAny(List<ItemStack> contents, Iterable<ItemStack> candidates) {
        int occupied = 0;
        for (ItemStack inner : contents) if (!inner.isEmpty()) occupied++;

        for (ItemStack candidate : candidates) {
            if (candidate.isEmpty() || !candidate.getItem().canFitInsideContainerItems()) continue;
            if (occupied < SLOT_COUNT) return true;
            for (ItemStack inner : contents) {
                if (!inner.isEmpty() && ItemStack.isSameItemSameComponents(inner, candidate)
                        && inner.getCount() < inner.getMaxStackSize()) return true;
            }
        }
        return false;
    }
}
