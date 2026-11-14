package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import com.alonediamond.playercontrolpp.compat.CraftingRecipes.Plan;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/**
 * 为整批合成同时选择配方及每格的合法物品变种。
 *
 * <p>先按实际单次产量向上取整合成次数，再逐格扩展候选方案，把同种物品在重复格中的
 * 用量累加后交给 {@link Cost} 估价。每扩展一格只保留成本最低的 32 个方案，最后比较
 * 各配方的完整方案。这样不会让每一格都独立贪心地选中同一小堆材料；不同格仍可使用
 * 配方允许的不同木板等变种。选中的格子与总投入量同时返回，采集和摆料共用这一选择。
 *
 * <p>这是有界束搜索：估价由调用方提供，保留宽度限制了计算量，也可能剪掉后续更优的
 * 分支，因此不承诺全局最优。相同成本按候选注册名和原配方顺序稳定选择。
 */
public record RecipeAllocation(Plan recipe, Map<Item, Integer> ingredients, int crafts, double cost) {
    private static final int BEAM_WIDTH = 32;
    @FunctionalInterface
    public interface Cost { double estimate(Item item, int count); }
    private record Candidate(List<List<ItemStack>> grid, Map<Item, Integer> counts, double cost) {}

    public static RecipeAllocation choose(List<Plan> recipes, int outputNeeded, Cost cost) {
        RecipeAllocation best = null;
        for (Plan recipe : recipes) {
            int crafts = craftsFor(outputNeeded, recipe.outputCount());
            List<Candidate> beam = List.of(new Candidate(List.of(), Map.of(), 0));
            for (List<ItemStack> slot : recipe.ingredients()) {
                List<Candidate> next = new ArrayList<>();
                List<Item> options = slot.stream().map(ItemStack::getItem).distinct()
                        .sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString())).toList();
                for (Candidate previous : beam) {
                    if (options.isEmpty()) {
                        List<List<ItemStack>> grid = new ArrayList<>(previous.grid);
                        grid.add(List.of());
                        next.add(new Candidate(grid, previous.counts, previous.cost));
                    }
                    for (Item item : options) {
                        Map<Item, Integer> counts = new LinkedHashMap<>(previous.counts);
                        counts.merge(item, crafts, RecipeAllocation::target);
                        double total = counts.entrySet().stream()
                                .mapToDouble(entry -> cost.estimate(entry.getKey(), entry.getValue())).sum();
                        List<List<ItemStack>> grid = new ArrayList<>(previous.grid);
                        grid.add(List.of(new ItemStack(item)));
                        next.add(new Candidate(grid, counts, total));
                    }
                }
                next.sort(Comparator.comparingDouble(Candidate::cost));
                beam = next.stream().limit(BEAM_WIDTH).toList();
                if (beam.isEmpty()) break;
            }
            if (beam.isEmpty() || beam.get(0).counts.isEmpty()) continue;
            Candidate chosen = beam.get(0);
            if (!Double.isFinite(chosen.cost)) continue;
            if (best == null || chosen.cost < best.cost) {
                best = new RecipeAllocation(recipe.select(chosen.grid),
                        Collections.unmodifiableMap(new LinkedHashMap<>(chosen.counts)), crafts, chosen.cost);
            }
        }
        return best;
    }

    public static int craftsFor(int outputNeeded, int outputPerCraft) {
        return (int) Math.min(Integer.MAX_VALUE,
                ((long) Math.max(0, outputNeeded) + Math.max(1, outputPerCraft) - 1) / Math.max(1, outputPerCraft));
    }

    public static int target(int reserve, int consumed) {
        return (int) Math.min(Integer.MAX_VALUE, (long) Math.max(0, reserve) + Math.max(0, consumed));
    }
}
