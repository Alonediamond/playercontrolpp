package com.alonediamond.playercontrolpp.feature.automaterial.container;

import com.alonediamond.playercontrolpp.compat.SlotActionCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** 有界的精确转移事务；数量与容量先检查，余量始终退回来源槽，绝不点击界面外。 */
public final class SlotTransfer {
    private SlotTransfer() {}

    public static int toPlayer(Minecraft mc, Slot source, int requested) {
        AbstractContainerMenu menu = mc.player.containerMenu;
        ItemStack original = source.getItem().copy();
        if (requested <= 0 || original.isEmpty() || !source.mayPickup(mc.player) || !menu.getCarried().isEmpty()) return 0;
        int capacity = 0;
        for (Slot slot : menu.slots) if (isPlayerSlot(mc, slot)) capacity += space(slot, original);
        int amount = Math.min(Math.min(requested, original.getCount()), capacity);
        if (amount <= 0) return 0;
        // 普通储物菜单的整堆转移交给原版；部分堆叠按精确数量放入玩家槽。
        if (amount == original.getCount()) {
            SlotActionCompat.quickMove(mc, menu.containerId, source.index);
        } else {
            SlotActionCompat.pickup(mc, menu.containerId, source.index);
            try {
                int remaining = amount;
                for (int i = menu.slots.size() - 1; i >= 0 && remaining > 0; i--) {
                    Slot slot = menu.slots.get(i);
                    if (!isPlayerSlot(mc, slot)) continue;
                    int count = Math.min(remaining, space(slot, original));
                    for (int n = 0; n < count; n++) SlotActionCompat.pickupRight(mc, menu.containerId, slot.index);
                    remaining -= count;
                }
            } finally {
                if (!menu.getCarried().isEmpty()) SlotActionCompat.pickup(mc, menu.containerId, source.index);
            }
        }
        return Math.max(0, original.getCount() - source.getItem().getCount());
    }

    public static boolean isPlayerSlot(Minecraft mc, Slot slot) {
        return slot.container == mc.player.getInventory() && slot.getContainerSlot() >= 0
                && slot.getContainerSlot() < Inventory.INVENTORY_SIZE;
    }

    private static int space(Slot slot, ItemStack stack) {
        if (!slot.mayPlace(stack)) return 0;
        ItemStack existing = slot.getItem();
        if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, stack)) return 0;
        return Math.max(0, Math.min(slot.getMaxStackSize(stack), stack.getMaxStackSize()) - existing.getCount());
    }
}
