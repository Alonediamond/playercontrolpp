package com.alonediamond.playercontrolpp.compat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Player-facing helpers whose signature changed across Minecraft versions.
 */
public final class PlayerCompat {

    private PlayerCompat() {}

    /**
     * Shows {@code text} on the action bar (above the hotbar).
     *
     * <p>Minecraft 26.1 split the overlay case out of the general
     * {@code displayClientMessage(Component, boolean)} into a dedicated
     * {@code sendOverlayMessage(Component)}.
     */
    public static void sendOverlayMessage(LocalPlayer player, Component text) {
        //#if MC >= 260000
        player.sendOverlayMessage(text);
        //#else
        //$$ player.displayClientMessage(text, true);
        //#endif
    }
}
