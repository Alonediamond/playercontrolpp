package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import com.alonediamond.playercontrolpp.compat.CraftingRecipes.Plan;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/** 有界束搜索同时分配各配方格，避免重复格都贪心选择同一小堆材料。 */
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
