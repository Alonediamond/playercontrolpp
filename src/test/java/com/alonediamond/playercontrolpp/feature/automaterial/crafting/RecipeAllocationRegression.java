package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import com.alonediamond.playercontrolpp.compat.CraftingRecipes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** 用真实物品验证配方格分配与目标总量口径，不需要游戏窗口。 */
public final class RecipeAllocationRegression {
    private static int checks;

    public static int run() {
        checks = 0;
        nestedPiston();
        outputRoundingAndRepeatedSlots();
        mixWoodPlanks();
        unsupportedAndBounds();
        return checks;
    }

    private static void nestedPiston() {
        CraftingRecipes.Plan sticky = plan(1, slot(Items.PISTON), slot(Items.SLIME_BALL));
        CraftingRecipes.Plan piston = plan(1,
                slot(Items.OAK_PLANKS), slot(Items.OAK_PLANKS), slot(Items.OAK_PLANKS),
                slot(Items.COBBLESTONE), slot(Items.IRON_INGOT), slot(Items.COBBLESTONE),
                slot(Items.COBBLESTONE), slot(Items.REDSTONE), slot(Items.COBBLESTONE));
        RecipeAllocation outer = RecipeAllocation.choose(List.of(sticky), 2, (item, count) -> count);
        check(outer != null && outer.ingredients().get(Items.PISTON) == 2,
                "粘性活塞的活塞输入按两次合成保留");
        check(outer.ingredients().get(Items.SLIME_BALL) == 2,
                "嵌套配方外层仍保留另一原料");
        RecipeAllocation inner = RecipeAllocation.choose(List.of(piston), outer.ingredients().get(Items.PISTON),
                (item, count) -> count);
        check(inner != null && inner.ingredients().get(Items.OAK_PLANKS) == 6,
                "活塞配方内层三格木板累计六件");
        check(inner.ingredients().get(Items.COBBLESTONE) == 8,
                "活塞配方内层四格圆石累计八件");
        check(RecipeAllocation.target(3, inner.ingredients().get(Items.COBBLESTONE)) == 11,
                "已有清单预留与嵌套消耗合成目标总量");
    }

    private static void outputRoundingAndRepeatedSlots() {
        CraftingRecipes.Plan four = plan(4, slot(Items.OAK_LOG));
        RecipeAllocation allocation = RecipeAllocation.choose(List.of(four), 5, (item, count) -> count);
        check(allocation != null && allocation.crafts() == 2,
                "每次产四件时需求五件只合成两次");
        check(allocation.ingredients().get(Items.OAK_LOG) == 2,
                "输入数量按合成次数而不是产物件数计算");
        check(RecipeAllocation.craftsFor(8, 4) == 2 && RecipeAllocation.craftsFor(9, 4) == 3,
                "整除及超出一件时向上取整");
        CraftingRecipes.Plan repeated = plan(1,
                slot(Items.COBBLESTONE), slot(Items.COBBLESTONE), slot(Items.COBBLESTONE));
        RecipeAllocation repeatedAllocation = RecipeAllocation.choose(List.of(repeated), 4,
                (item, count) -> count);
        check(repeatedAllocation != null && repeatedAllocation.ingredients().get(Items.COBBLESTONE) == 12,
                "相同物品占三格时累计全部格的用量");
    }

    private static void mixWoodPlanks() {
        CraftingRecipes.Plan mixed = plan(1,
                slot(Items.OAK_PLANKS, Items.SPRUCE_PLANKS),
                slot(Items.OAK_PLANKS, Items.SPRUCE_PLANKS));
        RecipeAllocation allocation = RecipeAllocation.choose(List.of(mixed), 1,
                (item, count) -> count > 1 ? 100 : 0);
        check(allocation != null, "合法木板候选可形成配方");
        check(allocation.ingredients().getOrDefault(Items.OAK_PLANKS, 0) == 1
                        && allocation.ingredients().getOrDefault(Items.SPRUCE_PLANKS, 0) == 1,
                "每个配方格独立选木板，可混用不同合法种类");
        check(allocation.recipe().ingredientsAt(0).size() == 1
                        && allocation.recipe().ingredientsAt(1).size() == 1,
                "采集方案与摆料方案固定同一格选择");
    }

    private static void unsupportedAndBounds() {
        check(RecipeAllocation.choose(List.of(), 1, (item, count) -> 0) == null,
                "没有支持的配方返回空方案");
        check(RecipeAllocation.choose(List.of(plan(1, List.of())), 1, (item, count) -> 0) == null,
                "没有任何输入材料的候选不构成可执行方案");
        RecipeAllocation large = RecipeAllocation.choose(List.of(plan(1, slot(Items.ENDER_PEARL))), 64,
                (item, count) -> count);
        check(large != null && large.ingredients().get(Items.ENDER_PEARL) == 64,
                "整批计划保留六十四个输入，摆料层可按十六堆叠上限分次执行");
        check(RecipeAllocation.target(Integer.MAX_VALUE, 100) == Integer.MAX_VALUE,
                "预留量加消耗使用饱和总量");
        check(RecipeAllocation.craftsFor(Integer.MAX_VALUE, 1) == Integer.MAX_VALUE,
                "最大产物需求不发生整数回绕");
    }

    @SafeVarargs
    private static CraftingRecipes.Plan plan(int outputCount, List<ItemStack>... slots) {
        return new CraftingRecipes.Plan(null, java.util.Arrays.asList(slots), 3, outputCount);
    }

    private static List<ItemStack> slot(Item... options) {
        return java.util.Arrays.stream(options).map(ItemStack::new).toList();
    }

    private static void check(boolean ok, String scenario) {
        if (!ok) throw new AssertionError(scenario);
        checks++;
    }
}
