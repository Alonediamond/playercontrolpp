package com.alonediamond.playercontrolpp.inventory.shulker;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** 使用真实物品组件核对潜影盒的空槽与合并容量。 */
public final class ShulkerStorageCapacityRegression {
    private ShulkerStorageCapacityRegression() {}

    public static int run() {
        int checks = 0;
        List<Item> different = List.of(
                Items.STONE, Items.DIRT, Items.COBBLESTONE, Items.GRANITE, Items.DIORITE,
                Items.ANDESITE, Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG, Items.JUNGLE_LOG,
                Items.ACACIA_LOG, Items.DARK_OAK_LOG, Items.MANGROVE_LOG, Items.CHERRY_LOG,
                Items.SAND, Items.RED_SAND, Items.GRAVEL, Items.CLAY_BALL, Items.BRICK,
                Items.IRON_INGOT, Items.GOLD_INGOT, Items.COPPER_INGOT, Items.COAL,
                Items.CHARCOAL, Items.FLINT, Items.STRING, Items.STICK);
        List<ItemStack> occupied = new ArrayList<>();
        for (Item item : different) occupied.add(new ItemStack(item, item.getDefaultMaxStackSize() / 2));
        check(!ShulkerStorageCapacity.canStoreAny(occupied, List.of(new ItemStack(Items.DIAMOND))),
                "27 格不同物品半堆且不匹配时拒绝");
        checks++;

        List<ItemStack> matching = stacks(Items.STONE, 27, 32);
        check(ShulkerStorageCapacity.canStoreAny(matching, List.of(new ItemStack(Items.STONE))),
                "同物品同组件半堆可以合并");
        checks++;

        List<ItemStack> named = stacks(Items.STONE, 27, 32);
        for (ItemStack stack : named) stack.set(DataComponents.CUSTOM_NAME, Component.literal("A"));
        check(!ShulkerStorageCapacity.canStoreAny(named, List.of(new ItemStack(Items.STONE))),
                "同物品但组件不同不能合并");
        checks++;

        check(ShulkerStorageCapacity.canStoreAny(stacks(Items.DIRT, 26, 64),
                List.of(new ItemStack(Items.STONE))), "有空槽时接受可入盒物品");
        checks++;
        check(!ShulkerStorageCapacity.canStoreAny(stacks(Items.DIRT, 26, 64),
                List.of(new ItemStack(Items.SHULKER_BOX))), "有空槽仍拒绝不可入盒物品");
        checks++;
        check(!ShulkerStorageCapacity.canStoreAny(stacks(Items.STONE, 27, 64),
                List.of(new ItemStack(Items.STONE))), "满盒拒绝");
        checks++;
        check(!ShulkerStorageCapacity.canStoreAny(stacks(Items.ENDER_PEARL, 27, 16),
                List.of(new ItemStack(Items.ENDER_PEARL))), "按 16 件实际上限判满");
        checks++;
        check(ShulkerStorageCapacity.canStoreAny(stacks(Items.ENDER_PEARL, 27, 15),
                List.of(new ItemStack(Items.ENDER_PEARL))), "16 件堆栈留一件时可合并");
        checks++;
        return checks;
    }

    private static List<ItemStack> stacks(Item item, int slots, int count) {
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < slots; i++) result.add(new ItemStack(item, count));
        return result;
    }

    private static void check(boolean passed, String scenario) {
        if (!passed) throw new AssertionError(scenario);
    }
}
