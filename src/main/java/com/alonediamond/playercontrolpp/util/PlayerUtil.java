package com.alonediamond.playercontrolpp.util;

import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/**
 * 各扫描类功能共用的玩家查询。
 */
public final class PlayerUtil {

    /**
     * 快捷栏格数。
     *
     * <p>原版有 {@code Inventory.SELECTION_SIZE}，但 1.21.4 起才有——1.21.1 只有
     * {@code INVENTORY_SIZE}，没有快捷栏常量。本项目一份源码要编所有版本，所以常量放这里。
     * {@code Inventory.INVENTORY_SIZE} 每个版本都有，直接用官方的。
     */
    public static final int HOTBAR_SIZE = 9;

    /** 属性读不到时的兜底值：原版生存模式的交互距离。 */
    private static final double DEFAULT_BLOCK_REACH = 4.5;

    private PlayerUtil() {}

    /**
     * 方块交互距离（格）。
     *
     * <p>1.20.5 起交互距离是属性，服务端插件、其它模组、附魔都能改它。
     * 读属性而不是硬编码 {@code isCreative() ? 5.0 : 4.5}，扫描半径才和玩家真能碰到的范围一致。
     */
    public static double blockReach(Player player) {
        AttributeInstance instance = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
        return instance != null ? instance.getValue() : DEFAULT_BLOCK_REACH;
    }

    /** 交互距离的平方，用来和 {@code distSqr} 比较，省一次开方。 */
    public static double blockReachSq(Player player) {
        double reach = blockReach(player);
        return reach * reach;
    }

    /**
     * @return 36 格主物品栏是否一个空格都没有。
     *
     * <p>只看主物品栏，不看装备栏与副手——备货能用的就只有这 36 格。
     * 原先 {@code ContainerSearcher}、{@code MaterialAnalyzer}、{@code ItemTransferExecutor}、
     * {@code BaritonePathingController} 各有一份完全相同的实现，统一到这里。
     */
    /**
     * 把物品栏索引换算成"当前打开的这个菜单"里的槽位号。
     *
     * <p>不能用 {@code QuickShulkerIntegration.menuSlotForInventorySlot()}：那是
     * {@code InventoryMenu} 专用的编号（快捷栏 36-44）。工作台菜单的快捷栏在 37-45、
     * 切石机在 29-37，各菜单都不一样。这里直接按"槽位属于玩家物品栏且容器序号相同"来找，
     * 对任何菜单都成立。
     *
     * @param inventoryIndex 物品栏空间的槽位，0-35
     * @return 菜单空间的槽位号；这个菜单里没有这一格时返回 -1
     */
    public static int menuSlotOf(AbstractContainerMenu menu, Inventory inventory, int inventoryIndex) {
        if (menu == null || inventory == null) return -1;
        for (Slot slot : menu.slots) {
            if (slot.container == inventory && slot.getContainerSlot() == inventoryIndex) {
                return slot.index;
            }
        }
        return -1;
    }

    public static boolean isInventoryFull(Player player) {
        if (player == null) return true;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (player.getInventory().getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
