package com.alonediamond.playercontrolpp.mixin.compat.quickshulker;

import com.alonediamond.playercontrolpp.integration.QuickShulkerIntegration;

import net.kyrptonaught.quickshulker.network.OpenShulkerPacket;
import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * {@link QuickShulkerIntegration} 的直连实现，由 MixinPlugin 在 QuickShulker 在场时注入。
 * QuickShulker 2.3.3（1.21.4）到 3.1.0（26.2）之间 {@code OpenShulkerPacket.sendOpenPacket(int)}
 * 的签名完全一致，无需版本分支。
 */
@Mixin(QuickShulkerIntegration.class)
public abstract class QuickShulkerIntegrationImpl {

    @Overwrite(remap = false)
    public boolean isLoaded() {
        return true;
    }

    /**
     * 通过 QuickShulker 自己的发包函数打开潜影盒。
     *
     * <p>服务端是对着 {@code player.containerMenu} 解析槽位的，所以只有当前打开的是玩家自己的
     * 物品栏菜单时这个调用才有意义——开着箱子时同一个索引指向完全不同的槽位。
     * 前置条件由 {@link QuickShulkerIntegration#canOpenFromInventory(Minecraft)} 检查。
     */
    @Overwrite(remap = false)
    public boolean openShulkerBox(int menuSlot) {
        try {
            OpenShulkerPacket.sendOpenPacket(menuSlot);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * @return 现在传物品栏空间的槽位索引会不会被正确解析，也就是当前打开的容器是否为玩家自己的物品栏菜单。
     */
    @Overwrite(remap = false)
    public boolean canOpenFromInventory(Minecraft mc) {
        return mc.player != null && mc.player.containerMenu == mc.player.inventoryMenu;
    }
}
