package com.alonediamond.playercontrolpp.compat;

import net.minecraft.world.entity.player.Inventory;

/**
 * 当前选中的快捷栏槽位。
 *
 * <p>1.21.5 把 public 字段 {@code Inventory.selected} 封进了
 * {@code getSelectedSlot()} / {@code setSelectedSlot(int)}，setter 还加了
 * {@code isHotbarSlot} 范围检查，所以在所有版本上都必须传 {@code 0..8}。
 */
public final class InventoryCompat {

    private InventoryCompat() {}

    /** @return 当前选中的快捷栏槽位，{@code 0..8}。 */
    public static int getSelectedSlot(Inventory inventory) {
        //#if MC >= 12105
        return inventory.getSelectedSlot();
        //#else
        //$$ return inventory.selected;
        //#endif
    }

    /**
     * 只改客户端的选中槽位。调用方还得发 {@code ServerboundSetCarriedItemPacket} 同步给服务端，
     * 否则服务端仍认为玩家手持之前那个物品。
     *
     * @param slot 快捷栏槽位，必须在 {@code 0..8}
     */
    public static void setSelectedSlot(Inventory inventory, int slot) {
        //#if MC >= 12105
        inventory.setSelectedSlot(slot);
        //#else
        //$$ inventory.selected = slot;
        //#endif
    }
}
