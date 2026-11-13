package com.alonediamond.playercontrolpp.inventory;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/** 菜单状态与 Screen 分开判断：自动缓存会屏蔽 GUI，但菜单仍然正常同步。 */
public final class ContainerMenus {
    private ContainerMenus() {}

    public static boolean isOpen(Minecraft mc) {
        return mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu;
    }

    /** 只检查容器侧，玩家背包里的物品不能作为内容已出现的依据。 */
    public static boolean hasContainerItems(Minecraft mc) {
        for (Slot slot : mc.player.containerMenu.slots) {
            if (!(slot.container instanceof Inventory) && !slot.getItem().isEmpty()) return true;
        }
        return false;
    }

    public static void close(Minecraft mc) {
        if (mc != null && isOpen(mc)) mc.player.closeContainer();
    }
}
