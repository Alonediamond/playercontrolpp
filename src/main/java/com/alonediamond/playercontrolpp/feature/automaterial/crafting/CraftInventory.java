package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import com.alonediamond.playercontrolpp.feature.automaterial.crafting.CraftBatchActions.Craft;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.CraftBatchActions.Fetch;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.CraftBatchActions.Step;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/** 只模拟背包 36 格。盒子和带组件的堆栈占用空间，不能与普通合成材料合并。 */
final class CraftInventory {
    private static final int MAX_OPERATIONS_PER_PLACEMENT = 16;
    private static final int OCCUPIED = -1;
    private final Item[] items;
    private final int[] counts;

    CraftInventory(IntFunction<ItemStack> slotAt) {
        this.items = new Item[Inventory.INVENTORY_SIZE];
        this.counts = new int[Inventory.INVENTORY_SIZE];
        for (int slot = 0; slot < counts.length; slot++) {
            ItemStack stack = slotAt.apply(slot);
            if (ItemUtil.isPlainLoose(stack)) {
                items[slot] = stack.getItem();
                counts[slot] = stack.getCount();
            } else if (!stack.isEmpty()) {
                counts[slot] = OCCUPIED;
            }
        }
    }

    private CraftInventory(CraftInventory original) {
        this.items = original.items.clone();
        this.counts = original.counts.clone();
    }

    boolean fits(List<Step> steps) {
        CraftInventory projected = new CraftInventory(this);
        for (Step step : steps) {
            if (step instanceof Fetch fetch) {
                if (!projected.canReceive(fetch.items())) return false;
                fetch.items().forEach(projected::insert);
            } else if (step instanceof Craft craft) {
                int remaining = craft.operations();
                while (remaining > 0) {
                    int count = projected.largestPlacement(craft.withOperations(remaining));
                    if (count == 0) return false;
                    projected.place(craft, count);
                    projected.insert(craft.item(), craft.recipe().yield() * count);
                    remaining -= count;
                }
            }
        }
        return true;
    }

    boolean canReceive(Map<Item, Integer> amounts) {
        int empty = emptySlots();
        for (Map.Entry<Item, Integer> entry : amounts.entrySet()) {
            empty -= newSlotsNeeded(entry.getKey(), entry.getValue());
        }
        return empty >= 0;
    }

    int largestPlacement(Craft craft) {
        int limit = Math.min(craft.operations(), MAX_OPERATIONS_PER_PLACEMENT);
        for (Item input : craft.inputs().keySet()) {
            limit = Math.min(limit, input.getDefaultMaxStackSize());
        }
        // 拿空一堆原料才会释放一个格，能否放下产物不一定随操作数单调变化。
        for (int count = limit; count > 0; count--) {
            if (canPlace(craft, count)) return count;
        }
        return 0;
    }

    boolean canPlace(Craft craft, int operations) {
        CraftInventory placed = new CraftInventory(this);
        return placed.place(craft, operations) && placed.canTake(craft, operations);
    }

    /** 从摆好料的背包算起，部分成交时还要能接回工作站里剩下的输入。 */
    boolean canTake(Craft craft, int operations) {
        Item output = craft.item();
        int empty = emptySlots();
        for (int completed = 1; completed <= operations; completed++) {
            int remaining = operations - completed;
            int outputAndReturns = craft.recipe().yield() * completed
                    + craft.inputs().getOrDefault(output, 0) * remaining;
            int needed = newSlotsNeeded(output, outputAndReturns);
            for (Map.Entry<Item, Integer> input : craft.inputs().entrySet()) {
                if (input.getKey() != output) {
                    needed += newSlotsNeeded(input.getKey(), input.getValue() * remaining);
                }
            }
            if (needed > empty) return false;
        }
        return true;
    }

    private boolean place(Craft craft, int operations) {
        for (Map.Entry<Item, Integer> input : craft.inputs().entrySet()) {
            int remaining = input.getValue() * operations;
            for (int slot = 0; slot < counts.length && remaining > 0; slot++) {
                if (items[slot] != input.getKey()) continue;
                int taken = Math.min(remaining, counts[slot]);
                counts[slot] -= taken;
                remaining -= taken;
                if (counts[slot] == 0) items[slot] = null;
            }
            if (remaining > 0) return false;
        }
        return true;
    }

    private int emptySlots() {
        int empty = 0;
        for (int count : counts) {
            if (count == 0) empty++;
        }
        return empty;
    }

    private int newSlotsNeeded(Item item, int amount) {
        int maxStack = item.getDefaultMaxStackSize();
        for (int slot = 0; slot < counts.length && amount > 0; slot++) {
            if (items[slot] == item) amount -= Math.max(0, maxStack - counts[slot]);
        }
        return RecipeAllocation.craftsFor(amount, maxStack);
    }

    private void insert(Item item, int amount) {
        // 固定取料和产物快移都先合并同物品，再从菜单末端找空槽。
        for (int offset = 0; offset < counts.length && amount > 0; offset++) {
            int slot = fromMenuEnd(offset);
            if (items[slot] == item) amount -= insert(slot, item, amount);
        }
        for (int offset = 0; offset < counts.length && amount > 0; offset++) {
            int slot = fromMenuEnd(offset);
            if (counts[slot] == 0) amount -= insert(slot, item, amount);
        }
    }

    private int insert(int slot, Item item, int amount) {
        int moved = Math.min(amount, Math.max(0, item.getDefaultMaxStackSize() - counts[slot]));
        items[slot] = item;
        counts[slot] += moved;
        return moved;
    }

    private static int fromMenuEnd(int offset) {
        int hotbar = PlayerUtil.HOTBAR_SIZE;
        return offset < hotbar ? hotbar - 1 - offset : Inventory.INVENTORY_SIZE - 1 - (offset - hotbar);
    }
}
