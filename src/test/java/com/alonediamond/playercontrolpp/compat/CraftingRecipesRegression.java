package com.alonediamond.playercontrolpp.compat;

import com.alonediamond.playercontrolpp.compat.CraftingRecipes.Plan;
import com.alonediamond.playercontrolpp.compat.CraftingRecipes.SolverEntry;
import com.alonediamond.playercontrolpp.compat.CraftingRecipes.SolverRecipe;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplySolver;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

//#if MC >= 12102
import net.minecraft.core.Registry;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import java.util.Optional;
import java.util.OptionalInt;
//#if MC >= 260000
import net.minecraft.world.item.ItemStackTemplate;
//#endif
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
//#else
//$$ import net.minecraft.core.Holder;
//$$ import net.minecraft.resources.ResourceLocation;
//$$ import net.minecraft.tags.TagKey;
//$$ import net.minecraft.world.item.crafting.CraftingBookCategory;
//$$ import net.minecraft.world.item.crafting.RecipeHolder;
//$$ import net.minecraft.world.item.crafting.ShapedRecipe;
//$$ import net.minecraft.world.item.crafting.ShapedRecipePattern;
//$$ import java.util.HashMap;
//#endif

public final class CraftingRecipesRegression {
    private CraftingRecipesRegression() {}

    public static int run() {
        ItemStack result = new ItemStack(Items.OAK_FENCE_GATE, 2);
        SolverEntry captured = capture(Items.OAK_PLANKS, result);
        require(captured != null && captured.recipe().yield() == 2, "保留工作台配方的固定产量");
        List<String> concrete = List.of("minecraft:spruce_planks", "", "", "", "minecraft:stick", "", "", "", "");
        Plan plan = CraftingRecipes.bindExact(captured.recipe(), concrete);
        require(plan != null && plan.width() == 3 && plan.slotCount() == 9
                        && plan.isEmptySlot(1) && plan.isEmptySlot(3)
                        && plan.ingredientsAt(0).getFirst().getItem() == Items.SPRUCE_PLANKS
                        && plan.ingredientsAt(4).getFirst().getItem() == Items.STICK
                        && plan.exactOutput() == Items.OAK_FENCE_GATE && plan.outputCount() == 2,
                "固定两种可接受木板中的求解结果，保留宽度与孔位");
        List<String> wrongChoice = new ArrayList<>(concrete);
        wrongChoice.set(0, "minecraft:birch_planks");
        require(CraftingRecipes.bindExact(captured.recipe(), wrongChoice) == null,
                "不得绑定配方外的木板");
        List<String> wrongHole = new ArrayList<>(concrete);
        wrongHole.set(1, "minecraft:stick");
        wrongHole.set(4, "");
        require(CraftingRecipes.bindExact(captured.recipe(), wrongHole) == null,
                "不得向有序配方的孔位摆料");
        result.set(DataComponents.CUSTOM_NAME, Component.literal("组件产物"));
        require(capture(Items.OAK_PLANKS, result) == null, "跳过含非默认组件的产物");
        require(capture(Items.MILK_BUCKET, new ItemStack(Items.OAK_FENCE_GATE, 2)) == null,
                "跳过有返还物的材料");
        return 6;
    }

    /** 测试进程末尾绑定原版物品标签，从对应 MC 版本的实际配方生成客户端快照。 */
    public static int runVanillaPistons() throws IOException {
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        try (MultiPackResourceManager packs = new MultiPackResourceManager(
                //#if MC >= 260300
                //$$ PackType.SERVER_DATA, List.of(ServerPacksSource.createVanillaPackSource().fullResources()))) {
                //#else
                PackType.SERVER_DATA, List.of(ServerPacksSource.createVanillaPackSource()))) {
                //#endif
            //#if MC >= 12102
            TagLoader.loadTagsForExistingRegistries(packs, registries).stream()
                    .filter(tags -> tags.key() == Registries.ITEM)
                    .forEach(Registry.PendingTags::apply);
            //#else
            //$$ TagLoader<Holder<Item>> loader = new TagLoader<>(BuiltInRegistries.ITEM::getHolder, "tags/item");
            //$$ Map<TagKey<Item>, List<Holder<Item>>> tags = new HashMap<>();
            //$$ loader.loadAndBuild(packs).forEach((id, values) -> tags.put(TagKey.create(Registries.ITEM, id), List.copyOf(values)));
            //$$ BuiltInRegistries.ITEM.bindTags(tags);
            //#endif
        }

        SolverRecipe piston = captureVanilla("piston", 10, registries);
        SolverRecipe birch = captureVanilla("birch_planks", 11, registries);
        require(piston.grid().getFirst().containsAll(List.of("minecraft:oak_planks", "minecraft:birch_planks"))
                        && piston.grid().get(3).equals(List.of("minecraft:cobblestone")),
                "真实活塞配方保留木板标签和圆石约束");
        require(birch.grid().getFirst().contains("minecraft:birch_log") && birch.yield() == 4,
                "真实白桦木板配方保留原木标签和批量产物");

        SupplyModel.Hint oak = new SupplyModel.Hint("minecraft:oak_planks", 12, "base", List.of());
        SupplyModel.Hint cobblestone = new SupplyModel.Hint("minecraft:cobblestone", 16, "base", List.of());
        SupplyModel.Hint hint = new SupplyModel.Hint(piston.output(), 4, piston.type(), List.of(oak, cobblestone));
        List<SupplyModel.Recipe> recipes = new ArrayList<>(List.of(model(piston), model(birch)));
        List<SupplyModel.Source> stock = new ArrayList<>(List.of(stock("oak_planks", 500), stock("birch_log", 1000),
                stock("iron_ingot", 4), stock("redstone", 4)));
        int checks = 4;
        //#if MC >= 12102
        SolverEntry cut = captureAndMatchStonecutting(registries);
        recipes.add(model(cut.recipe()));
        stock.add(stock("stone", 16));
        checks += 2;
        //#else
        //$$ stock.add(stock("cobblestone", 16));
        //#endif
        SupplyModel.Request request = new SupplyModel.Request(new SupplyModel.Catalog(recipes),
                stock, List.of(new SupplyModel.Goal(piston.output(), 4, hint)), Map.of(), Set.of(), Set.of(), 2,
                SupplyModel.Policy.SAVE_STOCK, SupplyModel.Mode.PREPARE);
        SupplyModel.Result result = SupplySolver.solve(request, 200_000);
        require(result.plan() != null && result.plan().shoppingList().getOrDefault("minecraft:birch_log", 0L) == 3
                        && !result.plan().shoppingList().containsKey("minecraft:oak_planks"),
                "省库存策略经实际配方适配使用三根白桦原木");
        //#if MC >= 12102
        require(result.plan().shoppingList().getOrDefault("minecraft:stone", 0L) == 16
                        && !result.plan().shoppingList().containsKey("minecraft:cobblestone"),
                "缺少现成圆石时使用同步的石头转圆石切石选项");
        checks++;
        //#endif
        SupplyModel.Make craft = result.plan().actions().stream()
                .filter(SupplyModel.Make.class::isInstance)
                .map(SupplyModel.Make.class::cast)
                .filter(action -> action.recipe().output().equals(piston.output()))
                .findFirst().orElseThrow();
        Plan bound = CraftingRecipes.bindExact(piston, craft.grid());
        require(bound != null && bound.ingredientsAt(0).getFirst().is(Items.BIRCH_PLANKS),
                "活塞计划的白桦木板可以绑定到实际九格配方");
        return checks;
    }

    //#if MC >= 12102
    private static SolverEntry captureAndMatchStonecutting(RegistryAccess registries) throws IOException {
        ItemStack output = new ItemStack(Items.COBBLESTONE);
        //#if MC >= 260000
        SlotDisplay result = new SlotDisplay.ItemStackSlotDisplay(ItemStackTemplate.fromNonEmptyStack(output));
        //#else
        //$$ SlotDisplay result = new SlotDisplay.ItemStackSlotDisplay(output);
        //#endif
        Ingredient input = Ingredient.of(Items.STONE);
        // 有原版石头转圆石配方的版本直接读取它，较早版本用同结构的同步选项测试兼容接口。
        try (InputStream data = recipeResource("cobblestone_from_stone_stonecutting")) {
            if (data != null) {
                StonecutterRecipe vanilla = (StonecutterRecipe) decodeRecipe(data, registries);
                input = vanilla.input();
                result = vanilla.resultDisplay();
            }
        }
        SelectableRecipe<StonecutterRecipe> option = new SelectableRecipe<>(result, Optional.empty());
        SelectableRecipe.SingleInputEntry<StonecutterRecipe> entry = new SelectableRecipe.SingleInputEntry<>(input, option);
        ContextMap context = recipeContext(registries);
        SolverEntry captured = CraftingRecipes.captureStonecuttingEntry(2, entry, context);
        require(captured != null && captured.recipe().grid().getFirst().equals(List.of("minecraft:stone"))
                        && captured.recipe().grid().subList(1, 9).stream().allMatch(List::isEmpty),
                "同步切石选项只使用首格的一个普通输入");
        List<String> grid = List.of("minecraft:stone", "", "", "", "", "", "", "", "");
        Plan bound = CraftingRecipes.bindExact(captured.recipe(), grid, captured.handle());
        SelectableRecipe.SingleInputEntry<StonecutterRecipe> sameOutput = new SelectableRecipe.SingleInputEntry<>(input, option);
        require(bound != null && bound.isExactStonecutting()
                        && CraftingRecipes.exactStonecutterIndex(bound, new ItemStack(Items.STONE), List.of(sameOutput, entry), context) == 1
                        && CraftingRecipes.exactStonecutterIndex(bound, new ItemStack(Items.ANDESITE), List.of(entry), context) == -1,
                "匹配实时菜单中的原定选项，拒绝不同输入和仅产物相同的选项");
        return captured;
    }

    private static ContextMap recipeContext(RegistryAccess registries) {
        //#if MC >= 260300
        //$$ return ContextMap.builder().set(SlotDisplayContext.REGISTRIES, registries).build();
        //#else
        return new ContextMap.Builder().withParameter(SlotDisplayContext.REGISTRIES, registries)
                .create(SlotDisplayContext.CONTEXT);
        //#endif
    }
    //#endif

    private static SolverRecipe captureVanilla(String name, int displayId, RegistryAccess registries) throws IOException {
        try (InputStream data = recipeResource(name)) {
            if (data == null) throw new IOException("配方资源缺失 " + name);
            Recipe<?> recipe = decodeRecipe(data, registries);
            //#if MC >= 12102
            RecipeDisplayEntry entry = new RecipeDisplayEntry(new RecipeDisplayId(displayId), recipe.display().getFirst(),
                    OptionalInt.empty(), recipe.recipeBookCategory(), Optional.of(recipe.placementInfo().ingredients()));
            SolverEntry captured = CraftingRecipes.captureEntry(entry, recipeContext(registries));
            //#else
            //$$ SolverEntry captured = CraftingRecipes.captureEntry(
            //$$         new RecipeHolder<>(ResourceLocation.withDefaultNamespace(name), recipe), registries);
            //#endif
            require(captured != null, "原版配方捕获失败 " + name);
            return captured.recipe();
        }
    }

    private static InputStream recipeResource(String name) {
        return CraftingRecipesRegression.class.getResourceAsStream("/data/minecraft/recipe/" + name + ".json");
    }

    private static Recipe<?> decodeRecipe(InputStream data, RegistryAccess registries) {
        //#if MC >= 260300
        //$$ return Recipe.DIRECT_CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE),
        //#else
        return Recipe.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE),
        //#endif
                JsonParser.parseReader(new InputStreamReader(data, StandardCharsets.UTF_8))).getOrThrow();
    }

    private static SupplyModel.Recipe model(SolverRecipe recipe) {
        return new SupplyModel.Recipe(recipe.id(), recipe.type(), recipe.output(), recipe.yield(),
                recipe.grid().stream().map(SupplyModel.Slot::new).toList());
    }

    private static SupplyModel.Source stock(String item, int count) {
        return new SupplyModel.Source("chest", "minecraft:" + item, count, 1);
    }

    private static SolverEntry capture(Item first, ItemStack output) {
        Ingredient firstSlot = Ingredient.of(first, Items.SPRUCE_PLANKS);
        Ingredient secondSlot = Ingredient.of(Items.STICK);
        //#if MC >= 12102
        SlotDisplay layout = new SlotDisplay.Composite(List.of(
                new SlotDisplay.ItemSlotDisplay(first), new SlotDisplay.ItemSlotDisplay(Items.SPRUCE_PLANKS)));
        //#if MC >= 260000
        SlotDisplay result = new SlotDisplay.ItemStackSlotDisplay(ItemStackTemplate.fromNonEmptyStack(output));
        //#else
        //$$ SlotDisplay result = new SlotDisplay.ItemStackSlotDisplay(output);
        //#endif
        ShapedCraftingRecipeDisplay display = new ShapedCraftingRecipeDisplay(2, 2, List.of(
                layout, SlotDisplay.Empty.INSTANCE, SlotDisplay.Empty.INSTANCE, new SlotDisplay.ItemSlotDisplay(Items.STICK)),
                result, new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE));
        RecipeDisplayEntry entry = new RecipeDisplayEntry(new RecipeDisplayId(7), display, OptionalInt.empty(),
                RecipeBookCategories.CRAFTING_MISC, Optional.of(List.of(firstSlot, secondSlot)));
        //#if MC >= 260300
        //$$ ContextMap context = ContextMap.EMPTY;
        //#else
        ContextMap context = new ContextMap.Builder().create(SlotDisplayContext.CONTEXT);
        //#endif
        return CraftingRecipes.captureEntry(entry, context);
        //#else
        //$$ ShapedRecipePattern layout = ShapedRecipePattern.of(Map.of('A', firstSlot, 'B', secondSlot), "A ", " B");
        //$$ ShapedRecipe recipe = new ShapedRecipe("", CraftingBookCategory.MISC, layout, output);
        //$$ return CraftingRecipes.captureEntry(new RecipeHolder<>(ResourceLocation.tryParse("minecraft:solver_test"), recipe), null);
        //#endif
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
