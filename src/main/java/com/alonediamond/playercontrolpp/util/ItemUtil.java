package com.alonediamond.playercontrolpp.util;

import com.alonediamond.playercontrolpp.compat.ContainerContentsCompat;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.Collections;
import java.util.List;

/**
 * 备货、存盒、填水三个功能共用的物品判定。这些方法早先在三四个类里各抄了一份。
 */
public final class ItemUtil {

    private ItemUtil() {}

    /**
     * @return 是否为任意颜色的潜影盒。
     *
     * <p>判方块类型而不是拿注册名去匹配 {@code "shulker_box"}：原版结果一致，
     * 每次调用不分配字符串，也不指望模组潜影盒刚好取了个方便的名字。
     */
    public static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    /**
     * @return 这个物品堆是否就是该物品。
     *
     * <p>直接比引用：{@link Item} 是注册表单例，同一物品必然同一对象。
     * （早先还回退比较注册名，每次调用多两次查表，而且会把两个<em>未注册</em>物品判为相等，
     * 因为它们都解析到默认 key。）
     */
    public static boolean is(ItemStack stack, Item item) {
        return stack.getItem() == item;
    }

    /** @return 潜影盒里的非空物品堆；没有容器组件时返回空表。读的是客户端最后同步到的内容。 */
    public static List<ItemStack> contentsOf(ItemStack shulkerBox) {
        ItemContainerContents contents = shulkerBox.get(DataComponents.CONTAINER);
        return contents == null ? Collections.emptyList() : ContainerContentsCompat.nonEmptyItems(contents);
    }

    /** @return 该潜影盒里有多少个 {@code item}。 */
    public static int countInside(ItemStack shulkerBox, Item item) {
        int count = 0;
        for (ItemStack inner : contentsOf(shulkerBox)) {
            if (is(inner, item)) {
                count += inner.getCount();
            }
        }
        return count;
    }

    /** @return 该潜影盒里是否至少有一个 {@code item}。 */
    public static boolean containsInside(ItemStack shulkerBox, Item item) {
        for (ItemStack inner : contentsOf(shulkerBox)) {
            if (is(inner, item)) return true;
        }
        return false;
    }

    /**
     * @return 玩家持有多少个 {@code item}：36 格主物品栏里的散装，加上其中潜影盒内的持有量。
     *
     * <p>这是备货全流程统一的"持有"口径——满足判定、缺口重判、原材料预留额度都用它，
     * 三处口径不一致就会出现"已经拿到了却还在拿"或"其实够用却判定缺料"。
     */
    public static int countEverywhere(Player player, Item item) {
        if (player == null || item == null) return 0;
        Inventory inventory = player.getInventory();
        int count = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (is(stack, item)) {
                count += stack.getCount();
            } else if (isShulkerBox(stack)) {
                count += countInside(stack, item);
            }
        }
        return count;
    }

    /**
     * @return 玩家物品栏里<b>散装</b>持有多少个 {@code item}（不含潜影盒内的）。
     *         合成消耗的是散装物品，所以合成前要按这个口径再查一遍。
     */
    public static int countLoose(Player player, Item item) {
        if (player == null || item == null) return 0;
        Inventory inventory = player.getInventory();
        int count = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (is(stack, item)) {
                count += stack.getCount();
            }
        }
        return count;
    }
}
