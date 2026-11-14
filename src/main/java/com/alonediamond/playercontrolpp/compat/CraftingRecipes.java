package com.alonediamond.playercontrolpp.compat;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import java.util.ArrayList;
import java.util.List;

//#if MC >= 12102
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
//#else
//$$ import net.minecraft.world.item.crafting.CraftingRecipe;
//$$ import net.minecraft.world.item.crafting.Ingredient;
//$$ import net.minecraft.world.item.crafting.ShapedRecipe;
//#endif

/** 把各版本的客户端配方书统一成摆料计划；不负责工作站选择和合成流程。 */
public final class CraftingRecipes {
    private static final int CRAFT_GRID_WIDTH = 3;
    private CraftingRecipes() {}

    /**
     * 一次合成用的配方计划：把各版本的配方类型统一成"每个合成格能放哪些物品"。
     *
     * <p>摆料逻辑（手动点击与"能做几套"的计算）只认这份数据，因此与 MC 版本无关。
     */
    public static final class Plan {
        /** 版本相关的配方句柄：1.21.2+ 是 {@code RecipeDisplayEntry}，1.21.1 是 {@code RecipeHolder}。 */
        private final Object handle;
        /** 每个合成格可接受的物品；空列表 = 图案空位。 */
        private final List<List<ItemStack>> grid;
        /** shaped 图案宽度；shapeless / 未知按 3 处理（位置不影响匹配）。 */
        private final int width;
        private final int outputCount;

        public Plan(Object handle, List<List<ItemStack>> grid, int width, int outputCount) {
            this.handle = handle;
            this.grid = grid;
            this.width = Math.max(1, width);
            this.outputCount = Math.max(1, outputCount);
        }

        public int width() { return width; }
        public int outputCount() { return outputCount; }
        /** 固定本批每格的变种；摆料和采集必须使用同一份选择。 */
        public Plan select(List<List<ItemStack>> selected) {
            return new Plan(handle, List.copyOf(selected), width, outputCount);
        }
        public List<List<ItemStack>> ingredients() { return grid; }
        public List<ItemStack> ingredientsAt(int index) { return grid.get(index); }

        public int slotCount() { return this.grid.size(); }

        public boolean isEmptySlot(int index) { return this.grid.get(index).isEmpty(); }
    }

    /**
     * 在客户端配方书里找"能在工作台做出 {@code item}"的那一条。
     *
     * <p>1.21.2+ 用配方展示条目（{@code RecipeDisplayEntry}）；1.21.1 没有那套 API，
     * 改用旧的 {@code RecipeHolder + CraftingRecipe.getIngredients()}。两者都只认
     * shaped / shapeless，避免把熔炉、切石机的条目当成工作台配方。
     */
    public static Plan find(Minecraft mc, Item item) {
        List<Plan> plans = findAll(mc, item);
        return plans.isEmpty() ? null : plans.get(0);
    }

    private static Object indexedWorld;
    private static Object indexedCollections;
    private static long indexedTick;
    private static java.util.Map<Item, List<Plan>> indexedPlans = java.util.Map.of();

    public static void clearCache() {
        indexedWorld = null;
        indexedCollections = null;
        indexedPlans = java.util.Map.of();
    }

    public static List<Plan> findAll(Minecraft mc, Item item) {
        if (mc.player == null || mc.level == null) return List.of();
        var collections = mc.player.getRecipeBook().getCollections();
        long tick = mc.level.getGameTime();
        if (indexedWorld != mc.level || indexedCollections != collections || tick < indexedTick || tick - indexedTick > 100) {
            java.util.Map<Item, List<Plan>> plans = new java.util.HashMap<>();
            try {
                //#if MC >= 12102
                ContextMap contextMap = SlotDisplayContext.fromLevel(mc.level);
                for (RecipeCollection collection : collections) {
                    for (RecipeDisplayEntry entry : collection.getRecipes()) {
                        RecipeDisplay display = entry.display();
                        if (!(display instanceof ShapedCraftingRecipeDisplay)
                                && !(display instanceof ShapelessCraftingRecipeDisplay)) continue;
                        List<ItemStack> results = entry.resultItems(contextMap);
                        if (results.isEmpty()) continue;
                        Plan plan = planFromDisplay(entry, contextMap);
                        if (plan != null) plans.computeIfAbsent(results.get(0).getItem(), ignored -> new ArrayList<>()).add(plan);
                    }
                }
                //#else
                //$$ for (RecipeCollection collection : collections) {
                //$$     for (RecipeHolder<?> holder : collection.getRecipes()) {
                //$$         if (!(holder.value() instanceof CraftingRecipe crafting)) continue;
                //$$         List<Ingredient> ingredients = crafting.getIngredients();
                //$$         if (ingredients.isEmpty()) continue;
                //$$         List<List<ItemStack>> grid = new ArrayList<>(ingredients.size());
                //$$         for (Ingredient ingredient : ingredients) grid.add(List.of(ingredient.getItems()));
                //$$         int width = crafting instanceof ShapedRecipe shaped ? shaped.getWidth() : CRAFT_GRID_WIDTH;
                //$$         ItemStack result = crafting.getResultItem(mc.level.registryAccess());
                //$$         plans.computeIfAbsent(result.getItem(), ignored -> new ArrayList<>())
                //$$                 .add(new Plan(holder, grid, width, result.getCount()));
                //$$     }
                //$$ }
                //#endif
            } catch (Throwable error) {
                Playercontrolpp.LOGGER.debug("Unable to index crafting recipes", error);
            }
            plans.replaceAll((key, value) -> List.copyOf(value));
            indexedPlans = java.util.Map.copyOf(plans);
            indexedWorld = mc.level;
            indexedCollections = collections;
            indexedTick = tick;
        }
        // 只缓存配方定义；库存与缺口仍由每次选择即时读取。
        return indexedPlans.getOrDefault(item, List.of());
    }
    //#if MC >= 12102
    /** 把配方展示条目转成"每个合成格能放哪些物品"。 */
    private static Plan planFromDisplay(RecipeDisplayEntry entry, ContextMap contextMap) {
        RecipeDisplay display = entry.display();
        List<SlotDisplay> layout;
        int width;
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            layout = shaped.ingredients();
            width = Math.max(1, shaped.width());
        } else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            layout = shapeless.ingredients();
            width = CRAFT_GRID_WIDTH;
        } else {
            return null;
        }

        List<List<ItemStack>> grid = new ArrayList<>(layout.size());
        for (SlotDisplay slot : layout) {
            grid.add(slot.resolveForStacks(contextMap));
        }
        List<ItemStack> results = entry.resultItems(contextMap);
        return new Plan(entry, grid, width, results.isEmpty() ? 1 : results.get(0).getCount());
    }

    /** 让服务端按配方把材料摆进格子（1.21.2+ 用配方展示 id）。 */
    public static void place(Minecraft mc, AbstractContainerMenu menu, Plan plan) {
        if (!(plan.handle instanceof RecipeDisplayEntry entry)) return;
        mc.gameMode.handlePlaceRecipe(menu.containerId, entry.id(), true);
    }

    /** @return 切石机菜单里产出 {@code item} 的那一项的按钮索引；没有则 -1。 */
    public static int stonecutterIndex(Minecraft mc, AbstractContainerMenu menu, Item item) {
        if (!(menu instanceof StonecutterMenu stonecutter)) return -1;
        ContextMap contextMap = SlotDisplayContext.fromLevel(mc.level);
        List<SelectableRecipe.SingleInputEntry<StonecutterRecipe>> entries =
                stonecutter.getVisibleRecipes().entries();
        for (int i = 0; i < entries.size(); i++) {
            ItemStack option = entries.get(i).recipe().optionDisplay().resolveForFirstStack(contextMap);
            if (ItemUtil.is(option, item)) return i;
        }
        return -1;
    }
    //#else
    //$$ /** 让服务端按配方把材料摆进格子（1.21.1 直接传 RecipeHolder）。 */
    //$$ public static void place(Minecraft mc, AbstractContainerMenu menu, Plan plan) {
    //$$     if (!(plan.handle instanceof RecipeHolder<?> holder)) return;
    //$$     mc.gameMode.handlePlaceRecipe(menu.containerId, holder, true);
    //$$ }
    //$$
    //$$ /** @return 切石机菜单里产出 {@code item} 的那一项的按钮索引；没有则 -1。 */
    //$$ public static int stonecutterIndex(Minecraft mc, AbstractContainerMenu menu, Item item) {
    //$$     if (!(menu instanceof StonecutterMenu stonecutter)) return -1;
    //$$     List<RecipeHolder<StonecutterRecipe>> recipes = stonecutter.getRecipes();
    //$$     for (int i = 0; i < recipes.size(); i++) {
    //$$         ItemStack option = recipes.get(i).value().getResultItem(mc.level.registryAccess());
    //$$         if (ItemUtil.is(option, item)) return i;
    //$$     }
    //$$     return -1;
    //$$ }
    //#endif

    public static int visibleStonecutterRecipes(AbstractContainerMenu menu) {
        if (!(menu instanceof StonecutterMenu stonecutter)) return -1;
        //#if MC >= 12102
        return stonecutter.getNumberOfVisibleRecipes();
        //#else
        //$$ return stonecutter.getRecipes().size();
        //#endif
    }

}
