package com.alonediamond.playercontrolpp.compat;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;

/** 复用原版丢掷热键的预测、发包及挥手行为。 */
public final class DropCompat {

    private DropCompat() {}

    /**
     * 丢当前选中的快捷栏物品，等价于按 Q / Ctrl+Q。
     *
     * @param dropAll {@code true} 丢整组（Ctrl+Q），{@code false} 丢一件（Q）
     */
    public static void dropSelected(MultiPlayerGameMode gameMode, LocalPlayer player, boolean dropAll) {
        if (player.isSpectator()) return;
        //#if MC >= 260300
        //$$ gameMode.dropItem(player, dropAll);
        //#else
        if (player.drop(dropAll)) {
            player.swing(InteractionHand.MAIN_HAND);
        }
        //#endif
    }
}
