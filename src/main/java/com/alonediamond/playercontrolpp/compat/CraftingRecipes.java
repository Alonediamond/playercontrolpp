package com.alonediamond.playercontrolpp.compat;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.util.ItemUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

//#if MC >= 12102
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
//#else
//$$ import net.minecraft.core.HolderLookup;
//$$ import net.minecraft.world.item.crafting.CraftingRecipe;
//$$ import net.minecraft.world.item.crafting.Ingredient;
//$$ import net.minecraft.world.item.crafting.ShapedRecipe;
//$$ import net.minecraft.world.item.crafting.ShapelessRecipe;
//#endif

/** 把各版本的客户端配方书统一成摆料计划；不负责工作站选择和合成流程。 */
public final class CraftingRecipes {
    private static final int CRAFT_GRID_WIDTH = 3;
    private static final int CRAFT_GRID_SLOTS = CRAFT_GRID_WIDTH * CRAFT_GRID_WIDTH;
    private static final int RECIPE_CACHE_LIFETIME_TICKS = 100;
    private static final int SOLVER_RECIPE_ENTRIES_PER_TICK = 128;
    private static final int MAX_SOLVER_RECIPE_ENTRIES = 8192;
    private static final int MAX_SOLVER_SLOT_CHOICES = 128;
    private static final long SOLVER_CAPTURE_BUDGET_NANOS = 1_000_000L;
    private CraftingRecipes() {}

    public static boolean isSupportedType(String recipeType) {
        return "crafting_shaped".equals(recipeType)
                || "crafting_shapeless".equals(recipeType)
                || "stonecutting".equals(recipeType);
    }

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
        private final Item exactOutput;
        private final boolean exactStonecutting;

        public Plan(Object handle, List<List<ItemStack>> grid, int width, int outputCount) {
            this(handle, grid, width, outputCount, null, false);
        }

        private Plan(Object handle, List<List<ItemStack>> grid, int width, int outputCount,
                     Item exactOutput, boolean exactStonecutting) {
            this.handle = handle;
            this.grid = grid;
            this.width = Math.max(1, width);
            this.outputCount = Math.max(1, outputCount);
            this.exactOutput = exactOutput;
            this.exactStonecutting = exactStonecutting;
        }

        public int width() { return width; }
        public int outputCount() { return outputCount; }
        public Item exactOutput() { return exactOutput; }
        public boolean isExactStonecutting() { return exactStonecutting; }
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
    private static Map<Item, List<Plan>> indexedPlans = Map.of();
    private static SolverSnapshot indexedSolverRecipes;

    public static void clearCache() {
        indexedWorld = null;
        indexedCollections = null;
        indexedPlans = Map.of();
        indexedSolverRecipes = null;
    }

    /** 客户端线程分批读取已解锁工作台配方及同步到客户端的切石选项。 */
    public static SolverSnapshot captureForSolver(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;
        if (indexedSolverRecipes == null || !indexedSolverRecipes.isCurrent(mc)) {
            indexedSolverRecipes = new SolverSnapshot(mc);
        }
        return indexedSolverRecipes;
    }

    public record SolverRecipe(String id, String type, String output, int yield, List<List<String>> grid) {
        public SolverRecipe {
            grid = grid.stream().map(List::copyOf).toList();
        }
    }

    public static final class SolverSnapshot {
        private final Object level;
        private final Object recipeBook;
        private final List<RecipeCollection> collections;
        //#if MC >= 12102
        private final SelectableRecipe.SingleInputSet<StonecutterRecipe> stonecutting;
        private int stonecuttingIndex;
        //#endif
        private final Map<String, SolverEntry> entries = new HashMap<>();
        private final Map<String, List<SolverRecipe>> byOutput = new HashMap<>();
        private int collectionIndex;
        private int entryIndex;
        private int scannedEntries;
        private boolean complete;

        private SolverSnapshot(Minecraft mc) {
            this.level = mc.level;
            this.recipeBook = mc.player.getRecipeBook();
            this.collections = mc.player.getRecipeBook().getCollections();
            //#if MC >= 12102
            this.stonecutting = mc.level.recipeAccess().stonecutterRecipes();
            //#endif
        }

        public boolean isCurrent(Minecraft mc) {
            return this == indexedSolverRecipes && level == mc.level && mc.player != null
                    && recipeBook == mc.player.getRecipeBook()
                    //#if MC >= 12102
                    && stonecutting == mc.level.recipeAccess().stonecutterRecipes()
                    //#endif
                    && collections == mc.player.getRecipeBook().getCollections();
        }

        public boolean tick(Minecraft mc) {
            if (complete || !isCurrent(mc)) return complete;
            //#if MC >= 12102
            ContextMap context = SlotDisplayContext.fromLevel(mc.level);
            //#endif
            long started = System.nanoTime();
            for (int step = 0; step < SOLVER_RECIPE_ENTRIES_PER_TICK; step++) {
                if (scannedEntries >= MAX_SOLVER_RECIPE_ENTRIES) {
                    complete = true;
                    return true;
                }
                if (collectionIndex >= collections.size()) {
                    //#if MC >= 12102
                    if (stonecuttingIndex < stonecutting.entries().size()) {
                        int index = stonecuttingIndex++;
                        scannedEntries++;
                        add(captureStonecuttingEntry(index, stonecutting.entries().get(index), context));
                        if (System.nanoTime() - started >= SOLVER_CAPTURE_BUDGET_NANOS) break;
                        continue;
                    }
                    //#endif
                    complete = true;
                    return true;
                }
                RecipeCollection collection = collections.get(collectionIndex);
                if (entryIndex >= collection.getRecipes().size()) {
                    collectionIndex++;
                    entryIndex = 0;
                } else {
                    int index = entryIndex++;
                    scannedEntries++;
                    //#if MC >= 12102
                    add(captureEntry(collection.getRecipes().get(index), context));
                    //#else
                    //$$ add(captureEntry(collection.getRecipes().get(index), mc.level.registryAccess()));
                    //#endif
                }
                if (System.nanoTime() - started >= SOLVER_CAPTURE_BUDGET_NANOS) break;
            }
            return false;
        }

        private void add(SolverEntry entry) {
            if (entry == null || entries.putIfAbsent(entry.recipe().id(), entry) != null) return;
            byOutput.computeIfAbsent(entry.recipe().output(), ignored -> new ArrayList<>()).add(entry.recipe());
        }

        public List<SolverRecipe> recipesFor(String output) {
            return complete ? List.copyOf(byOutput.getOrDefault(output, List.of())) : List.of();
        }

        /** 重新解析实时展示，拒绝快照之后变化的标签和配方。 */
        public Plan bind(Minecraft mc, String id, List<String> grid) {
            if (!complete || !isCurrent(mc)) return null;
            SolverEntry saved = entries.get(id);
            if (saved == null) return null;
            //#if MC >= 12102
            ContextMap context = SlotDisplayContext.fromLevel(mc.level);
            if ("stonecutting".equals(saved.recipe().type())) {
                int index = Integer.parseInt(id.substring("stonecutting/".length()));
                SolverEntry live = captureStonecuttingEntry(index, stonecutting.entries().get(index), context);
                if (live == null || !saved.recipe().equals(live.recipe())) return null;
                return bindExact(live.recipe(), grid, live.handle());
            }
            if (!(saved.handle() instanceof RecipeDisplayEntry entry)) return null;
            SolverEntry live = captureEntry(entry, context);
            //#else
            //$$ if (!(saved.handle() instanceof RecipeHolder<?> holder)) return null;
            //$$ SolverEntry live = captureEntry(holder, mc.level.registryAccess());
            //#endif
            if (live == null || !saved.recipe().equals(live.recipe())) return null;
            return bindExact(live.recipe(), grid);
        }
    }

    record SolverEntry(SolverRecipe recipe, Object handle) {}

    static Plan bindExact(SolverRecipe recipe, List<String> grid) {
        return bindExact(recipe, grid, null);
    }

    static Plan bindExact(SolverRecipe recipe, List<String> grid, Object stonecuttingOption) {
        if (grid.size() != CRAFT_GRID_SLOTS || recipe.grid().size() != CRAFT_GRID_SLOTS) return null;
        Item output = RegistryCompat.item(Identifier.tryParse(recipe.output()));
        if (output == null || output == Items.AIR) return null;
        List<List<ItemStack>> selected = new ArrayList<>(CRAFT_GRID_SLOTS);
        for (int index = 0; index < CRAFT_GRID_SLOTS; index++) {
            List<String> choices = recipe.grid().get(index);
            String itemId = grid.get(index);
            if (itemId == null) return null;
            if (choices.isEmpty() && itemId.isEmpty()) {
                selected.add(List.of());
                continue;
            }
            if (!choices.contains(itemId)) return null;
            Item item = RegistryCompat.item(Identifier.tryParse(itemId));
            if (item == null || item == Items.AIR) return null;
            selected.add(List.of(new ItemStack(item)));
        }
        // 工作台固定格位只手动摆料。切石机需保留同步选项，才能定位当前菜单里的按钮。
        return new Plan(stonecuttingOption, List.copyOf(selected), CRAFT_GRID_WIDTH, recipe.yield(), output,
                "stonecutting".equals(recipe.type()));
    }

    private static List<String> solverChoices(List<ItemStack> stacks) {
        if (stacks.isEmpty() || stacks.size() > MAX_SOLVER_SLOT_CHOICES) return List.of();
        List<String> choices = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            if (stack.getCount() != 1 || !ItemUtil.isPlainLoose(stack) || hasRemainder(stack.getItem())) return List.of();
            choices.add(itemId(stack));
        }
        return choices.stream().distinct().sorted().toList();
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static boolean hasRemainder(Item item) {
        //#if MC >= 260000
        return item.getCraftingRemainder() != null;
        //#elseif MC >= 12102
        //$$ return !item.getCraftingRemainder().isEmpty();
        //#else
        //$$ return item.hasCraftingRemainingItem();
        //#endif
    }

    private static SolverEntry solverEntry(String id, String type, Object handle, ItemStack result,
                                           int width, List<List<String>> pattern) {
        if (!ItemUtil.isPlainLoose(result) || result.getCount() > result.getMaxStackSize()
                || pattern.isEmpty() || pattern.size() > CRAFT_GRID_SLOTS
                || width < 1 || width > CRAFT_GRID_WIDTH || pattern.size() > width * CRAFT_GRID_WIDTH
                || pattern.stream().allMatch(List::isEmpty)) {
            return null;
        }
        List<List<String>> grid = new ArrayList<>(CRAFT_GRID_SLOTS);
        for (int slot = 0; slot < CRAFT_GRID_SLOTS; slot++) {
            int row = slot / CRAFT_GRID_WIDTH;
            int column = slot % CRAFT_GRID_WIDTH;
            int index = row * width + column;
            grid.add(column < width && index < pattern.size() ? pattern.get(index) : List.of());
        }
        return new SolverEntry(new SolverRecipe(id, type, itemId(result), result.getCount(), grid), handle);
    }

    //#if MC >= 12102
    static SolverEntry captureStonecuttingEntry(int index, SelectableRecipe.SingleInputEntry<StonecutterRecipe> entry,
                                                ContextMap context) {
        SlotDisplay result = entry.recipe().optionDisplay();
        if (!(result instanceof SlotDisplay.ItemStackSlotDisplay)) return null;
        SlotDisplay input = entry.input().display();
        List<String> choices = verifiedChoices(input, entry.input(), context);
        if (choices.isEmpty()) return null;
        return solverEntry("stonecutting/" + index, "stonecutting", entry, result.resolveForFirstStack(context),
                1, List.of(choices));
    }

    static SolverEntry captureEntry(RecipeDisplayEntry entry, ContextMap context) {
        RecipeDisplay display = entry.display();
        if (!(display.craftingStation() instanceof SlotDisplay.ItemSlotDisplay station)
                || station.item().value() != Items.CRAFTING_TABLE) return null;
        List<SlotDisplay> slots;
        String type;
        int width;
        if (display instanceof ShapedCraftingRecipeDisplay shaped) {
            slots = shaped.ingredients();
            width = shaped.width();
            if (shaped.height() < 1 || shaped.height() > CRAFT_GRID_WIDTH
                    || width < 1 || width > CRAFT_GRID_WIDTH || slots.size() != width * shaped.height()) return null;
            type = "crafting_shaped";
        } else if (display instanceof ShapelessCraftingRecipeDisplay shapeless) {
            slots = shapeless.ingredients();
            width = CRAFT_GRID_WIDTH;
            type = "crafting_shapeless";
        } else {
            return null;
        }
        // 动态产物展示与返还物无法用物品 ID 安全表达，未知展示不纳入模型。
        if (slots.size() > CRAFT_GRID_SLOTS || !(display.result() instanceof SlotDisplay.ItemStackSlotDisplay)) return null;
        List<Ingredient> requirements = entry.craftingRequirements().orElse(List.of());
        List<List<String>> pattern = new ArrayList<>(slots.size());
        int ingredientIndex = 0;
        for (SlotDisplay slot : slots) {
            if (slot instanceof SlotDisplay.Empty && display instanceof ShapedCraftingRecipeDisplay) {
                pattern.add(List.of());
                continue;
            }
            if (ingredientIndex >= requirements.size()) return null;
            Ingredient requirement = requirements.get(ingredientIndex++);
            List<String> choices = verifiedChoices(slot, requirement, context);
            if (choices.isEmpty()) return null;
            pattern.add(choices);
        }
        if (ingredientIndex != requirements.size()) return null;
        ItemStack result = display.result().resolveForFirstStack(context);
        return solverEntry(Integer.toString(entry.id().index()), type, entry, result, width, pattern);
    }

    private static List<String> verifiedChoices(SlotDisplay slot, Ingredient requirement, ContextMap context) {
        if (!ordinaryDisplay(slot)) return List.of();
        List<ItemStack> stacks = slot.resolve(context, SlotDisplay.ItemStackContentsFactory.INSTANCE)
                .limit(MAX_SOLVER_SLOT_CHOICES + 1L)
                .toList();
        if (stacks.stream().anyMatch(stack -> !requirement.test(stack))) return List.of();
        return solverChoices(stacks);
    }

    private static boolean ordinaryDisplay(SlotDisplay slot) {
        if (itemDisplay(slot)) return true;
        return slot instanceof SlotDisplay.Composite composite
                && !composite.contents().isEmpty() && composite.contents().size() <= MAX_SOLVER_SLOT_CHOICES
                && composite.contents().stream().allMatch(CraftingRecipes::itemDisplay);
    }

    private static boolean itemDisplay(SlotDisplay slot) {
        return slot instanceof SlotDisplay.ItemSlotDisplay || slot instanceof SlotDisplay.TagSlotDisplay;
    }
    //#else
    //$$ static SolverEntry captureEntry(RecipeHolder<?> holder, HolderLookup.Provider registries) {
    //$$     if (!"minecraft".equals(holder.id().getNamespace())) return null;
    //$$     String type;
    //$$     int width;
    //$$     if (holder.value() instanceof ShapedRecipe shaped && shaped.getClass() == ShapedRecipe.class) {
    //$$         type = "crafting_shaped";
    //$$         width = shaped.getWidth();
    //$$     } else if (holder.value().getClass() == ShapelessRecipe.class) {
    //$$         type = "crafting_shapeless";
    //$$         width = CRAFT_GRID_WIDTH;
    //$$     } else {
    //$$         return null;
    //$$     }
    //$$     if (!(holder.value() instanceof CraftingRecipe crafting)) return null;
    //$$     List<Ingredient> ingredients = crafting.getIngredients();
    //$$     if (ingredients.size() > CRAFT_GRID_SLOTS) return null;
    //$$     List<List<String>> pattern = new ArrayList<>(ingredients.size());
    //$$     for (Ingredient ingredient : ingredients) {
    //$$         if (ingredient.isEmpty() && holder.value() instanceof ShapedRecipe) {
    //$$             pattern.add(List.of());
    //$$             continue;
    //$$         }
    //$$         List<String> choices = solverChoices(List.of(ingredient.getItems()));
    //$$         if (choices.isEmpty()) return null;
    //$$         pattern.add(choices);
    //$$     }
    //$$     return solverEntry(holder.id().toString(), type, holder, crafting.getResultItem(registries), width, pattern);
    //$$ }
    //#endif

    public static List<Plan> findAll(Minecraft mc, Item item) {
        if (mc.player == null || mc.level == null) return List.of();
        List<RecipeCollection> collections = mc.player.getRecipeBook().getCollections();
        long tick = mc.level.getGameTime();
        if (indexedWorld != mc.level || indexedCollections != collections
                || tick < indexedTick || tick - indexedTick > RECIPE_CACHE_LIFETIME_TICKS) {
            Map<Item, List<Plan>> plans = new HashMap<>();
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
            indexedPlans = Map.copyOf(plans);
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

    /** 按同步选项的身份定位当前输入下的按钮，输出须与计划的普通产物和单次产量相同。 */
    public static int exactStonecutterIndex(Minecraft mc, AbstractContainerMenu menu, Plan plan) {
        //#if MC >= 12102
        if (!(menu instanceof StonecutterMenu stonecutter)) return -1;
        return exactStonecutterIndex(plan, menu.getSlot(0).getItem(), stonecutter.getVisibleRecipes().entries(),
                SlotDisplayContext.fromLevel(mc.level));
        //#else
        //$$ return -1;
        //#endif
    }

    //#if MC >= 12102
    static int exactStonecutterIndex(Plan plan, ItemStack input,
                                   List<SelectableRecipe.SingleInputEntry<StonecutterRecipe>> entries, ContextMap context) {
        if (!plan.isExactStonecutting()) return -1;
        if (!ItemUtil.isPlainLoose(input) || !input.is(plan.ingredientsAt(0).getFirst().getItem())) return -1;
        for (int index = 0; index < entries.size(); index++) {
            SelectableRecipe.SingleInputEntry<StonecutterRecipe> option = entries.get(index);
            if (option != plan.handle || !option.input().test(input)) continue;
            ItemStack output = option.recipe().optionDisplay().resolveForFirstStack(context);
            if (ItemUtil.isPlainLoose(output) && output.is(plan.exactOutput())
                    && output.getCount() == plan.outputCount()) return index;
            return -1;
        }
        return -1;
    }
    //#endif

}
