package com.alonediamond.playercontrolpp.compat;

import net.minecraft.client.player.LocalPlayer;

/**
 * 丢掷手持物品。
 *
 * <p>26.3 把 {@code LocalPlayer.drop(boolean)} 挪回了 {@code LivingEntity}，签名也变成了
 * {@code drop(ItemStack, boolean, Prediction)} 并返回实体——它是掉落物生成的通用底层。
 * 客户端丢快捷键真正等价的是 {@code Inventory.removeFromSelected(boolean)}：
 * 服务端处理 Q 键的入口就是它（{@code ServerboundPlayerActionPacket DROP_ALL_ITEM} 分支
 * 在服务端逐个调用 removeFromSelected / 扣数量），而且它在所有版本都存在。
 */
public final class DropCompat {

    private DropCompat() {}

    /**
     * 丢当前选中的快捷栏物品，等价于按 Q / Ctrl+Q。
     *
     * @param dropAll {@code true} 丢整组（Ctrl+Q），{@code false} 丢一件（Q）
     */
    public static void dropSelected(LocalPlayer player, boolean dropAll) {
        //#if MC >= 260300
        player.getInventory().removeFromSelected(dropAll);
        //#else
        //$$ player.drop(dropAll);
        //#endif
    }
}
