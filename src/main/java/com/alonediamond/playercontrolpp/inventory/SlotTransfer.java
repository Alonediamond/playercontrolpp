package com.alonediamond.playercontrolpp.inventory;

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
        return toPlayer(mc, source, requested, true);
    }

    /** 服务端的来源堆叠可能晚于客户端同步，固定取货额度不能交给整堆快移。 */
    public static int toPlayerExact(Minecraft mc, Slot source, int requested) {
        return toPlayer(mc, source, requested, false);
    }

    private static int toPlayer(Minecraft mc, Slot source, int requested, boolean allowQuickMove) {
        AbstractContainerMenu menu = mc.player.containerMenu;
        ItemStack original = source.getItem().copy();
        if (requested <= 0 || original.isEmpty() || !source.mayPickup(mc.player) || !menu.getCarried().isEmpty()) return 0;
        int capacity = 0;
        for (Slot slot : menu.slots) if (isPlayerSlot(mc, slot)) capacity += space(slot, original);
        int amount = Math.min(Math.min(requested, original.getCount()), capacity);
        if (amount <= 0) return 0;
        // 普通储物菜单的整堆转移交给原版；部分堆叠按精确数量放入玩家槽。
        if (allowQuickMove && amount == original.getCount()) {
            SlotActionCompat.quickMove(mc, menu.containerId, source.index);
        } else {
            SlotActionCompat.pickup(mc, menu.containerId, source.index);
            try {
                int remaining = amount;
                // 固定取料保留空槽给其他配方材料，与合成前的容量模拟一致。
                if (!allowQuickMove) {
                    remaining = deposit(mc, menu, original, remaining, true);
                }
                deposit(mc, menu, original, remaining, false);
            } finally {
                if (!menu.getCarried().isEmpty()) SlotActionCompat.pickup(mc, menu.containerId, source.index);
            }
        }
        return Math.max(0, original.getCount() - source.getItem().getCount());
    }

    private static int deposit(Minecraft mc, AbstractContainerMenu menu, ItemStack stack, int remaining,
                               boolean skipEmptySlots) {
        for (int index = menu.slots.size() - 1; index >= 0 && remaining > 0; index--) {
            Slot slot = menu.slots.get(index);
            if (!isPlayerSlot(mc, slot) || skipEmptySlots && !slot.hasItem()) continue;
            int count = Math.min(remaining, space(slot, stack));
            for (int moved = 0; moved < count; moved++) {
                SlotActionCompat.pickupRight(mc, menu.containerId, slot.index);
            }
            remaining -= count;
        }
        return remaining;
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
