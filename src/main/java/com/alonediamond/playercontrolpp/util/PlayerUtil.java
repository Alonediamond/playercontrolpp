package com.alonediamond.playercontrolpp.util;

import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

/**
 * Small player queries shared by the scanning features.
 */
public final class PlayerUtil {

    /**
     * Hotbar size.
     *
     * <p>Minecraft does expose this as {@code Inventory.SELECTION_SIZE}, but only from 1.21.4
     * onwards — 1.21.1 has {@code INVENTORY_SIZE} and nothing for the hotbar. Since this project
     * builds one source tree against all five versions, the constant lives here instead.
     * {@code Inventory.INVENTORY_SIZE} exists everywhere and is used directly.
     */
    public static final int HOTBAR_SIZE = 9;

    /** Vanilla survival block reach, used when the attribute is somehow unavailable. */
    private static final double DEFAULT_BLOCK_REACH = 4.5;

    private PlayerUtil() {}

    /**
     * Block interaction range in blocks.
     *
     * <p>Since 1.20.5 reach is an attribute, so server plugins, other mods and enchantments can
     * change it. Reading the attribute instead of hardcoding {@code isCreative() ? 5.0 : 4.5}
     * keeps the scan radius in step with what the player can actually touch.
     */
    public static double blockReach(Player player) {
        AttributeInstance instance = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
        return instance != null ? instance.getValue() : DEFAULT_BLOCK_REACH;
    }

    /** Squared block reach, for comparing against {@code distSqr} without a square root. */
    public static double blockReachSq(Player player) {
        double reach = blockReach(player);
        return reach * reach;
    }
}
