package com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver;

import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Make;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Recipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 普通配方中候选集合相同的格位可以一起分配。工作台仍按原九格和每次的具体变种摆料。 */
final class BatchLayout {
    record Group(List<String> choices, List<Integer> slots) {}
    record Share(String item, long units) {}
    final Recipe recipe;
    final List<Group> groups;

    BatchLayout(Recipe recipe) {
        this.recipe = recipe;
        Map<List<String>, List<Integer>> byChoices = new LinkedHashMap<>();
        for (int slot = 0; slot < recipe.slots().size(); slot++) {
            List<String> choices = recipe.slots().get(slot).choices();
            if (!choices.isEmpty()) byChoices.computeIfAbsent(choices, key -> new ArrayList<>()).add(slot);
        }
        groups = byChoices.entrySet().stream().map(entry -> new Group(entry.getKey(), List.copyOf(entry.getValue()))).toList();
    }

    /** 每格有 operations 个位置，同一变种连续填写。只在变种变更处切批，避免按物品数量展开。 */
    List<Make> bind(long operations, List<List<Share>> allocation) {
        TreeSet<Long> cuts = new TreeSet<>(List.of(0L, operations));
        for (List<Share> group : allocation) {
            long offset = 0;
            for (Share share : group) {
                offset = Math.addExact(offset, share.units());
                cuts.add(offset % operations);
            }
        }
        List<Make> batches = new ArrayList<>();
        long start = 0;
        for (long end : cuts) {
            if (end == start) continue;
            String[] grid = new String[9];
            Arrays.fill(grid, "");
            for (int group = 0; group < groups.size(); group++) {
                List<Integer> slots = groups.get(group).slots();
                List<Share> shares = allocation.get(group);
                int choice = 0;
                long boundary = shares.getFirst().units();
                for (int index = 0; index < slots.size(); index++) {
                    long position = Math.addExact(Math.multiplyExact((long) index, operations), start);
                    while (position >= boundary) boundary = Math.addExact(boundary, shares.get(++choice).units());
                    grid[slots.get(index)] = shares.get(choice).item();
                }
            }
            while (start < end) {
                int count = (int) Math.min(Integer.MAX_VALUE, end - start);
                batches.add(new Make(recipe, List.of(grid), false, count));
                start += count;
            }
        }
        return batches;
    }
}
