package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import com.alonediamond.playercontrolpp.feature.automaterial.crafting.CraftBatchActions.Balance;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.CraftBatchActions.Craft;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.CraftBatchActions.Fetch;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.CraftBatchActions.Step;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.CraftBatchActions.Stock;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplySolver;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialItemEntry;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialRequirements;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class CraftBatchActionsRegression {
    private int checks;

    public static int run() {
        CraftBatchActionsRegression regression = new CraftBatchActionsRegression();
        regression.cachedStockAndCraftOrder();
        regression.fixedVariantsAndReserves();
        regression.stonecuttingFeedsPistons();
        regression.statusAndInvalidation();
        return regression.checks;
    }

    private void cachedStockAndCraftOrder() {
        SupplyModel.Recipe planks = recipe("boards", "board", 4, grid("log", ""));
        SupplyModel.Recipe sticks = recipe("sticks", "stick", 4, grid("board", "board"));
        BlockPos chest = new BlockPos(3, 64, 12);
        SupplyModel.Source log = new SupplyModel.Source("chest", "log", 1, 1);
        SupplyModel.Request request = new SupplyModel.Request(new SupplyModel.Catalog(List.of(planks, sticks)),
                List.of(log), List.of(new SupplyModel.Goal("stick", 8, null)), Map.of(), Set.of(), Set.of(), 2,
                SupplyModel.Policy.FEWEST_OPERATIONS, SupplyModel.Mode.PREPARE);
        SupplyModel.Result solved = SupplySolver.solve(request, 5000);
        List<Step> steps = CraftBatchActions.translate(CraftBatchActions.executable(solved),
                List.of(new Stock(log, chest)), Map.of("board", Items.BIRCH_PLANKS, "log", Items.BIRCH_LOG,
                        "stick", Items.STICK));
        check(steps.size() == 3 && steps.getFirst().equals(new Fetch(chest, Items.BIRCH_LOG, 1)),
                "缓存取料准确绑定到具体箱和物品数量");
        check(steps.get(1) instanceof Craft first && first.item() == Items.BIRCH_PLANKS
                        && steps.getLast() instanceof Craft last && last.operations() == 2 && last.produced() == 8,
                "先制板，再合并相邻两次相同格位；产量必须保留");
        check(CraftBatchActions.canExecute(steps, item -> new Balance(0, 0, 0), Items.STICK, 8),
                "执行前能重放取料、制作及中间余料");
        check(!CraftBatchActions.canExecute(List.of(steps.getFirst(), steps.getLast(), steps.get(1)),
                        item -> new Balance(0, 0, 0), Items.STICK, 8),
                "库存终值相同也不能提前执行依赖子产物的制作");
    }

    private void fixedVariantsAndReserves() {
        List<String> oak = grid("oak", "oak");
        List<String> birch = grid("birch", "birch");
        SupplyModel.Recipe recipe = new SupplyModel.Recipe("sticks", "crafting_shaped", "stick", 4,
                List.of(new SupplyModel.Slot(List.of("oak", "birch")), new SupplyModel.Slot(List.of()),
                        new SupplyModel.Slot(List.of()), new SupplyModel.Slot(List.of("oak", "birch")),
                        new SupplyModel.Slot(List.of()), new SupplyModel.Slot(List.of()),
                        new SupplyModel.Slot(List.of()), new SupplyModel.Slot(List.of()), new SupplyModel.Slot(List.of())));
        SupplyModel.Source owned = new SupplyModel.Source("inventory", "oak", 2, 0);
        SupplyModel.Plan plan = new SupplyModel.Plan(List.of(new SupplyModel.Take(0, "oak"),
                new SupplyModel.Take(0, "oak"), new SupplyModel.Make(recipe, oak, false),
                new SupplyModel.Make(recipe, birch, false)), Map.of(), new SupplyModel.Score(0, 0, 0),
                SupplyModel.Mode.PREPARE);
        List<Step> steps = CraftBatchActions.translate(plan, List.of(new Stock(owned, null)),
                Map.of("oak", Items.OAK_PLANKS, "birch", Items.BIRCH_PLANKS, "stick", Items.STICK));
        check(steps.size() == 2 && steps.getFirst() instanceof Craft first && first.layout().grid().equals(oak)
                        && steps.getLast() instanceof Craft second && second.layout().grid().equals(birch),
                "身上散装不用发取物点击；同一配方用不同变种时保留两次固定摆料");
        Function<Item, Balance> protectedStock = item -> item == Items.STICK ? new Balance(3, 3, 3)
                : new Balance(5, 2, 3);
        check(CraftBatchActions.canExecute(steps, protectedStock, Items.STICK, 11),
                "已有成品只抵扣一次，盒内库存可满足预留");
        check(!CraftBatchActions.canExecute(steps, item -> item == Items.OAK_PLANKS
                        ? new Balance(4, 2, 3) : protectedStock.apply(item), Items.STICK, 11),
                "预留减少到必须占用散装时拒绝执行同一份旧计划");
        check(!CraftBatchActions.canExecute(steps, item -> item == Items.BIRCH_PLANKS
                        ? new Balance(5, 1, 3) : protectedStock.apply(item), Items.STICK, 11),
                "同 ID 的盒内或带组件物品不能补足普通散装输入");
    }

    private void statusAndInvalidation() {
        SupplyModel.Plan plan = new SupplyModel.Plan(List.of(), Map.of(), new SupplyModel.Score(0, 0, 0),
                SupplyModel.Mode.PREPARE);
        check(CraftBatchActions.executable(new SupplyModel.Result(SupplyModel.Status.FEASIBLE_LIMIT,
                plan, 1, List.of())) == plan, "超限时仍可执行完整候选");
        check(CraftBatchActions.executable(new SupplyModel.Result(SupplyModel.Status.UNKNOWN_LIMIT,
                null, 1, List.of())) == null, "超限无候选不发游戏动作");
        check(CraftBatchActions.executable(new SupplyModel.Result(SupplyModel.Status.NO_PLAN_IN_MODEL,
                null, 1, List.of())) == null, "模型无解不发游戏动作");
        MaterialRequirements requirements = new MaterialRequirements();
        long initial = requirements.revision();
        requirements.replaceWith(List.of(new MaterialItemEntry(Items.STICK, 8, 64)));
        long replaced = requirements.revision();
        requirements.clear();
        check(initial != replaced && replaced != requirements.revision(), "批次替换和清空使旧方案失效");
    }

    private void stonecuttingFeedsPistons() {
        SupplyModel.Slot boards = new SupplyModel.Slot(List.of("oak", "birch"));
        SupplyModel.Slot cobble = new SupplyModel.Slot(List.of("cobble"));
        SupplyModel.Slot iron = new SupplyModel.Slot(List.of("iron"));
        SupplyModel.Slot dust = new SupplyModel.Slot(List.of("redstone"));
        SupplyModel.Recipe piston = new SupplyModel.Recipe("pistons", "crafting_shaped", "piston", 1,
                List.of(boards, boards, boards, cobble, iron, cobble, cobble, dust, cobble));
        SupplyModel.Recipe planks = recipe("boards", "birch", 4, grid("log", ""));
        SupplyModel.Recipe cutting = new SupplyModel.Recipe("cut_cobble", "stonecutting", "cobble", 1,
                List.of(new SupplyModel.Slot(List.of("stone")), new SupplyModel.Slot(List.of()),
                        new SupplyModel.Slot(List.of()), new SupplyModel.Slot(List.of()),
                        new SupplyModel.Slot(List.of()), new SupplyModel.Slot(List.of()),
                        new SupplyModel.Slot(List.of()), new SupplyModel.Slot(List.of()),
                        new SupplyModel.Slot(List.of())));
        List<SupplyModel.Source> sources = List.of(
                new SupplyModel.Source("inventory", "stone", 256, 0),
                new SupplyModel.Source("inventory", "log", 254, 0),
                new SupplyModel.Source("inventory", "oak", 16, 0),
                new SupplyModel.Source("inventory", "iron", 28, 0),
                new SupplyModel.Source("inventory", "redstone", 28, 0));
        List<SupplyModel.Goal> goals = List.of(new SupplyModel.Goal("piston", 4, null));
        Set<String> stopAt = Set.of("stone", "iron", "redstone");
        SupplyModel.Request workbenchOnly = new SupplyModel.Request(new SupplyModel.Catalog(List.of(piston, planks)),
                sources, goals, Map.of(), Set.of(), stopAt, 5, SupplyModel.Policy.SAVE_STOCK, SupplyModel.Mode.PREPARE);
        check(SupplySolver.solve(workbenchOnly, 60_000).status() == SupplyModel.Status.NO_PLAN_IN_MODEL,
                "复现玩家身上只有石头时，工作台模型缺圆石而无解");
        SupplyModel.Request request = new SupplyModel.Request(new SupplyModel.Catalog(List.of(piston, planks, cutting)),
                sources, goals, Map.of(), Set.of(), stopAt, 5, SupplyModel.Policy.SAVE_STOCK, SupplyModel.Mode.PREPARE);
        SupplyModel.Plan plan = CraftBatchActions.executable(SupplySolver.solve(request, 60_000));
        check(plan != null && Long.valueOf(3).equals(plan.shoppingList().get("log"))
                        && !plan.shoppingList().containsKey("oak"),
                "同时找到切石和白桦制板步骤，保留橡木木板");
        Map<String, Item> items = Map.of("stone", Items.STONE, "cobble", Items.COBBLESTONE,
                "oak", Items.OAK_PLANKS, "birch", Items.BIRCH_PLANKS, "log", Items.BIRCH_LOG,
                "piston", Items.PISTON, "iron", Items.IRON_INGOT, "redstone", Items.REDSTONE);
        List<Step> steps = CraftBatchActions.translate(plan,
                sources.stream().map(source -> new Stock(source, null)).toList(), items);
        int cuts = 0;
        for (Step step : steps) {
            if (step instanceof Craft craft && "stonecutting".equals(craft.layout().recipe().type())) {
                cuts += craft.operations();
            }
        }
        check(cuts == 16, "切石动作保留工位类型和十六次操作额度");
        Map<Item, Balance> inventory = Map.of(Items.STONE, new Balance(256, 256, 240),
                Items.BIRCH_LOG, new Balance(254, 254, 251), Items.OAK_PLANKS, new Balance(16, 16, 16),
                Items.IRON_INGOT, new Balance(28, 28, 24), Items.REDSTONE, new Balance(28, 28, 24));
        check(CraftBatchActions.canExecute(steps, item -> inventory.getOrDefault(item, new Balance(0, 0, 0)),
                Items.PISTON, 4), "按两种工作站的计划重放，保留原料和木板预留");
    }

    private static SupplyModel.Recipe recipe(String id, String item, int yield, List<String> grid) {
        return new SupplyModel.Recipe(id, "crafting_shaped", item, yield, grid.stream()
                .map(slot -> new SupplyModel.Slot(slot.isEmpty() ? List.of() : List.of(slot))).toList());
    }

    private static List<String> grid(String first, String second) {
        return List.of(first, "", "", second, "", "", "", "", "");
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
