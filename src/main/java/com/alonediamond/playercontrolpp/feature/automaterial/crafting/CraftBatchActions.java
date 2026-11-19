package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Action;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Make;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Plan;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Result;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Source;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Take;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 把求解器的单位步骤编成游戏动作，再按实际库存口径顺序验账。 */
final class CraftBatchActions {
    record Stock(Source model, BlockPos container) {}

    sealed interface Step permits Fetch, Craft {}
    record Fetch(BlockPos container, Item item, int amount) implements Step {}
    record Craft(Make layout, Item item, Map<Item, Integer> inputs, int operations) implements Step {
        int produced() {
            return operations * layout.recipe().yield();
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
                Item item = items.get(take.item());
                if (last(steps) instanceof Fetch previous && previous.container().equals(source.container())
                        && previous.item() == item) {
                    steps.set(steps.size() - 1, new Fetch(source.container(), item, previous.amount() + 1));
                } else {
                    steps.add(new Fetch(source.container(), item, 1));
                }
            } else if (action instanceof Make make) {
                Item output = items.get(make.recipe().output());
                if (last(steps) instanceof Craft previous && previous.layout().recipe().equals(make.recipe())
                        && previous.layout().grid().equals(make.grid())) {
                    steps.set(steps.size() - 1, new Craft(make, output, previous.inputs(), previous.operations() + 1));
                } else {
                    Map<Item, Integer> inputs = new LinkedHashMap<>();
                    for (String id : make.grid()) {
                        if (!id.isEmpty()) inputs.merge(items.get(id), 1, Integer::sum);
                    }
                    steps.add(new Craft(make, output, Map.copyOf(inputs), 1));
                }
            }
        }
        return List.copyOf(steps);
    }

    private static Step last(List<Step> steps) {
        return steps.isEmpty() ? null : steps.getLast();
    }

    static boolean canExecute(List<Step> steps, Function<Item, Balance> current, Item root, int target) {
        Map<Item, Balance> ledger = new HashMap<>();
        Function<Item, Balance> remaining = item -> ledger.computeIfAbsent(item, current);
        for (Step step : steps) {
            if (step instanceof Fetch fetch) {
                ledger.put(fetch.item(), remaining.apply(fetch.item()).add(fetch.amount()));
            } else if (step instanceof Craft craft) {
                for (int operation = 0; operation < craft.operations(); operation++) {
                    for (Map.Entry<Item, Integer> input : craft.inputs().entrySet()) {
                        Balance balance = remaining.apply(input.getKey());
                        if (balance.usable() < input.getValue()) return false;
                        ledger.put(input.getKey(), balance.add(-input.getValue()));
                    }
                    ledger.put(craft.item(), remaining.apply(craft.item()).add(craft.layout().recipe().yield()));
                }
            }
        }
        return remaining.apply(root).owned() >= target;
    }
}
