package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import com.alonediamond.playercontrolpp.compat.CraftingRecipes.SolverRecipe;
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
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Arrays;
import java.util.Collections;
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
        regression.relatedTargetsShareAWindow();
        regression.conversionKeepsItsSeedOrder();
        regression.tightInventoryKeepsFetchOrder();
        regression.fitsResultsAndReturnedInputs();
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
        List<Step> ordered = CraftBatchActions.translate(CraftBatchActions.executable(solved),
                List.of(new Stock(log, chest)), Map.of("board", Items.BIRCH_PLANKS, "log", Items.BIRCH_LOG,
                        "stick", Items.STICK));
        List<Step> steps = CraftBatchActions.group(ordered, new CraftInventory(slot -> ItemStack.EMPTY));
        check(steps.size() == 3 && steps.getFirst().equals(new Fetch(chest, Map.of(Items.BIRCH_LOG, 1))),
                "缓存取料准确绑定到具体箱和物品数量");
        check(steps.get(1) instanceof Craft first && first.item() == Items.BIRCH_PLANKS
                        && steps.getLast() instanceof Craft last && last.operations() == 2 && last.recipe().yield() == 4,
                "先制板，再合并相邻两次相同格位；产量必须保留");
        check(CraftBatchActions.canExecute(steps, item -> new Balance(0, 0, 0), Map.of(Items.STICK, 8)),
                "执行前能重放取料、制作及中间余料");
        check(!CraftBatchActions.canExecute(List.of(steps.getFirst(), steps.getLast(), steps.get(1)),
                        item -> new Balance(0, 0, 0), Map.of(Items.STICK, 8)),
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
        List<Step> ordered = CraftBatchActions.translate(plan, List.of(new Stock(owned, null)),
                Map.of("oak", Items.OAK_PLANKS, "birch", Items.BIRCH_PLANKS, "stick", Items.STICK));
        CraftInventory loose = new CraftInventory(slot -> switch (slot) {
            case 0 -> new ItemStack(Items.OAK_PLANKS, 2);
            case 1 -> new ItemStack(Items.BIRCH_PLANKS, 2);
            default -> ItemStack.EMPTY;
        });
        List<Step> steps = CraftBatchActions.group(ordered, loose);
        check(steps.size() == 2 && steps.getFirst() instanceof Craft first && first.grid().equals(oak)
                        && steps.getLast() instanceof Craft second && second.grid().equals(birch),
                "身上散装不用发取物点击；同一配方用不同变种时保留两次固定摆料");
        Function<Item, Balance> protectedStock = item -> item == Items.STICK ? new Balance(3, 3, 3)
                : new Balance(5, 2, 3);
        check(CraftBatchActions.canExecute(steps, protectedStock, Map.of(Items.STICK, 11)),
                "已有成品只抵扣一次，盒内库存可满足预留");
        check(!CraftBatchActions.canExecute(steps, item -> item == Items.OAK_PLANKS
                        ? new Balance(4, 2, 3) : protectedStock.apply(item), Map.of(Items.STICK, 11)),
                "预留减少到必须占用散装时拒绝执行同一份旧计划");
        check(!CraftBatchActions.canExecute(steps, item -> item == Items.BIRCH_PLANKS
                        ? new Balance(5, 1, 3) : protectedStock.apply(item), Map.of(Items.STICK, 11)),
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
        List<Step> ordered = CraftBatchActions.translate(plan,
                sources.stream().map(source -> new Stock(source, null)).toList(), items);
        ItemStack[] slots = {
                new ItemStack(Items.STONE, 64), new ItemStack(Items.STONE, 64),
                new ItemStack(Items.STONE, 64), new ItemStack(Items.STONE, 64),
                new ItemStack(Items.BIRCH_LOG, 64), new ItemStack(Items.BIRCH_LOG, 64),
                new ItemStack(Items.BIRCH_LOG, 64), new ItemStack(Items.BIRCH_LOG, 62),
                new ItemStack(Items.OAK_PLANKS, 16), new ItemStack(Items.IRON_INGOT, 28),
                new ItemStack(Items.REDSTONE, 28)
        };
        CraftInventory loose = new CraftInventory(index -> index < slots.length ? slots[index] : ItemStack.EMPTY);
        List<Step> steps = CraftBatchActions.group(ordered, loose);
        check(steps.size() == 3 && operationsFor(steps, Items.COBBLESTONE) == 16
                        && operationsFor(steps, Items.BIRCH_PLANKS) == 3
                        && steps.getLast() instanceof Craft last && last.item() == Items.PISTON && last.operations() == 4,
                "把交错的切石、制板和活塞分成三个固定摆料批次");
        Map<Item, Balance> inventory = Map.of(Items.STONE, new Balance(256, 256, 240),
                Items.BIRCH_LOG, new Balance(254, 254, 251), Items.OAK_PLANKS, new Balance(16, 16, 16),
                Items.IRON_INGOT, new Balance(28, 28, 24), Items.REDSTONE, new Balance(28, 28, 24));
        check(CraftBatchActions.canExecute(steps, item -> inventory.getOrDefault(item, new Balance(0, 0, 0)),
                Map.of(Items.PISTON, 4)), "按两种工作站的计划重放，保留原料和木板预留");

        BlockPos chest = new BlockPos(3, 64, 12);
        List<Step> cached = CraftBatchActions.translate(plan,
                sources.stream().map(source -> new Stock(source, chest)).toList(), items);
        List<Step> fetched = CraftBatchActions.group(cached, new CraftInventory(slot -> ItemStack.EMPTY));
        Map<Item, Integer> amounts = Map.of(Items.STONE, 16, Items.BIRCH_LOG, 3, Items.IRON_INGOT, 4, Items.REDSTONE, 4);
        check(fetched.size() == 4 && fetched.getFirst().equals(new Fetch(chest, amounts)),
                "同一来源的四种原料在一次开箱中按各自额度取齐");
        check(CraftBatchActions.canExecute(fetched, item -> new Balance(0, 0, 0), Map.of(Items.PISTON, 4)),
                "集中取货、切石和制板以后才能一次摆齐四次活塞");
    }

    private void relatedTargetsShareAWindow() {
        List<String> oakGrid = List.of("oak", "stick", "oak", "oak", "stick", "oak", "", "", "");
        List<String> birchGrid = List.of("birch", "stick", "birch", "birch", "stick", "birch", "", "", "");
        SupplyModel.Recipe oak = recipe("oak_fence", "oak_fence", 3, oakGrid);
        SupplyModel.Recipe birch = recipe("birch_fence", "birch_fence", 3, birchGrid);
        SupplyModel.Recipe wall = recipe("stone_wall", "stone_wall", 6, grid("stone", "stone"));
        Map<Item, List<SolverRecipe>> catalog = Map.of(Items.OAK_FENCE, List.of(snapshot(oak)),
                Items.BIRCH_FENCE, List.of(snapshot(birch)), Items.COBBLESTONE_WALL, List.of(snapshot(wall)));
        Item root = Items.BIRCH_FENCE;
        List<MaterialItemEntry> requirements = List.of(new MaterialItemEntry(Items.COBBLESTONE_WALL, 1, 64),
                new MaterialItemEntry(Items.OAK_FENCE, 6, 64));
        Function<Item, List<SolverRecipe>> recipes = item -> catalog.getOrDefault(item, List.of());
        Map<Item, Integer> targets = OptimizedCraftBatch.windowTargets(root, 6, requirements, recipes, item -> 0);
        check(targets.equals(Map.of(Items.BIRCH_FENCE, 6, Items.OAK_FENCE, 6)) && targets.keySet().iterator().next() == root,
                "只把共用木棍的未完成栅栏加入当前窗口并保留当前目标顺序");
        check(OptimizedCraftBatch.windowTargets(root, 11, requirements, recipes, item -> 0).equals(Map.of(root, 11)),
                "合计超过窗口的另一项目继续单独备货");
        check(OptimizedCraftBatch.windowTargets(root, 6, requirements, recipes, item -> item == Items.OAK_FENCE ? 6 : 0)
                .equals(Map.of(root, 6)), "已满足的同组项目由预留保护且不重复生产");

        Craft first = new Craft(birch, birchGrid, root, Map.of(Items.BIRCH_PLANKS, 4, Items.STICK, 2), 2);
        Craft second = new Craft(oak, oakGrid, Items.OAK_FENCE, Map.of(Items.OAK_PLANKS, 4, Items.STICK, 2), 2);
        Function<Item, Balance> stock = item -> catalog.containsKey(item) ? new Balance(0, 0, 6) : new Balance(8, 8, 0);
        check(!CraftBatchActions.canExecute(List.of(first), stock, targets),
                "第一种栅栏完成时仍不能把同一窗口的另一种视为已交付");
        check(CraftBatchActions.canExecute(List.of(first, second), stock, targets),
                "核对联合方案的两个成品总数");
    }

    private static SolverRecipe snapshot(SupplyModel.Recipe recipe) {
        return new SolverRecipe(recipe.id(), recipe.type(), recipe.output(), recipe.yield(),
                recipe.slots().stream().map(SupplyModel.Slot::choices).toList());
    }

    private void tightInventoryKeepsFetchOrder() {
        ItemStack[] slots = fullInventory();
        slots[0] = ItemStack.EMPTY;
        slots[1] = new ItemStack(Items.BIRCH_PLANKS, 56);
        CraftInventory inventory = new CraftInventory(index -> slots[index]);
        BlockPos chest = new BlockPos(3, 64, 12);
        List<Step> ordered = List.of(new Fetch(chest, Map.of(Items.BIRCH_LOG, 1)), boards(1),
                new Fetch(chest, Map.of(Items.REDSTONE, 1)));
        check(!inventory.canReceive(Map.of(Items.BIRCH_LOG, 1, Items.REDSTONE, 1)) && inventory.fits(ordered),
                "仅一空槽时先取原木加工，合并木板后再用腾出的槽取红石");
        check(CraftBatchActions.group(ordered, inventory).equals(ordered),
                "集中取货放不下时保留可执行的分段顺序");
    }

    private void conversionKeepsItsSeedOrder() {
        List<String> blockSlot = grid("block", "");
        List<String> ingotSlots = Collections.nCopies(9, "ingot");
        Craft unpack = new Craft(recipe("unpack", "ingot", 9, blockSlot), blockSlot, Items.IRON_INGOT,
                Map.of(Items.IRON_BLOCK, 1), 1);
        Craft pack = new Craft(recipe("pack", "block", 1, ingotSlots), ingotSlots, Items.IRON_BLOCK,
                Map.of(Items.IRON_INGOT, 9), 1);
        List<Step> ordered = List.of(unpack, pack, unpack);
        CraftInventory inventory = new CraftInventory(slot -> slot == 0 ? new ItemStack(Items.IRON_BLOCK) : ItemStack.EMPTY);
        check(CraftBatchActions.group(ordered, inventory).equals(ordered) && inventory.fits(ordered),
                "前一轮产物是后一轮输入时保留依赖链，不能把两次拆块提到一起");
    }

    private void fitsResultsAndReturnedInputs() {
        ItemStack[] slots = fullInventory();
        slots[0] = new ItemStack(Items.BIRCH_LOG, 64);
        slots[1] = new ItemStack(Items.BIRCH_PLANKS, 60);
        check(new CraftInventory(index -> slots[index]).largestPlacement(boards(4)) == 1,
                "空槽为零时仍可合并一份产物，不能按能装下一份放行整批");
        slots[1].set(DataComponents.CUSTOM_NAME, Component.literal("keep"));
        check(new CraftInventory(index -> slots[index]).largestPlacement(boards(1)) == 0,
                "相同物品的带组件堆栈无法合并默认产物");
        slots[0] = new ItemStack(Items.BIRCH_LOG);
        check(new CraftInventory(index -> slots[index]).largestPlacement(boards(1)) == 1,
                "摆空输入槽后可以用刚腾出的格子收产物");
        slots[0] = new ItemStack(Items.BIRCH_LOG, 2);
        check(new CraftInventory(index -> slots[index]).largestPlacement(boards(2)) == 0,
                "只够放产物时拒绝可能无处归还未成交输入的批次");
        slots[1] = ItemStack.EMPTY;
        check(new CraftInventory(index -> slots[index]).largestPlacement(boards(2)) == 2,
                "补一个空槽后可以批量制作并容纳中途的产物与退料");
    }

    private static ItemStack[] fullInventory() {
        ItemStack[] slots = new ItemStack[Inventory.INVENTORY_SIZE];
        Arrays.fill(slots, new ItemStack(Items.DIRT, 64));
        return slots;
    }

    private static Craft boards(int operations) {
        List<String> grid = grid("log", "");
        return new Craft(recipe("boards", "birch", 4, grid), grid, Items.BIRCH_PLANKS,
                Map.of(Items.BIRCH_LOG, 1), operations);
    }

    private static int operationsFor(List<Step> steps, Item item) {
        return steps.stream().filter(Craft.class::isInstance).map(Craft.class::cast)
                .filter(craft -> craft.item() == item).mapToInt(Craft::operations).sum();
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
