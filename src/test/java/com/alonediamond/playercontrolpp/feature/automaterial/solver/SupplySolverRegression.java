package com.alonediamond.playercontrolpp.feature.automaterial.solver;

import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Action;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Catalog;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Goal;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Hint;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Make;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Mode;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Plan;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Policy;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Recipe;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Request;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Result;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Slot;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Source;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Status;
import com.alonediamond.playercontrolpp.feature.automaterial.solver.SupplyModel.Take;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration.RawNode;

import java.util.ArrayList;
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
        survivalPiston();
        twoFences();
        mineralRoutes();
        sharedAndRounded();
        cyclesAndLimits();
        smallOracle();
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
        check(SupplySolver.solve(new Request(full.catalog(), stock, full.goals(), Map.of(), Set.of(), Set.of("B"),
                2, Policy.SAVE_STOCK, Mode.PREPARE), BUDGET).plan() == null, "显式终点在自建图也有效");
        check(SupplySolver.solve(full, 1).status() == Status.UNKNOWN_LIMIT, "限额不是缺料证明");
        check(SupplySolver.solve(full, BUDGET, 0).status() == Status.UNKNOWN_LIMIT, "时间预算耗尽不误报缺料");
        check(SupplySolver.solve(request(List.of(), List.of(stock("A", Integer.MAX_VALUE)),
                List.of(goal("A", Integer.MAX_VALUE)), 0), BUDGET).status() == Status.UNKNOWN_LIMIT, "大订单惰性展开并限制活动帧");
        Request options = request(List.of(), List.of(new Source("far", "A", 1, 8), new Source("near", "A", 1, 1)), List.of(goal("A", 1)), 0);
        check(SupplySolver.solve(options, 3).status() == Status.FEASIBLE_LIMIT, "已找到可行解但尚未搜索完毕");
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
                    if (leastParts == Integer.MAX_VALUE) check(result.status() == Status.NO_PLAN_IN_MODEL, "穷举对照：不可行");
                    else check(result.status() == Status.BEST_IN_MODEL && result.plan().score().protectedUnits() == leastParts,
                            "穷举对照：最小期初部件消耗");
                }
            }
        }
    }

    private Plan solved(Request request) {
        Result result = SupplySolver.solve(request, BUDGET);
        check(result.status() == Status.BEST_IN_MODEL, "应完成搜索: " + result.status() + " " + request.goals());
        Plan plan = result.plan();
        Map<String, Long> inventory = new HashMap<>();
        int[] capacity = request.stock().stream().mapToInt(Source::count).toArray();
        for (Action action : plan.actions()) {
            if (action instanceof Take take) {
                check(--capacity[take.source()] >= 0 && request.stock().get(take.source()).item().equals(take.item()), "不重复兑现货源");
                inventory.merge(take.item(), 1L, Long::sum);
            } else if (action instanceof Make make) {
                for (int index = 0; index < 9; index++) {
                    String item = make.grid().get(index);
                    Slot slot = make.recipe().slots().get(index);
                    if (item.isEmpty()) check(slot.choices().isEmpty(), "保留图案空格");
                    else check(slot.choices().contains(item) && inventory.merge(item, -1L, Long::sum) >= 0, "合法变种且先有料再制作");
                }
                inventory.merge(make.recipe().output(), (long) make.recipe().yield(), Long::sum);
            }
        }
        for (Goal goal : request.goals()) check(inventory.merge(goal.item(), -(long) goal.units(), Long::sum) >= 0, "满足每项交付");
        inventory.values().removeIf(amount -> amount == 0);
        check(inventory.equals(plan.surplus()), "模拟回放与余料账本一致");
        return plan;
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
