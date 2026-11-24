package com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver;

import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Recipe;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Score;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Slot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 各评分独立取最低值，允许拆分配方产量、忽略原料争用和开箱费，因此只会低估真实成本。 */
final class OrderCostBounds {
    private static final int OPERATION_SCALE = 1024;
    private static final Score ZERO = new Score(0, 0, 0);
    private final List<Map<String, Score>> layers = new ArrayList<>();

    OrderCostBounds(Map<String, Long> usable, Set<String> preferred) {
        Map<String, Score> stock = new HashMap<>();
        usable.forEach((item, count) -> {
            if (count > 0) stock.put(item, new Score(preferred.contains(item) ? 1 : 0, SupplySolver.unitPressure(count), 0));
        });
        layers.add(stock);
    }

    void addLayer(Set<BatchLayout> layouts) {
        Map<String, Score> previous = layers.getLast();
        Map<String, Score> next = new HashMap<>(previous);
        for (BatchLayout layout : layouts) {
            Recipe recipe = layout.recipe;
            Score cost = new Score(0, 0, OPERATION_SCALE);
            for (Slot slot : recipe.slots()) {
                if (slot.choices().isEmpty()) continue;
                Score cheapest = null;
                for (String item : slot.choices()) {
                    Score choice = previous.get(item);
                    if (choice != null) cheapest = cheapest == null ? choice : minimum(cheapest, choice);
                }
                cost = add(cost, cheapest, 1);
            }
            int yield = recipe.yield();
            next.merge(recipe.output(), new Score(cost.protectedUnits() / yield, cost.pressure() / yield,
                    cost.operations() / yield), OrderCostBounds::minimum);
        }
        layers.add(next);
    }

    Score estimate(int level, Map<String, Long> due, Map<String, Long> earlier, long crafts) {
        Score total = sum(ZERO, due, level);
        if (!earlier.isEmpty()) total = sum(total, earlier, level - 1);
        return new Score(total.protectedUnits(), total.pressure(), addProduct(crafts, total.operations() / OPERATION_SCALE, 1));
    }

    private Score sum(Score total, Map<String, Long> items, int level) {
        Map<String, Score> prices = layers.get(level);
        for (Map.Entry<String, Long> entry : items.entrySet()) {
            total = add(total, prices.getOrDefault(entry.getKey(), ZERO), entry.getValue());
        }
        return total;
    }

    private static Score minimum(Score a, Score b) {
        return new Score(Math.min(a.protectedUnits(), b.protectedUnits()), Math.min(a.pressure(), b.pressure()),
                Math.min(a.operations(), b.operations()));
    }

    private static Score add(Score total, Score unit, long count) {
        return new Score(addProduct(total.protectedUnits(), unit.protectedUnits(), count),
                addProduct(total.pressure(), unit.pressure(), count), addProduct(total.operations(), unit.operations(), count));
    }

    private static long addProduct(long base, long rate, long count) {
        return rate != 0 && count > (Long.MAX_VALUE - base) / rate ? Long.MAX_VALUE : base + rate * count;
    }

    /** 三项分别核验，软保护必须持平。差值与整除避免浮点误差和乘法溢出，零下界也必须持平。 */
    static boolean withinFivePercent(Score score, Score lower) {
        return score.protectedUnits() == lower.protectedUnits()
                && score.pressure() - lower.pressure() <= lower.pressure() / 20
                && score.operations() - lower.operations() <= lower.operations() / 20;
    }
}
