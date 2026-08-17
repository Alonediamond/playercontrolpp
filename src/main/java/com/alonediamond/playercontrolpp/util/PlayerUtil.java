package com.alonediamond.playercontrolpp.util;

import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

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
}
