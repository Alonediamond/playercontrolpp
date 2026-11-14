package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import com.alonediamond.playercontrolpp.compat.CraftingRecipes;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.config.RawMaterialMode;
import com.alonediamond.playercontrolpp.feature.automaterial.GatherContext;
import com.alonediamond.playercontrolpp.feature.automaterial.container.CachedContainerSource;
import com.alonediamond.playercontrolpp.integration.ChestTrackerIntegration;
import net.minecraft.world.item.Item;

import java.util.*;
import java.util.function.ToIntFunction;

/** 每批重新估价：自有富余、有限范围缓存、递归合成、已知可挖来源，依次增加成本。 */
final class RecipePlanner {
    private static final double UNAVAILABLE = Double.POSITIVE_INFINITY;
    private final GatherContext ctx;
    private final ToIntFunction<Item> reserved;
    private final Map<Item, List<ChestTrackerIntegration.Stock>> stocks = new HashMap<>();
    private final Map<Item, List<CraftingRecipes.Plan>> recipes = new HashMap<>();
    private final Map<Item, Integer> held = new HashMap<>();
    private Map<String, List<net.minecraft.core.BlockPos>> knownMining;
    private boolean awaitingSurvey;
    private record Estimate(Item item, int amount, int depth, Set<Item> path) {}
    private final Map<Estimate, Double> estimates = new HashMap<>();

    RecipePlanner(GatherContext ctx, ToIntFunction<Item> reserved) {
        this.ctx = ctx;
        this.reserved = reserved;
        var scope = new CachedContainerSource().scope(0);
        if (scope.error() == null) {
            boolean boxes = Configs.BaritoneSettings.RECOGNIZE_MIXED_SHULKER_BOX.getBooleanValue();
            for (var stock : ChestTrackerIntegration.getInstance().stockSnapshot(
                    ctx.client.player.blockPosition(), scope.range(), boxes)) {
                stocks.computeIfAbsent(stock.item(), ignored -> new ArrayList<>()).add(stock);
            }
        }
    }

    RecipeAllocation choose(Item item, int count) {
        Set<Item> path = new HashSet<>();
        path.add(item);
        return RecipeAllocation.choose(recipesFor(item), count,
                (ingredient, amount) -> estimate(ingredient, amount, 0, path));
    }

    double ingredientCost(Item item, int count) { return estimate(item, count, 0, new HashSet<>()); }
    boolean awaitingSurvey() { return awaitingSurvey; }

    private List<CraftingRecipes.Plan> recipesFor(Item item) {
        return recipes.computeIfAbsent(item, key -> CraftingRecipes.findAll(ctx.client, key));
    }

    private double estimate(Item item, int amount, int depth, Set<Item> path) {
        Estimate key = new Estimate(item, amount, depth, Set.copyOf(path));
        Double existing = estimates.get(key);
        if (existing != null) return existing;
        double result = estimateUncached(item, amount, depth, path);
        estimates.put(key, result);
        return result;
    }

    private double estimateUncached(Item item, int amount, int depth, Set<Item> path) {
        if (path.contains(item)) return UNAVAILABLE;
        int target = RecipeAllocation.target(reserved.applyAsInt(item), amount);
        int missing = Math.max(0, target - held.computeIfAbsent(item, ctx::countHeld));
        if (missing == 0) return amount * 0.001;
        double cacheCost = 0;
        for (var stock : stocks.getOrDefault(item, List.of())) {
            int take = Math.min(missing, stock.count());
            if (take <= 0) continue;
            cacheCost += 10 + Math.sqrt(stock.position().distSqr(ctx.client.player.blockPosition())) + take * .05;
            missing -= take;
            if (missing == 0) return cacheCost;
        }
        if (depth >= 3) return cacheCost + 100_000 + missing;
        double fallback = UNAVAILABLE;
        if (Configs.BaritoneSettings.RAW_MATERIAL_CRAFT_MODE.getOptionListValue() == RawMaterialMode.WORLD_SEARCH
                && canMine(item)) {
            fallback = 1000 + missing * 20.0;
        }
        path.add(item);
        RecipeAllocation allocation = RecipeAllocation.choose(recipesFor(item), missing,
                (child, needed) -> estimate(child, needed, depth + 1, path));
        path.remove(item);
        if (allocation != null) fallback = Math.min(fallback, 100 + allocation.cost());
        return cacheCost + fallback;
    }

    private boolean canMine(Item item) {
        if (MiningSources.blocksFor(item).isEmpty()) return false;
        if (knownMining == null) {
            var survey = MiningSources.survey();
            awaitingSurvey |= !survey.ready();
            knownMining = survey.positions();
        }
        int radius = Configs.BaritoneSettings.MINING_SEARCH_RADIUS.getIntegerValue();
        return MiningSources.blocksFor(item).stream().anyMatch(id -> knownMining.getOrDefault(id, List.of()).stream()
                .anyMatch(pos -> pos.distSqr(ctx.client.player.blockPosition()) <= (long) radius * radius));
    }
}
