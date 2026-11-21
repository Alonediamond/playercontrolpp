package com.alonediamond.playercontrolpp.feature.automaterial.solver;

import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Action;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Catalog;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Delivery;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Goal;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Hint;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Make;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Mode;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Plan;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Policy;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Recipe;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Request;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Result;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Slot;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Source;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Status;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Take;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplySolver;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.WholeOrderSolver;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplySchedule;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Score;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration.RawNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SupplySolverRegression {
    private static final long BUDGET = 200_000;
    private static final String PISTON = "minecraft:piston";
    private static final String OAK = "minecraft:oak_planks";
    private static final String BIRCH = "minecraft:birch_planks";
    private static final String LOG = "minecraft:oak_log";
    private static final String COBBLE = "minecraft:cobblestone";
    private static final String IRON = "minecraft:iron_ingot";
    private static final String DUST = "minecraft:redstone";
    private int checks;
    private boolean showPlans;

    public static void main(String[] args) {
        SupplySolverRegression suite = new SupplySolverRegression();
        if (List.of(args).contains("--two-fences")) {
            suite.showPlans = true;
            suite.twoFences();
            suite.fencesUnderRuntimeBudget();
            System.out.println("Two fences: " + suite.checks + " checks passed");
            return;
        }
        if (List.of(args).contains("--survival-piston")) {
            suite.showPlans = true;
            suite.survivalPiston();
            System.out.println("Survival piston: " + suite.checks + " checks passed");
            return;
        }
        suite.showPlans = List.of(args).contains("--demo");
        System.out.println("Supply solver: " + suite.runChecks() + " checks passed");
    }

    public static int run() {
        return new SupplySolverRegression().runChecks();
    }

    private int runChecks() {
        pistons();
        pistonUnderNodeLimit();
        survivalPiston();
        twoFences();
        fencesUnderRuntimeBudget();
        mineralRoutes();
        sharedAndRounded();
        cyclesAndLimits();
        cancelledBeforeIndexing();
        smallOracle();
        wholeOrderWoodwork();
        wholeOrderSticks();
        wholeOrderSourcesAndDepth();
        wholeOrderOracle();
        constructionOrderWithShortages();
        concreteWarehouse();
        distributedWarehouse();
        partialOrdersShareStock();
        return checks;
    }

    private void pistons() {
        Catalog catalog = new Catalog(List.of(RecipeFixtures.read(PISTON), RecipeFixtures.read(OAK), RecipeFixtures.read(BIRCH)));
        List<Source> stock = List.of(stock(OAK, 500), stock(LOG, 1000), stock(COBBLE, 16), stock(IRON, 4), stock(DUST, 4));
        Hint hint = RecipeFixtures.hint(new RawNode(PISTON, 4, "crafting_shaped", List.of(
                new RawNode(OAK, 12, "crafting_shapeless", List.of(leaf(LOG, 3))),
                leaf(COBBLE, 16), leaf(IRON, 4), leaf(DUST, 4))));
        List<Goal> goals = List.of(new Goal(PISTON, 4, hint));
        Plan conserve = solved(new Request(catalog, stock, goals, Map.of(), Set.of(OAK), Set.of(), 2, Policy.SAVE_STOCK, Mode.PREPARE));
        check(conserve.shoppingList().get(LOG) == 3 && !conserve.shoppingList().containsKey(OAK), "500 板不动，取 3 根原木");
        check(conserve.actions().stream().filter(Make.class::isInstance).map(Make.class::cast).allMatch(Make::followsHint),
                "合法且同样合适时保留提示路径、格位和比例");
        Plan abundant = solved(new Request(catalog, stock, goals, Map.of(), Set.of(), Set.of(), 2, Policy.SAVE_STOCK, Mode.PREPARE));
        check(abundant.shoppingList().equals(conserve.shoppingList()), "无需手标木板，按原库存消耗比例也选择富余原木");
        Plan direct = solved(new Request(catalog, stock, goals, Map.of(), Set.of(OAK), Set.of(), 2, Policy.FEWEST_OPERATIONS, Mode.PREPARE));
        check(direct.shoppingList().get(OAK) == 12 && !direct.shoppingList().containsKey(LOG), "同箱少合成取 12 板");
        List<Source> limited = new ArrayList<>(stock);
        limited.set(1, stock(LOG, 2));
        Request mixed = new Request(catalog, limited, goals, Map.of(), Set.of(OAK), Set.of(), 2, Policy.SAVE_STOCK, Mode.PREPARE);
        Plan fallback = solved(mixed);
        check(fallback.shoppingList().get(OAK) == 4 && fallback.shoppingList().get(LOG) == 2, "软保护允许混用");
        Request hard = new Request(catalog, limited, goals, Map.of(OAK, 500), Set.of(OAK), Set.of(), 2, Policy.SAVE_STOCK, Mode.PREPARE);
        check(SupplySolver.solve(hard, BUDGET).status() == Status.NO_PLAN_IN_MODEL, "硬保护不允许动用木板");
        limited.set(1, stock("minecraft:birch_log", 3));
        Plan raw = solved(new Request(catalog, limited, goals, Map.of(OAK, 500), Set.of(), Set.of(), 2, Policy.SAVE_STOCK, Mode.RAW_ONLY));
        check(raw.shoppingList().get("minecraft:birch_log") == 3 && raw.executable().stream().allMatch(Take.class::isInstance),
                "只备料可改走白桦；不执行模拟的制板和活塞操作");
        check(raw.actions().stream().filter(Make.class::isInstance).map(Make.class::cast).noneMatch(Make::followsHint),
                "不将替代路径报告成原树");
        if (showPlans) {
            System.out.println("省库存：" + abundant.shoppingList());
            System.out.println("少操作：" + direct.shoppingList());
            System.out.println("原木不足：" + fallback.shoppingList());
            System.out.println("只备原料：" + raw.shoppingList());
        }
    }

    private void mineralRoutes() {
        String block = "minecraft:iron_block";
        String nugget = "minecraft:iron_nugget";
        String redBlock = "minecraft:redstone_block";
        Catalog catalog = new Catalog(List.of(RecipeFixtures.read("minecraft:iron_ingot_from_iron_block"),
                RecipeFixtures.read("minecraft:iron_ingot_from_nuggets"), RecipeFixtures.read(block),
                RecipeFixtures.read(DUST), RecipeFixtures.read(redBlock)));
        Request order = new Request(catalog, List.of(stock(block, 1), stock(nugget, 9), stock(redBlock, 1)),
                List.of(goal(IRON, 10), goal(DUST, 4), goal(DUST, 4)), Map.of(), Set.of(), Set.of(),
                3, Policy.SAVE_STOCK, Mode.PREPARE);
        Plan plan = solved(order);
        check(plan.shoppingList().equals(Map.of(block, 1L, nugget, 9L, redBlock, 1L)), "铁块和铁粒各供一部分，红石跨目标复用");
        check(plan.surplus().equals(Map.of(DUST, 1L)), "实际配方拆块余一粉");
        Recipe table = RecipeFixtures.read("minecraft:crafting_table");
        check(!table.slots().get(4).choices().isEmpty() && table.slots().get(2).choices().isEmpty()
                && table.slots().get(5).choices().isEmpty(), "真实 2×2 图案保留行距");
    }

    private void pistonUnderNodeLimit() {
        String birchLog = "minecraft:birch_log";
        Catalog catalog = new Catalog(List.of(RecipeFixtures.read(PISTON), RecipeFixtures.read(OAK), RecipeFixtures.read(BIRCH),
                RecipeFixtures.read("minecraft:acacia_planks"), RecipeFixtures.read("minecraft:birch_wood"),
                RecipeFixtures.read("minecraft:iron_ingot_from_iron_block")));
        List<Source> stock = List.of(stock(OAK, 500), stock(birchLog, 1000), stock(COBBLE, 16), stock(IRON, 4), stock(DUST, 4));
        Hint oakRoute = RecipeFixtures.hint(new RawNode(PISTON, 4, "crafting_shaped", List.of(
                new RawNode(OAK, 12, "crafting_shapeless", List.of(leaf(LOG, 3))),
                leaf(COBBLE, 16), leaf(IRON, 4), leaf(DUST, 4))));
        Request order = new Request(catalog, stock, List.of(new Goal(PISTON, 4, oakRoute)), Map.of(),
                Set.of(), Set.of(), 5, Policy.SAVE_STOCK, Mode.PREPARE);
        Result result = SupplySolver.solve(order, 128);
        check(result.status() == Status.FEASIBLE_LIMIT && result.plan().shoppingList()
                        .equals(Map.of(birchLog, 3L, COBBLE, 16L, IRON, 4L, DUST, 4L)),
                "省库存须在节点限额内优先尝试富余白桦原木，同时保留其他分支");
    }

    /** IDEA 选 26.2 的测试类路径，以 --survival-piston 调试 main，可跳过其余回归。 */
    private void survivalPiston() {
        String birchLog = "minecraft:birch_log";
        String spruceLog = "minecraft:spruce_log";
        String ironBlock = "minecraft:iron_block";
        String redBlock = "minecraft:redstone_block";
        Recipe piston = RecipeFixtures.read(PISTON);
        Catalog catalog = new Catalog(List.of(piston, RecipeFixtures.read(OAK), RecipeFixtures.read(BIRCH),
                RecipeFixtures.read("minecraft:spruce_planks"), RecipeFixtures.read(DUST),
                RecipeFixtures.read("minecraft:iron_ingot_from_iron_block")));
        // 石材格是单个物品而非石材标签；放入更多深板岩圆石不能使它成为合法解。
        List<Source> stock = List.of(stock(OAK, 3), stock(LOG, 8), stock(birchLog, 24), stock(spruceLog, 1),
                stock(COBBLE, 12), stock("minecraft:cobbled_deepslate", 64),
                stock(DUST, 2), stock(redBlock, 8), stock(IRON, 12), stock(ironBlock, 1));
        // 故意先推荐橡木。三个木板格不绑定木种；只有深度、真实配方与库存是硬条件。
        Hint oakRoute = RecipeFixtures.hint(new RawNode(PISTON, 1, "crafting_shaped", List.of(
                new RawNode(OAK, 3, "crafting_shapeless", List.of(leaf(LOG, 1))), leaf(COBBLE, 4),
                new RawNode(DUST, 1, "crafting_shapeless", List.of(leaf(redBlock, 1))),
                new RawNode(IRON, 1, "crafting_shapeless", List.of(leaf(ironBlock, 1))))));
        Goal target = new Goal(PISTON, 1, oakRoute);
        Request single = new Request(catalog, stock, List.of(target), Map.of(), Set.of(), Set.of(), 2,
                Policy.SAVE_STOCK, Mode.PREPARE);
        Plan singlePlan = solved(single);
        check(singlePlan.shoppingList().equals(Map.of(birchLog, 1L, COBBLE, 4L, redBlock, 1L, IRON, 1L)),
                "省库存：不耗尽橡木板、不碰稀少云杉、用白桦制板，拆红石块但不拆铁块");
        check(singlePlan.surplus().equals(Map.of(BIRCH, 1L, DUST, 8L)), "只用三块板和一份粉，其余留给后续需求");
        Make pistonStep = singlePlan.actions().stream().filter(Make.class::isInstance).map(Make.class::cast)
                .filter(step -> step.recipe().output().equals(PISTON)).findFirst().orElseThrow();
        check(pistonStep.grid().equals(List.of(BIRCH, BIRCH, BIRCH, COBBLE, IRON, COBBLE, COBBLE, DUST, COBBLE)),
                "最终摆料与选定变种相同，而非临时改用背包里的橡木板");

        // 还是同一份库存，只多一项必须交付的订单，不设置硬保留。只求活塞的最优解现在不再可行。
        Request combined = new Request(catalog, stock, List.of(target, goal(birchLog, 24)), Map.of(), Set.of(),
                Set.of(), 2, Policy.SAVE_STOCK, Mode.PREPARE);
        Plan combinedPlan = solved(combined);
        check(combinedPlan.shoppingList().equals(Map.of(LOG, 1L, birchLog, 24L, COBBLE, 4L, redBlock, 1L, IRON, 1L)),
                "全单最优：交付全部白桦原木时，活塞改用橡木原木");
        if (showPlans) {
            System.out.println("单做活塞：" + singlePlan.shoppingList() + "；九格：" + pistonStep.grid());
            System.out.println("再交付 24 白桦原木：" + combinedPlan.shoppingList());
        }
    }

    /** 专用板和通用棍共用库存；不预先告诉求解器应把哪种原木留给谁。 */
    private void twoFences() {
        String oakFence = "minecraft:oak_fence";
        String spruceFence = "minecraft:spruce_fence";
        String spruce = "minecraft:spruce_log";
        String birch = "minecraft:birch_log";
        String stick = "minecraft:stick";
        List<Recipe> recipes = List.of(RecipeFixtures.read(oakFence), RecipeFixtures.read(spruceFence),
                RecipeFixtures.read(stick), RecipeFixtures.read(OAK), RecipeFixtures.read("minecraft:spruce_planks"),
                RecipeFixtures.read(BIRCH));
        List<Source> stock = List.of(stock(LOG, 2), stock(spruce, 2), stock(birch, 1));
        List<Goal> goals = List.of(goal(oakFence, 6), goal(spruceFence, 6));
        for (List<Goal> order : List.of(goals, goals.reversed())) {
            Plan plan = solved(request(recipes, stock, order, 3));
            check(plan.shoppingList().equals(Map.of(LOG, 2L, spruce, 2L, birch, 1L)) && plan.surplus().isEmpty(),
                    "五根原木恰好满足两种栅栏，无需软偏好或库存下限");
            List<Make> batches = plan.actions().stream().filter(Make.class::isInstance).map(Make.class::cast)
                    .filter(make -> make.recipe().output().equals(stick)).toList();
            check(batches.size() == 2 && batches.stream().allMatch(make -> make.recipe().yield() == 4
                    && make.grid().stream().filter(item -> !item.isEmpty()).toList().equals(List.of(BIRCH, BIRCH))),
                    "两次制棍均使用两块白桦板；不是只检查原木采购总数");
            if (showPlans) System.out.println(order + " -> " + plan.shoppingList() + "; sticks: " + batches.getFirst().grid());
        }
        Result missing = SupplySolver.solve(request(recipes, stock.subList(0, 2), goals, 3), BUDGET);
        check(missing.status() == Status.NO_PLAN_IN_MODEL && missing.plan() == null, "拿走白桦原木后不是同一可行订单");
    }

    private void fencesUnderRuntimeBudget() {
        String oakFence = "minecraft:oak_fence";
        String birchFence = "minecraft:birch_fence";
        String spareLog = "minecraft:spruce_log";
        String sparePlanks = "minecraft:spruce_planks";
        String stick = "minecraft:stick";
        Recipe sticks = RecipeFixtures.read(stick);
        List<Recipe> recipes = new ArrayList<>(List.of(RecipeFixtures.read(oakFence), RecipeFixtures.read(birchFence), sticks));
        for (String planks : sticks.slots().getFirst().choices()) {
            recipes.add(RecipeFixtures.read(planks));
        }
        for (String wood : List.of("oak", "birch", "spruce")) {
            recipes.add(RecipeFixtures.read("minecraft:" + wood + "_wood"));
        }
        List<Source> stock = List.of(stock(LOG, 2), stock("minecraft:birch_log", 2), stock(spareLog, 1));
        Hint oakStick = new Hint(stick, 4, "crafting_shaped", List.of(
                new Hint(OAK, 2, "crafting_shapeless", List.of(new Hint(LOG, 1, "base", List.of())))));
        Hint birchHint = new Hint(birchFence, 6, "crafting_shaped", List.of(oakStick));
        List<Goal> goals = List.of(new Goal(birchFence, 6, birchHint), goal(oakFence, 6));
        for (List<Goal> order : List.of(goals, goals.reversed())) {
            Request request = request(recipes, stock, order, 5);
            Result result = SupplySolver.solve(request, 256);
            check(result.plan() != null, "运行时大标签下先取得两种栅栏均可交付的方案");
            Plan plan = result.plan();
            List<Make> sticksMade = plan.actions().stream().filter(Make.class::isInstance).map(Make.class::cast)
                    .filter(make -> make.recipe().output().equals(stick)).toList();
            check(sticksMade.size() == 2 && sticksMade.stream().allMatch(make -> make.grid().stream()
                            .filter(item -> !item.isEmpty()).toList().equals(List.of(sparePlanks, sparePlanks))),
                    "通用木棍用富余木料，保留橡木和白桦给各自的固定格");
            replay(request, plan);
            Plan whole = exact(request);
            check(whole.shoppingList().equals(plan.shoppingList()), "整单证明与两种栅栏的完整取料相符");
        }
    }

    private void sharedAndRounded() {
        Recipe flexible = simple("flex", "first", 1, List.of(new Slot(List.of("oak", "spruce"))));
        Recipe strict = simple("strict", "second", 1, List.of(new Slot(List.of("oak"))));
        List<Goal> both = List.of(goal("first", 1), goal("second", 1));
        Plan global = solved(request(List.of(flexible, strict), List.of(stock("oak", 1), stock("spruce", 1)), both, 2));
        check(global.actions().stream().filter(Make.class::isInstance).map(Make.class::cast).findFirst().orElseThrow()
                .grid().getFirst().equals("spruce"), "全单回退，留橡木给不可替换的需求");
        Plan switchAcross = solved(request(List.of(flexible), List.of(stock("oak", 1), stock("spruce", 1)), List.of(goal("first", 2)), 1));
        check(switchAcross.shoppingList().size() == 2, "同一格跨次换种");
        Recipe bulk = simple("bulk", "K", 4, Collections.nCopies(3, new Slot(List.of("A"))));
        Plan rounded = solved(request(List.of(bulk), List.of(stock("A", 6)), List.of(goal("K", 5)), 1));
        check(rounded.surplus().equals(Map.of("K", 3L)) && rounded.shoppingList().equals(Map.of("A", 6L)), "3A→4K 两次留 3K");
        Plan reused = solved(request(List.of(bulk), List.of(stock("A", 3)), List.of(goal("K", 2), goal("K", 2)), 1));
        check(reused.surplus().isEmpty() && reused.shoppingList().get("A") == 3, "两项需求共享同批余料");
        // 末项的两格只能取现成材料（已达深度上限）。若第二项一见余料就用，末项会误报缺料。
        Recipe doubleBoard = simple("saw", "board", 2, List.of(new Slot(List.of("log"))));
        Recipe pair = simple("pair", "pair", 1, Collections.nCopies(2, new Slot(List.of("board"))));
        Plan saved = solved(request(List.of(doubleBoard, pair), List.of(stock("log", 2)),
                List.of(goal("board", 2), goal("pair", 1)), 1));
        check(saved.shoppingList().equals(Map.of("log", 2L)) && saved.surplus().isEmpty(),
                "可分解的需求先再做一批，保存两块余板给不可分解的后续需求");
        List<Source> sources = List.of(new Source("far", "A", 1, 10), new Source("near", "A", 1, 1));
        Plan near = solved(request(List.of(), sources, List.of(goal("A", 1)), 0));
        check(((Take) near.actions().getFirst()).source() == 1, "同物品选择近箱");
    }

    private void cyclesAndLimits() {
        Recipe first = simple("AB", "B", 1, List.of(new Slot(List.of("A"))));
        Recipe second = simple("BA", "A", 1, List.of(new Slot(List.of("B"))));
        List<Recipe> cycle = List.of(first, second);
        Request missing = request(cycle, List.of(), List.of(goal("A", 1)), 8);
        Result noSeed = SupplySolver.solve(missing, BUDGET);
        check(noSeed.status() == Status.NO_PLAN_IN_MODEL && !noSeed.blockedExamples().isEmpty(), "无种子环不能自己启动");
        check(solved(request(cycle, List.of(stock("B", 1)), List.of(goal("A", 1)), 8)).shoppingList().get("B") == 1, "有种子允许转换");
        Recipe seed = simple("seed", "B", 1, List.of(new Slot(List.of("C"))));
        List<Recipe> chain = List.of(second, seed);
        List<Source> stock = List.of(stock("C", 1));
        check(SupplySolver.solve(request(chain, stock, List.of(goal("A", 1)), 1), BUDGET).plan() == null, "深度 1 不得把 B 追到 C");
        Request full = request(chain, stock, List.of(goal("A", 1)), 2);
        check(solved(full).shoppingList().get("C") == 1, "深度 2 可用 C");
        Hint stopped = RecipeFixtures.hint(new RawNode("A", 1, "crafting_shapeless", List.of(leaf("B", 1))));
        check(SupplySolver.solve(new Request(full.catalog(), stock, List.of(new Goal("A", 1, stopped)),
                Map.of(), Set.of(), Set.of(), 2, Policy.SAVE_STOCK, Mode.RAW_ONLY), BUDGET).plan() == null,
                "原树明确停止的 B 即使有配方也不擅自继续分解");
        check(solved(new Request(full.catalog(), stock, List.of(new Goal("A", 1, stopped)),
                Map.of(), Set.of(), Set.of(), 2, Policy.SAVE_STOCK, Mode.PREPARE)).shoppingList().get("C") == 1,
                "成品求解可为提示树的空叶使用已知配方");
        check(SupplySolver.solve(new Request(full.catalog(), stock, full.goals(), Map.of(), Set.of(), Set.of("B"),
                2, Policy.SAVE_STOCK, Mode.PREPARE), BUDGET).plan() == null, "显式终点在自建图也有效");
        check(SupplySolver.solve(full, 1).status() == Status.UNKNOWN_LIMIT, "限额不是缺料证明");
        check(SupplySolver.solve(full, BUDGET, 0).status() == Status.UNKNOWN_LIMIT, "时间预算耗尽不误报缺料");
        check(SupplySolver.solve(request(List.of(), List.of(stock("A", Integer.MAX_VALUE)),
                List.of(goal("A", Integer.MAX_VALUE)), 0), BUDGET).status() == Status.UNKNOWN_LIMIT, "大订单惰性展开并限制活动帧");
        Request options = request(List.of(), List.of(new Source("far", "A", 1, 8), new Source("near", "A", 1, 1)), List.of(goal("A", 1)), 0);
        check(SupplySolver.solve(options, 3).status() == Status.FEASIBLE_LIMIT, "已找到可行解但尚未搜索完毕");
    }

    private void cancelledBeforeIndexing() {
        Request impossible = new Request(new Catalog(List.of()), List.of(), List.of(goal("A", 1)),
                Map.of("B", 1), Set.of(), Set.of(), 1, Policy.SAVE_STOCK, Mode.PREPARE);
        check(SupplySolver.solve(impossible, BUDGET, 0).status() == Status.UNKNOWN_LIMIT,
                "时间预算耗尽时不读取快照并推断无解");
        Thread.currentThread().interrupt();
        try {
            Result cancelled = SupplySolver.solve(impossible, BUDGET);
            check(cancelled.status() == Status.UNKNOWN_LIMIT && cancelled.plan() == null && cancelled.explored() == 0,
                    "中断发生在搜索前时不交付结果");
            check(Thread.currentThread().isInterrupted(), "取消不会清除调用线程的中断标记");
        } finally {
            Thread.interrupted();
        }
    }

    private void smallOracle() {
        Recipe bulk = simple("bulk", "part", 3, List.of(new Slot(List.of("raw"))));
        for (int raw = 0; raw <= 3; raw++) {
            for (int parts = 0; parts <= 4; parts++) {
                for (int needed = 1; needed <= 7; needed++) {
                    int leastParts = Integer.MAX_VALUE;
                    for (int useParts = 0; useParts <= parts; useParts++)
                        for (int crafts = 0; crafts <= raw; crafts++)
                            if (useParts + 3 * crafts >= needed) leastParts = Math.min(leastParts, useParts);
                    Request sample = new Request(new Catalog(List.of(bulk)), List.of(stock("part", parts), stock("raw", raw)),
                            List.of(goal("part", needed)), Map.of(), Set.of("part"), Set.of(), 1, Policy.SAVE_STOCK, Mode.PREPARE);
                    Result result = SupplySolver.solve(sample, BUDGET);
                    Result whole = WholeOrderSolver.solve(sample);
                    check(leastParts == Integer.MAX_VALUE ? whole.status() == Status.NO_PLAN_IN_MODEL
                                    : whole.status() == Status.BEST_IN_MODEL && whole.plan().score().protectedUnits() == leastParts,
                            "整单精确搜索与独立穷举一致");
                    if (leastParts == Integer.MAX_VALUE) check(result.status() == Status.NO_PLAN_IN_MODEL, "穷举对照：不可行");
                    else check(result.status() == Status.BEST_IN_MODEL && result.plan().score().protectedUnits() == leastParts,
                            "穷举对照：最小期初部件消耗");
                }
            }
        }
    }

    /** 13 种、1152 件。既有建筑成品、须制板的半成品，也有当前完全没有来源的黑曜石。 */
    private void constructionOrderWithShortages() {
        List<Goal> goals = new ArrayList<>();
        List<Source> stock = new ArrayList<>();
        for (String item : List.of("stone", "bricks", "glass", "sand", "gravel", "dirt", "granite", "andesite", "diorite", "tuff")) {
            String id = "minecraft:" + item;
            goals.add(goal(id, 96));
            stock.add(stock(id, 96));
        }
        String torch = "minecraft:torch";
        String obsidian = "minecraft:obsidian";
        goals.addAll(List.of(goal(BIRCH, 64), goal(torch, 64), goal(obsidian, 64)));
        stock.addAll(List.of(stock(BIRCH, 32), stock("minecraft:birch_log", 8),
                stock("minecraft:coal", 4), stock("minecraft:stick", 4)));
        List<Recipe> recipes = List.of(RecipeFixtures.read(BIRCH), RecipeFixtures.read(torch));
        Request mixed = request(recipes, stock, goals, 5);
        Delivery partial = WholeOrderSolver.planForGathering(mixed, true);
        check(partial.partial() && partial.result().status() == Status.BEST_IN_MODEL
                && partial.units().values().stream().mapToLong(Long::longValue).sum() == 1040, "已证实整单不可行，实际可交付 1040 件");
        check(partial.units().get(BIRCH) == 64 && partial.units().get(torch) == 16 && !partial.units().containsKey(obsidian),
                "已有 32 块板与 8 根原木合并；煤只够 16 个火把；不把缺失的黑曜石算作交付");
        List<Goal> delivered = partial.units().entrySet().stream().map(row -> goal(row.getKey(), Math.toIntExact(row.getValue()))).toList();
        replay(request(recipes, stock, delivered, 5), partial.result().plan());

        List<Source> enough = new ArrayList<>(stock);
        enough.addAll(List.of(stock("minecraft:coal", 12), stock("minecraft:stick", 12), stock(obsidian, 64)));
        Delivery complete = WholeOrderSolver.planForGathering(request(recipes, enough, goals, 5), true);
        check(!complete.partial() && complete.units().size() == 13, "部分成品加足量原料可以直接证实整单可行");
        replay(request(recipes, enough, goals, 5), complete.result().plan());

        List<Source> finished = goals.stream().map(goal -> stock(goal.item(), goal.units())).toList();
        Delivery direct = WholeOrderSolver.planForGathering(request(List.of(), finished, goals, 0), true);
        check(!direct.partial() && direct.result().plan().actions().stream().allMatch(Take.class::isInstance),
                "1152 件缓存成品无需任何配方或工作站");
        Delivery empty = WholeOrderSolver.planForGathering(request(recipes, List.of(), goals, 5), true);
        check(empty.result().status() == Status.NO_PLAN_IN_MODEL && empty.result().plan() == null && empty.units().isEmpty(),
                "零库存无种子，明确交回剩余采集，不发取料或制作动作");
    }

    private void distributedWarehouse() {
        List<Source> warehouse = new ArrayList<>();
        for (int box = 0; box < 64; box++) {
            warehouse.add(new Source("wood-" + box, BIRCH, 16, 2));
            warehouse.add(new Source("rock-" + box, COBBLE, 16, 2));
        }
        List<Goal> work = List.of(goal(BIRCH, 512), goal(COBBLE, 512));
        Request request = request(List.of(), warehouse, work, 0);
        Delivery delivery = WholeOrderSolver.planForGathering(request, true);
        check(delivery.result().plan().score().operations() == 128 && delivery.result().explored() < 1000,
                "128 个等价分仓取 1024 件，访问成本下界可证明 64 次开箱最优");
        replay(request, delivery.result().plan());
    }

    private void concreteWarehouse() {
        List<Recipe> recipes = new ArrayList<>();
        List<Goal> goals = new ArrayList<>();
        List<Source> stock = new ArrayList<>();
        for (String color : List.of("white", "orange", "magenta", "light_blue", "yellow", "lime",
                "pink", "gray", "light_gray", "cyan", "purple", "blue")) {
            String output = "minecraft:" + color + "_concrete_powder";
            recipes.add(RecipeFixtures.read(output));
            goals.add(goal(output, 96));
            stock.add(stock(output, 8));
            stock.add(stock("minecraft:" + color + "_dye", color.equals("cyan") ? 6 : 11));
        }
        stock.add(stock("minecraft:sand", 528));
        stock.add(stock("minecraft:gravel", 528));
        Request request = request(recipes, stock, goals, 5);
        Delivery partial = WholeOrderSolver.planForGathering(request, true);
        check(partial.partial() && partial.units().get("minecraft:cyan_concrete_powder") == 56
                        && partial.units().values().stream().mapToLong(Long::longValue).sum() == 1112,
                "12 色真实混凝土粉共需 1152 块，青色短五份染料，其余十一色可足额交付");
        List<Goal> selected = partial.units().entrySet().stream().map(e -> goal(e.getKey(), Math.toIntExact(e.getValue()))).toList();
        replay(request(recipes, stock, selected, 5), partial.result().plan());
        check(partial.result().plan().shoppingList().get("minecraft:sand") == 508
                        && partial.result().plan().shoppingList().get("minecraft:gravel") == 508,
                "127 次制作共享砂与砂砾，各消耗 508 件");
    }

    private void partialOrdersShareStock() {
        Request ready = request(List.of(RecipeFixtures.read(BIRCH)), List.of(stock(BIRCH, 4), stock("minecraft:birch_log", 1000)),
                List.of(goal(BIRCH, 4), goal("unavailable", 1)), 3);
        Delivery direct = WholeOrderSolver.planForGathering(ready, true);
        Delivery conserve = WholeOrderSolver.planForGathering(ready, false);
        check(direct.partial() && direct.result().plan().shoppingList().equals(Map.of(BIRCH, 4L))
                        && conserve.result().plan().shoppingList().equals(Map.of("minecraft:birch_log", 1L)),
                "可交付子单全有成品时仍尊重玩家的现货优先开关");
        Recipe first = simple("one", "one", 1, List.of(new Slot(List.of("shared"))));
        Recipe second = simple("two", "two", 1, List.of(new Slot(List.of("shared"))));
        List<Goal> goals = List.of(goal("one", 4), goal("two", 4));
        for (List<Goal> order : List.of(goals, goals.reversed())) {
            Request request = new Request(new Catalog(List.of(first, second)), List.of(stock("shared", 7)), order,
                    Map.of("shared", 2), Set.of(), Set.of(), 1, Policy.SAVE_STOCK, Mode.PREPARE);
            Delivery delivery = WholeOrderSolver.planForGathering(request, true);
            check(delivery.partial() && delivery.units().equals(Map.of(order.getFirst().item(), 4L, order.getLast().item(), 1L)),
                    "共享原料冲突时为先处理的条目保留交付数量，再二分缩小另一项的数量");
            check(delivery.result().plan().shoppingList().equals(Map.of("shared", 5L)), "部分交付同样保留两份硬下限");
        }
        Thread.currentThread().interrupt();
        try {
            Delivery cancelled = WholeOrderSolver.planForGathering(request(List.of(first, second),
                    List.of(stock("shared", 3)), goals, 3), true);
            check(cancelled.result().status() == Status.UNKNOWN_LIMIT && cancelled.result().plan() == null,
                    "取消搜索不能误报缺料或交付部分动作");
        } finally {
            Thread.interrupted();
        }
    }

    private void wholeOrderWoodwork() {
        String door = "minecraft:oak_door";
        String chest = "minecraft:chest";
        String button = "minecraft:birch_button";
        List<Recipe> recipes = List.of(RecipeFixtures.read(door), RecipeFixtures.read(chest), RecipeFixtures.read(button));
        List<Goal> targets = List.of(goal(door, 18), goal(chest, 2), goal(button, 1));
        List<Source> stock = List.of(stock(OAK, 36), stock(BIRCH, 17));
        for (List<Goal> order : List.of(targets, targets.reversed())) {
            Plan plan = exact(request(recipes, stock, order, 1));
            check(plan.surplus().isEmpty() && plan.shoppingList().equals(Map.of(OAK, 36L, BIRCH, 17L)), "21 件木作整单交付");
            Make chests = plan.actions().stream().filter(Make.class::isInstance).map(Make.class::cast)
                    .filter(make -> make.recipe().output().equals(chest)).findFirst().orElseThrow();
            check(chests.operations() == 2 && chests.grid().stream().filter(id -> !id.isEmpty()).allMatch(BIRCH::equals),
                    "两次箱子一批，用白桦板，将 36 块橡木板全部留给门");
        }
        check(WholeOrderSolver.solve(request(recipes, List.of(stock(OAK, 35), stock(BIRCH, 18)), targets, 1))
                .status() == Status.NO_PLAN_IN_MODEL, "总板数够，橡木专用格少一块仍无解");
        Plan cached = exact(request(recipes, List.of(stock(OAK, 36), stock(BIRCH, 9), stock(chest, 1)), targets, 1));
        check(cached.shoppingList().get(chest) == 1, "缓存成品和制作共同满足同一个清单条目");
    }

    private void wholeOrderSticks() {
        String sticks = "minecraft:stick";
        String torch = "minecraft:torch";
        String rail = "minecraft:rail";
        String ladder = "minecraft:ladder";
        List<Recipe> recipes = List.of(RecipeFixtures.read(sticks), RecipeFixtures.read(rail),
                RecipeFixtures.read(torch), RecipeFixtures.read(ladder));
        List<Source> stock = List.of(stock(OAK, 10), stock("minecraft:coal", 2), stock(IRON, 6));
        List<Goal> targets = List.of(goal(rail, 16), goal(torch, 8), goal(ladder, 6));
        Plan plan = exact(request(recipes, stock, targets, 2));
        check(plan.surplus().equals(Map.of(sticks, 3L)) && plan.shoppingList().get(OAK) == 10,
                "铁轨、火把、梯子共需 17 根木棍，只制作五次并共享三根余棍");
        check(plan.actions().stream().anyMatch(action -> action instanceof Make make
                && make.recipe().output().equals(sticks) && make.operations() == 5), "同一格位的制棍次数随交付计划保留");
    }

    private void wholeOrderSourcesAndDepth() {
        List<Source> boxes = List.of(new Source("near", IRON, 4, 2), new Source("shared", IRON, 4, 3),
                new Source("shared", DUST, 4, 3));
        Plan shared = exact(request(List.of(), boxes, List.of(goal(IRON, 4), goal(DUST, 4)), 0));
        check(shared.score().operations() == 3 && shared.actions().stream().noneMatch(action ->
                action instanceof Take take && take.source() == 0), "两个无配方成品共享一次较远箱访问");
        Request bulk = request(List.of(RecipeFixtures.read(OAK)), List.of(stock(LOG, 256)), List.of(goal(OAK, 1024)), 5);
        Plan large = exact(bulk);
        check(large.actions().size() == 2 && ((Make) large.actions().getLast()).operations() == 256,
                "1024 块板只需一项取货和一项制作，超出旧动作数和活动帧上限");
        SupplySchedule schedule = new SupplySchedule(large);
        List<Action> batches = new ArrayList<>();
        check(schedule.preview(64).actions().size() == 2, "先试排整组，未提交的试排不占用原木或余料");
        while (!schedule.isEmpty()) {
            SupplySchedule.Slice slice = schedule.preview(31);
            batches.addAll(slice.actions());
            slice.commit();
        }
        replay(bulk, new Plan(batches, large.surplus(), large.score(), large.mode()));
        check(batches.stream().filter(Take.class::isInstance).map(Take.class::cast).mapToInt(Take::count).max().orElseThrow() == 8,
                "分段以 7 或 8 根原木备料，复用前一批余板，累计仍只用 256 根");
        Recipe a = simple("a", "A", 2, List.of(new Slot(List.of("B"))));
        Recipe b = simple("b", "B", 1, List.of(new Slot(List.of("A"))));
        List<Goal> goals = List.of(goal("A", 3), goal("B", 1));
        check(WholeOrderSolver.solve(request(List.of(a, b), List.of(), goals, 8)).status() == Status.NO_PLAN_IN_MODEL,
                "无种子的循环不能凭空产生全单库存");
        exact(request(List.of(a, b), List.of(stock("A", 2)), goals, 4));
        check(WholeOrderSolver.solve(request(List.of(a, b), List.of(stock("A", 2)), goals, 2)).status() == Status.NO_PLAN_IN_MODEL,
                "有种子的固定转换仍受实际制作层数约束");
        Thread.currentThread().interrupt();
        try {
            check(WholeOrderSolver.solve(bulk).status() == Status.UNKNOWN_LIMIT, "取消整单搜索不会提交候选");
        } finally {
            Thread.interrupted();
        }
    }

    /** 正向枚举两箱采购量和每种实际制作次数，不使用求解器的倒推、上界或布局分配。 */
    private void wholeOrderOracle() {
        List<Recipe> recipes = List.of(simple("mill", "board", 2, List.of(new Slot(List.of("log")))),
                simple("light", "light", 2, List.of(new Slot(List.of("board", "coal")))));
        for (int logs = 0; logs <= 2; logs++) {
            for (int boards = 1; boards <= 3; boards++) {
                for (int coal = 0; coal <= 2; coal++) {
                    List<Source> stock = List.of(new Source("near", "board", boards, 2),
                            new Source("far", "log", logs, 5), new Source("far", "coal", coal, 5));
                    for (Policy policy : Policy.values()) {
                        Request sample = new Request(new Catalog(recipes), stock, List.of(goal("board", 2), goal("light", 3)),
                                Map.of("board", 1), Set.of("board"), Set.of(), 2, policy, Mode.PREPARE);
                        List<Long> best = null;
                        for (int log = 0; log <= logs; log++) {
                            for (int board = 0; board < boards; board++) {
                                for (int fuel = 0; fuel <= coal; fuel++) {
                                    for (int mill = 0; mill <= log; mill++) {
                                        for (int burn = 0; burn <= 2; burn++) {
                                            if (board + 2 * mill - burn < 2 || 2 - burn > fuel) continue;
                                            long cost = mill + 2 + (board > 0 ? 2 : 0) + (log + fuel > 0 ? 5 : 0);
                                            long pressure = price(board, boards - 1) + price(fuel, coal) + price(log, logs);
                                            List<Long> score = objective(new Score(board, pressure, cost), policy);
                                            if (best == null || Arrays.compare(
                                                    score.toArray(Long[]::new), best.toArray(Long[]::new)) < 0) best = score;
                                        }
                                    }
                                }
                            }
                        }
                        Result solved = WholeOrderSolver.solve(sample);
                        if (best == null) check(solved.status() == Status.NO_PLAN_IN_MODEL, "正向全枚举判定不可行");
                        else {
                            check(solved.status() == Status.BEST_IN_MODEL && objective(solved.plan().score(), policy).equals(best),
                                    "正向全枚举对照整单的完整三项成本、硬保留及两箱共享访问");
                            replay(sample, solved.plan());
                        }
                    }
                }
            }
        }
    }

    private static long price(int used, int available) {
        return used == 0 ? 0 : (long) used * Math.ceilDiv(1_000_000, available);
    }

    private static List<Long> objective(Score score, Policy policy) {
        return policy == Policy.FEWEST_OPERATIONS ? List.of(score.operations(), score.protectedUnits(), score.pressure())
                : List.of(score.protectedUnits(), score.pressure(), score.operations());
    }

    private Plan exact(Request request) {
        Result result = WholeOrderSolver.solve(request);
        check(result.status() == Status.BEST_IN_MODEL, "整单应证优: " + request.goals());
        replay(request, result.plan());
        return result.plan();
    }

    private Plan solved(Request request) {
        Result result = SupplySolver.solve(request, BUDGET);
        check(result.status() == Status.BEST_IN_MODEL, "应完成搜索: " + result.status() + " " + request.goals());
        replay(request, result.plan());
        return result.plan();
    }

    private void replay(Request request, Plan plan) {
        Map<String, Long> inventory = new HashMap<>();
        int[] capacity = request.stock().stream().mapToInt(Source::count).toArray();
        for (Action action : plan.actions()) {
            if (action instanceof Take take) {
                check((capacity[take.source()] -= take.count()) >= 0 && request.stock().get(take.source()).item().equals(take.item()), "不重复兑现货源");
                inventory.merge(take.item(), (long) take.count(), Long::sum);
            } else if (action instanceof Make make) {
                for (int index = 0; index < 9; index++) {
                    String item = make.grid().get(index);
                    Slot slot = make.recipe().slots().get(index);
                    if (item.isEmpty()) check(slot.choices().isEmpty(), "保留图案空格");
                    else check(slot.choices().contains(item) && inventory.merge(item, -(long) make.operations(), Long::sum) >= 0, "合法变种且先有料再制作");
                }
                inventory.merge(make.recipe().output(), (long) make.recipe().yield() * make.operations(), Long::sum);
            }
        }
        for (Goal goal : request.goals()) check(inventory.merge(goal.item(), -(long) goal.units(), Long::sum) >= 0, "满足每项交付");
        inventory.values().removeIf(amount -> amount == 0);
        check(inventory.equals(plan.surplus()), "模拟回放与余料账本一致");
    }

    private static RawNode leaf(String item, int count) { return new RawNode(item, count, "base", List.of()); }
    private static Source stock(String item, int units) { return new Source("box", item, units, 1); }
    private static Goal goal(String item, int units) { return new Goal(item, units, null); }
    private static Request request(List<Recipe> recipes, List<Source> stock, List<Goal> goals, int depth) {
        return new Request(new Catalog(recipes), stock, goals, Map.of(), Set.of(), Set.of(), depth, Policy.SAVE_STOCK, Mode.PREPARE);
    }
    private static Recipe simple(String id, String item, int yield, List<Slot> occupied) {
        List<Slot> slots = new ArrayList<>(occupied);
        while (slots.size() < 9) slots.add(new Slot(List.of()));
        return new Recipe(id, "crafting_shapeless", item, yield, slots);
    }
    private void check(boolean valid, String scenario) {
        if (!valid) throw new AssertionError(scenario);
        checks++;
    }
}
