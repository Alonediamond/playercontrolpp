package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Action;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Make;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Plan;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Recipe;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Result;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Source;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Take;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 把求解器的单位步骤编成游戏动作，再按实际库存口径顺序验账。 */
final class CraftBatchActions {
    record Stock(Source model, BlockPos container) {}

    sealed interface Step permits Fetch, Craft {}
    record Fetch(BlockPos container, Map<Item, Integer> items) implements Step {
        Fetch {
            items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
        }
    }

    record Craft(Recipe recipe, List<String> grid, Item item, Map<Item, Integer> inputs,
                 int operations) implements Step {
        Craft withOperations(int count) {
            return new Craft(recipe, grid, item, inputs, count);
        }
    }

    record Balance(int owned, int plain, int reserve) {
        int usable() {
            return Math.max(0, Math.min(plain, owned - reserve));
        }

        Balance add(int change) {
            return new Balance(owned + change, plain + change, reserve);
        }
    }

    private CraftBatchActions() {}

    static Plan executable(Result result) {
        return switch (result.status()) {
            case BEST_IN_MODEL, FEASIBLE_LIMIT -> result.plan();
            case NO_PLAN_IN_MODEL, UNKNOWN_LIMIT -> null;
        };
    }

    static List<Step> translate(Plan plan, List<Stock> stock, Map<String, Item> items) {
        List<Step> steps = new ArrayList<>();
        for (Action action : plan.actions()) {
            if (action instanceof Take take) {
                Stock source = stock.get(take.source());
                if (source.container() == null) continue;
                steps.add(new Fetch(source.container(), Map.of(items.get(take.item()), 1)));
            } else if (action instanceof Make make) {
                Map<Item, Integer> inputs = new LinkedHashMap<>();
                for (String id : make.grid()) {
                    if (!id.isEmpty()) inputs.merge(items.get(id), 1, Integer::sum);
                }
                steps.add(new Craft(make.recipe(), make.grid(), items.get(make.recipe().output()), Map.copyOf(inputs), 1));
            }
        }
        return List.copyOf(steps);
    }

    /** 前提是原始顺序能验账。每个消费者排在原来位于它前面的所有同物品生产者之后。 */
    static List<Step> group(List<Step> ordered, CraftInventory inventory) {
        Map<BlockPos, Map<Item, Integer>> fetches = new LinkedHashMap<>();
        Map<Item, Integer> productionLayer = new HashMap<>();
        List<Map<Craft, Integer>> layers = new ArrayList<>();
        for (Step step : ordered) {
            switch (step) {
                case Fetch fetch -> {
                    Map<Item, Integer> amounts = fetches.computeIfAbsent(fetch.container(), pos -> new LinkedHashMap<>());
                    fetch.items().forEach((item, count) -> amounts.merge(item, count, Integer::sum));
                }
                case Craft craft -> {
                    int layer = 0;
                    for (Item input : craft.inputs().keySet()) {
                        layer = Math.max(layer, productionLayer.getOrDefault(input, -1) + 1);
                    }
                    productionLayer.merge(craft.item(), layer, Math::max);
                    if (layer == layers.size()) layers.add(new LinkedHashMap<>());
                    layers.get(layer).merge(craft.withOperations(1), craft.operations(), Integer::sum);
                }
            }
        }
        List<Step> grouped = new ArrayList<>();
        fetches.forEach((position, amounts) -> grouped.add(new Fetch(position, amounts)));
        for (Map<Craft, Integer> layer : layers) {
            layer.forEach((craft, operations) -> grouped.add(craft.withOperations(operations)));
        }
        // 集中取料可能放不下，保留原来边取边用的执行顺序。
        return inventory.fits(grouped) ? List.copyOf(grouped) : groupAdjacent(ordered);
    }

    private static List<Step> groupAdjacent(List<Step> ordered) {
        List<Step> steps = new ArrayList<>();
        for (Step step : ordered) {
            Step previous = steps.isEmpty() ? null : steps.getLast();
            if (previous instanceof Fetch first && step instanceof Fetch next
                    && first.container().equals(next.container())) {
                Map<Item, Integer> amounts = new LinkedHashMap<>(first.items());
                next.items().forEach((item, count) -> amounts.merge(item, count, Integer::sum));
                steps.set(steps.size() - 1, new Fetch(first.container(), amounts));
            } else if (previous instanceof Craft first && step instanceof Craft next
                    && first.recipe().equals(next.recipe()) && first.grid().equals(next.grid())) {
                steps.set(steps.size() - 1, first.withOperations(first.operations() + next.operations()));
            } else {
                steps.add(step);
            }
        }
        return List.copyOf(steps);
    }

    static boolean canExecute(List<Step> steps, Function<Item, Balance> current, Item root, int target) {
        Map<Item, Balance> ledger = new HashMap<>();
        Function<Item, Balance> remaining = item -> ledger.computeIfAbsent(item, current);
        for (Step step : steps) {
            if (step instanceof Fetch fetch) {
                fetch.items().forEach((item, count) -> ledger.put(item, remaining.apply(item).add(count)));
            } else if (step instanceof Craft craft) {
                for (int operation = 0; operation < craft.operations(); operation++) {
                    for (Map.Entry<Item, Integer> input : craft.inputs().entrySet()) {
                        Balance balance = remaining.apply(input.getKey());
                        if (balance.usable() < input.getValue()) return false;
                        ledger.put(input.getKey(), balance.add(-input.getValue()));
                    }
                    ledger.put(craft.item(), remaining.apply(craft.item()).add(craft.recipe().yield()));
                }
            }
        }
        return remaining.apply(root).owned() >= target;
    }
}
