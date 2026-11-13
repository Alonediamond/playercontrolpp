package com.alonediamond.playercontrolpp.inventory;

import com.alonediamond.playercontrolpp.util.ItemUtil;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;

/** 一次选择操作使用的库存计数；点击后应重新读取，不跨 tick 缓存服务端数据。 */
public final class InventoryCounts {
    private final Map<Item, Integer> loose = new HashMap<>();
    private final Map<Item, Integer> owned = new HashMap<>();

    private InventoryCounts() {}

    /** 借盒的外壳仍在背包，只有盒内余额从自有库存扣除。 */
    public static InventoryCounts capture(IntFunction<ItemStack> stackAt, Set<Integer> borrowedBoxSlots) {
        InventoryCounts counts = new InventoryCounts();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
            ItemStack stack = stackAt.apply(slot);
            if (stack.isEmpty()) continue;
            counts.loose.merge(stack.getItem(), stack.getCount(), Integer::sum);
            counts.owned.merge(stack.getItem(), stack.getCount(), Integer::sum);

            boolean shulker = ItemUtil.isShulkerBox(stack);
            boolean borrowed = borrowedBoxSlots.contains(slot);
            if (!shulker && !borrowed) continue;
            for (ItemStack inner : ItemUtil.contentsOf(stack)) {
                // 与 countEverywhere 一致：命中外层物品本身时不再累计同种内层物品。
                if (shulker && inner.getItem() != stack.getItem()) {
                    counts.owned.merge(inner.getItem(), inner.getCount(), Integer::sum);
                }
                if (borrowed) counts.owned.merge(inner.getItem(), -inner.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    public int loose(Item item) {
        return loose.getOrDefault(item, 0);
    }

    public int owned(Item item) {
        return Math.max(0, owned.getOrDefault(item, 0));
    }
}
