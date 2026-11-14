package com.alonediamond.playercontrolpp.feature.automaterial.manual;

import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialItemEntry;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration.RawNode;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** 临时原料树、两种备货口径和批次保护的无窗口回归。 */
public final class ManualRawMaterialPlanRegression {
    private static int checks;

    private ManualRawMaterialPlanRegression() {}

    public static int run() {
        checks = 0;
        RawNode planks = node("minecraft:oak_planks", 4, "crafting_shaped",
                leaf("minecraft:oak_log", 1), leaf("minecraft:stone", 2));
        RawNode sticks = node("minecraft:stick", 1, "crafting_shapeless",
                leaf("minecraft:oak_log", 3));
        ManualRawMaterialPlan plan = ManualRawMaterialPlan.fromTrees(List.of(planks, sticks));
        check(amount(plan.materials(), Items.OAK_LOG) == 4
                && amount(plan.materials(), Items.STONE) == 2, "多根树叶子合并并累计重复原料");
        check(!plan.unsupportedRecipe(), "工作台过程可执行");
        check(ManualRawMaterialPlan.fromTrees(List.of(node("minecraft:glass", 1, "smelting",
                leaf("minecraft:sand", 1)))).unsupportedRecipe(), "熔炼过程标记为不支持");
        check(!ManualRawMaterialPlan.fromTrees(List.of(node("minecraft:stone_slab", 1, "stonecutting",
                leaf("minecraft:stone", 1)))).unsupportedRecipe(), "切石过程可执行");
        expectInvalid(() -> ManualRawMaterialPlan.fromTrees(List.of(
                leaf("minecraft:stone", Integer.MAX_VALUE), leaf("minecraft:stone", 1))), "重复叶子溢出拒绝");
        expectInvalid(() -> ManualRawMaterialPlan.fromTrees(List.of(leaf("minecraft:stone", 0))),
                "无效数量拒绝");
        expectInvalid(() -> plan.rawOnlyTargets(
                Map.of(Items.OAK_LOG, Integer.MAX_VALUE), item -> Integer.MAX_VALUE),
                "原料目标加基线溢出拒绝");

        ManualMaterialBatch batch = new ManualMaterialBatch("planks");
        batch.setAmount("minecraft:oak_planks", 4);
        List<MaterialItemEntry> finalTargets = List.of(entry(Items.OAK_PLANKS, 14), entry(Items.STONE, 8));
        Map<Item, Integer> reserves = ManualRawMaterialPlan.reserves(batch, finalTargets,
                Map.of(Items.STONE, 8, Items.OAK_LOG, 3));
        check(reserves.get(Items.OAK_PLANKS) == 10 && reserves.get(Items.STONE) == 8
                && reserves.get(Items.OAK_LOG) == 3, "成品既有基线和前批成果均受保护");
        Map<Item, Integer> held = new IdentityHashMap<>();
        held.put(Items.OAK_LOG, 9);
        held.put(Items.STONE, 11);
        check(plan.rootFor(Items.OAK_PLANKS) == planks && plan.rootFor(Items.PISTON) == null,
                "逐组合成复用对应成品的临时配方树，不把树叶表当成成品目标");
        List<MaterialItemEntry> rawOnlyTargets = plan.rawOnlyTargets(
                reserves, item -> held.getOrDefault(item, 0));
        check(amount(rawOnlyTargets, Items.OAK_LOG) == 13 && amount(rawOnlyTargets, Items.STONE) == 13,
                "仅备原料在当前持有量上追加新目标");
        held.put(Items.OAK_LOG, 1);
        check(amount(plan.rawOnlyTargets(reserves,
                item -> held.getOrDefault(item, 0)), Items.OAK_LOG) == 7,
                "当前持有低于前批保护量时，仍须在保护量之外追加原料");

        queueTargetsStaySeparate();
        return checks;
    }

    private static void queueTargetsStaySeparate() {
        Path directory;
        try { directory = Files.createTempDirectory("playercontrolpp-raw-plan"); }
        catch (IOException e) { throw new AssertionError("cannot create regression directory", e); }
        try {
            ManualMaterialQueue queue = new ManualMaterialQueue(directory.resolve("queue.json"));
            ManualMaterialBatch first = queue.addBatch("first");
            first.setAmount("minecraft:oak_planks", 4);
            ManualMaterialBatch second = queue.addBatch("second");
            second.setAmount("minecraft:stone", 2);
            Map<Item, Integer> held = new IdentityHashMap<>();
            held.put(Items.OAK_PLANKS, 10);
            held.put(Items.OAK_LOG, 5);
            check(queue.start(item -> held.getOrDefault(item, 0))
                    && amount(queue.activeBatch().targets(), Items.OAK_PLANKS) == 14,
                    "成品目标使用启动时持有量建立基线");
            ManualRawMaterialPlan plan = ManualRawMaterialPlan.fromTrees(List.of(
                    node("minecraft:oak_planks", 4, "crafting_shaped", leaf("minecraft:oak_log", 1))));
            Map<Item, Integer> reserves = ManualRawMaterialPlan.reserves(first,
                    queue.activeBatch().targets(), queue.completedTargets());
            queue.replaceActiveTargets(plan.rawOnlyTargets(reserves,
                    item -> held.getOrDefault(item, 0)));
            check(amount(queue.activeBatch().targets(), Items.OAK_LOG) == 6
                    && first.amount("minecraft:oak_planks") == 4,
                    "仅备原料只替换运行目标，不改批次定义");
            held.put(Items.OAK_LOG, 6);
            check(queue.completeActiveBatch(item -> held.getOrDefault(item, 0))
                    && queue.activeIndex() == 1
                    && amount(queue.activeBatch().targets(), Items.OAK_LOG) == 6
                    && amount(queue.activeBatch().targets(), Items.OAK_PLANKS) == 10
                    && amount(queue.activeBatch().targets(), Items.STONE) == 2,
                    "下一批保留前批原料成果和成品既有基线");
        } finally {
            try { Files.deleteIfExists(directory.resolve("queue.json")); Files.deleteIfExists(directory); }
            catch (IOException e) { throw new AssertionError("cannot remove regression directory", e); }
        }
    }

    private static RawNode node(String id, int count, String recipeType, RawNode... children) {
        return new RawNode(id, count, recipeType, List.of(children));
    }

    private static RawNode leaf(String id, int count) {
        return node(id, count, "base");
    }

    private static MaterialItemEntry entry(Item item, int count) {
        return new MaterialItemEntry(item, count, item.getDefaultMaxStackSize());
    }

    private static int amount(List<MaterialItemEntry> entries, Item item) {
        return entries.stream().filter(entry -> entry.item == item)
                .mapToInt(entry -> entry.neededCount).findFirst().orElse(0);
    }

    private static void expectInvalid(Runnable action, String scenario) {
        try { action.run(); }
        catch (IllegalArgumentException | ArithmeticException e) { check(true, scenario); return; }
        check(false, scenario);
    }

    private static void check(boolean ok, String scenario) {
        if (!ok) throw new AssertionError(scenario);
        checks++;
    }
}
