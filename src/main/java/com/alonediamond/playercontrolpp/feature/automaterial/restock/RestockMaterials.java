package com.alonediamond.playercontrolpp.feature.automaterial.restock;

import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** 一轮续料的材料目标与选择规则；不控制建造、菜单、寻路或点击。 */
public final class RestockMaterials {
    /** 建造需要实际散装可用的数量，不能用盒内持有量抵扣。 */
    public record Need(Item item, int target) {}
    public record Selection(Slot slot, Item item) {}

    private final List<Need> needs = new ArrayList<>();
    private final List<Item> wantedItems = new ArrayList<>();
    private final List<Need> needsView = Collections.unmodifiableList(needs);
    private final List<Item> wantedView = Collections.unmodifiableList(wantedItems);

    public void clear() {
        needs.clear();
        wantedItems.clear();
    }

    public void replaceWith(List<LitematicaIntegration.MaterialEntry> entries, int stacks,
                            ToIntFunction<Item> looseCount) {
        clear();
        for (LitematicaIntegration.MaterialEntry entry : entries) {
            if (entry.ignored()) continue;
            ItemStack stack = entry.stack();
            if (stack.isEmpty() || entry.countMissing() <= 0) continue;
            Item item = stack.getItem();
            wantedItems.add(item);
            // 一趟只补配置的组数，避免一种材料占满背包，使其他材料永远取不到。
            int target = Math.min(entry.countMissing(), Math.max(1, stack.getMaxStackSize()) * stacks);
            if (looseCount.applyAsInt(item) < target) needs.add(new Need(item, target));
        }
    }

    public List<Need> needs() { return needsView; }
    public List<Item> wantedItems() { return wantedView; }

    public boolean anyMissing(ToIntFunction<Item> looseCount) {
        for (Need need : needs) {
            if (looseCount.applyAsInt(need.item()) < need.target()) return true;
        }
        return false;
    }

    public boolean boxContainsMissing(ItemStack box, ToIntFunction<Item> looseCount) {
        for (ItemStack inner : ItemUtil.contentsOf(box)) {
            for (Need need : needs) {
                if (ItemUtil.is(inner, need.item()) && looseCount.applyAsInt(need.item()) < need.target()) return true;
            }
        }
        return false;
    }

    /** 满足比例最低者优先；比例相等时保留菜单中的先后顺序。 */
    public Selection selectLooseStack(List<Slot> slots, Predicate<Slot> containerSide,
                                       ToIntFunction<Item> looseCount) {
        Selection best = null;
        double bestRatio = Double.MAX_VALUE;
        for (Slot slot : slots) {
            if (!containerSide.test(slot)) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            if (ItemUtil.isShulkerBox(stack) && !ItemUtil.contentsOf(stack).isEmpty()) continue;
            for (Need need : needs) {
                if (!ItemUtil.is(stack, need.item())) continue;
                int have = looseCount.applyAsInt(need.item());
                if (have < need.target()) {
                    double ratio = (double) have / need.target();
                    if (ratio < bestRatio) {
                        bestRatio = ratio;
                        best = new Selection(slot, need.item());
                    }
                }
                break;
            }
        }
        return best;
    }

    public int gapFor(Item item, int looseCount) {
        int gap = 0;
        for (Need need : needs) {
            if (need.item() == item) gap = Math.max(gap, need.target() - looseCount);
        }
        return gap;
    }
}
