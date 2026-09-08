package com.alonediamond.playercontrolpp.feature;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.HorseInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.player.Player;

/**
 * 容器 GUI 屏蔽开关：缓存容器的两个功能开箱时吞掉 {@code setScreen}，界面从不出现。
 *
 * <p>窗口是「租约」制：功能在自己 tick 里续租，停止/切世界时立即失效。这样既天然覆盖
 * 迟到的开箱回包（高延迟服务器上服务端回包可能晚于状态机超时），又保证功能异常退出后
 * 屏蔽最多再持续一个租约期就自愈，不会永久吞掉玩家的界面。
 *
 * <p>为什么不能简单 cancel 掉一切：吞掉 {@code setScreen} 意味着 Screen 对象从未创建，
 * ChestTracker 挂在 {@code ScreenEvents.remove} 上的落库钩子就不会触发——这正是
 * 自动缓存附近容器改用 {@code MemoryBuilder} 直写落库的原因（见功能内注释）。
 */
public final class ContainerGuiSuppressor {

    /** 单次续租的租约时长。功能每 tick 都会续，正常永远到不了期。 */
    private static final int LEASE_TICKS = 10;

    private static long validUntilMs;

    private ContainerGuiSuppressor() {}

    /** 功能活跃时每 tick 调用，维持屏蔽窗口。 */
    public static void renew() {
        validUntilMs = System.currentTimeMillis() + LEASE_TICKS * 50L;
    }

    /** 立即结束屏蔽（功能停止、世界切换、状态重置时调用）。 */
    public static void expire() {
        validUntilMs = 0;
    }

    /** @return 屏蔽窗口当前是否生效。 */
    public static boolean isActive() {
        return System.currentTimeMillis() < validUntilMs;
    }

    /**
     * @return 这个界面该不该被吞掉。只吞容器界面；玩家自己的各种背包界面
     *         （物品栏 / 创造模式 / 马背包）永远放行——按 E 开背包必须始终有反应。
     *         其余非容器界面（聊天栏、设置等）本来就不在吞的范围里。
     */
    public static boolean shouldSuppress(Screen screen) {
        if (!isActive() || screen == null) return false;
        if (screen instanceof InventoryScreen
                || screen instanceof CreativeModeInventoryScreen
                || screen instanceof HorseInventoryScreen) {
            return false;
        }

        Player player = Minecraft.getInstance().player;
        if (player == null) return false;
        return screen instanceof AbstractContainerScreen<?> container
                && container.getMenu() != player.inventoryMenu;
    }
}
