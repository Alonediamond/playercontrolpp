package com.alonediamond.playercontrolpp.feature.automaterial.container;

import com.alonediamond.playercontrolpp.feature.automaterial.GatherContext;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialItemEntry;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** 只决定下一次取什么，不发送点击、不推进状态；每次均按实际持有量重新判断。 */
public final class ContainerMaterialCollector {
    public record Selection(Slot slot, int amount, boolean unpack) {}

    public static Selection select(GatherContext ctx, List<Slot> slots, Predicate<Slot> canAttempt) {
        return select(ctx.wantedEntries(), ctx::countHeld, slots,
                slot -> slot.container != ctx.client.player.getInventory() && slot.mayPickup(ctx.client.player)
                        && canAttempt.test(slot),
                Configs.BaritoneSettings.SHULKER_BOX_PRIORITY_THRESHOLD.getIntegerValue(),
                Configs.BaritoneSettings.RECOGNIZE_MIXED_SHULKER_BOX.getBooleanValue());
    }

    public static Selection select(List<MaterialItemEntry> requirements, ToIntFunction<Item> held, List<Slot> slots,
                            Predicate<Slot> usable, int threshold, boolean mixedBoxes) {
        // 满盒优先对每种缺失材料生效，不取决于为了哪一种材料来到这个箱子。
        for (Slot slot : slots) {
            if (!(slot.hasItem() && usable.test(slot))) continue;
            for (MaterialItemEntry entry : requirements) {
                if (remaining(held, entry) > threshold && isFullBox(slot.getItem(), entry.item)) {
                    return new Selection(slot, 1, false);
                }
            }
        }
        for (Slot slot : slots) {
            if (!(slot.hasItem() && usable.test(slot)) || ItemUtil.isShulkerBox(slot.getItem())) continue;
            for (MaterialItemEntry entry : requirements) {
                int gap = remaining(held, entry);
                if (gap > 0 && slot.getItem().is(entry.item)) return new Selection(slot, gap, false);
            }
        }
        if (mixedBoxes) {
            for (Slot slot : slots) {
                if (!(slot.hasItem() && usable.test(slot)) || !ItemUtil.isShulkerBox(slot.getItem())) continue;
                for (MaterialItemEntry entry : requirements) {
                    if (remaining(held, entry) > 0 && ItemUtil.containsInside(slot.getItem(), entry.item)) {
                        return new Selection(slot, 1, true);
                    }
                }
            }
        }
        return null;
    }

    private static int remaining(ToIntFunction<Item> held, MaterialItemEntry entry) {
        return Math.max(0, entry.neededCount - held.applyAsInt(entry.item));
    }

    public static boolean isFullBox(ItemStack box, Item item) {
        if (!ItemUtil.isShulkerBox(box)) return false;
        List<ItemStack> contents = ItemUtil.contentsOf(box);
        if (contents.size() != 27) return false;
        for (ItemStack stack : contents) {
            if (!stack.is(item) || stack.getCount() != stack.getMaxStackSize()) return false;
        }
        return true;
    }
}
