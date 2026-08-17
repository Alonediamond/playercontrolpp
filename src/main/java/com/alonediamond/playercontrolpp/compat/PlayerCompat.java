package com.alonediamond.playercontrolpp.compat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * 签名在版本间变过的玩家相关方法。
 */
public final class PlayerCompat {

    private PlayerCompat() {}

    /**
     * 在 ActionBar（快捷栏上方）显示 {@code text}。
     *
     * <p>26.1 把这个用途从 {@code displayClientMessage(Component, boolean)} 里拆成了独立的
     * {@code sendOverlayMessage(Component)}。
     */
    public static void sendOverlayMessage(LocalPlayer player, Component text) {
        //#if MC >= 260000
        player.sendOverlayMessage(text);
        //#else
        //$$ player.displayClientMessage(text, true);
        //#endif
    }
}
