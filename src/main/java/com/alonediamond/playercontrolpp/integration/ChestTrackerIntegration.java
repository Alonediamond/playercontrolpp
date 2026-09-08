package com.alonediamond.playercontrolpp.integration;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.Item;

import java.util.Collections;
import java.util.List;

/**
 * ChestTracker 联动的默认实现（stub）。真正的方法体在
 * {@code mixin/compat/chesttracker/ChestTrackerIntegrationImpl} 里，机制同 {@link LitematicaIntegration}。
 */
public class ChestTrackerIntegration {

    private static final ChestTrackerIntegration INSTANCE = new ChestTrackerIntegration();

    private ChestTrackerIntegration() {}

    public static ChestTrackerIntegration getInstance() { return INSTANCE; }

    /** Mixin 注入成功时覆写为 {@code true}；未注入即联动未生效。 */
    public boolean isLoaded() { return false; }

    /** @return 箱子追踪设置里的搜索范围；未加载时 -1。 */
    public int getSearchRange() { return -1; }

    /** @return 箱子追踪设置里的物品列表范围；未加载时 -1。 */
    public int getListRange() { return -1; }

    /** @return 供箱子追踪查询用的当前维度 key。 */
    public Identifier getCurrentDimensionKey() { return null; }

    /** @return 是否存在已加载的缓存库。 */
    public boolean hasLoadedMemoryBank() { return false; }

    /** 在箱子追踪的记忆里搜含有目标物品的容器坐标，按距玩家由近到远返回。 */
    public List<BlockPos> searchItem(Item targetItem, BlockPos playerPos, int effectiveRange) {
        return Collections.emptyList();
    }

    /** 在箱子追踪的记忆里搜「装着目标物品的整盒（潜影盒）」所在的容器坐标，按距玩家由近到远返回。 */
    public List<BlockPos> searchShulkerBoxWithItem(Item targetItem, BlockPos playerPos, int effectiveRange) {
        return Collections.emptyList();
    }

    /** 把一个已同步的容器菜单直接写进箱子追踪当前加载的缓存库，不另建缓存库。 */
    public boolean cacheContainer(
            Level level,
            BlockPos position,
            BlockState state,
            AbstractContainerMenu menu) {
        return false;
    }

    /** 清掉箱子追踪的交互记录，避免它在关界面时用一条过期的交互重复落库。 */
    public void clearInteractionTracker() {}
}
