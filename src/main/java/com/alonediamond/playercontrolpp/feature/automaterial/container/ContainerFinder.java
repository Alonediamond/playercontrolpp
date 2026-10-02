package com.alonediamond.playercontrolpp.feature.automaterial.container;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.feature.automaterial.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.feature.automaterial.AutoMaterialGatherer.State;
import com.alonediamond.playercontrolpp.integration.ChestTrackerIntegration;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

public class ContainerFinder {
    ChestTrackerIntegration chestTracker;
    public ContainerFinder(ChestTrackerIntegration chestTracker) {
        this.chestTracker = chestTracker;
    }

    public void search(AutoMaterialGatherer amg) {
        try {
            if (PlayerUtil.isInventoryFull(amg.mc.player)) {
                amg.onInventoryFull();
                return;
            }
            int searchRange = chestTracker.getSearchRange();
            int listRange = chestTracker.getListRange();
            if (searchRange < 0 && listRange < 0) {
                MessageUtil.sendActionBar(amg.mc, "playercontrolpp.message.baritone.no_cache");
                amg.setState(State.STOPPED);
                return;
            }
            int effectiveRange = searchRange >= 0 ? searchRange : listRange;
            if (effectiveRange == Integer.MAX_VALUE) {
                MessageUtil.sendActionBar(amg.mc, "playercontrolpp.message.baritone.range_infinite");
                amg.setState(State.STOPPED);
                return;
            }

            Identifier currentDim = chestTracker.getCurrentDimensionKey();
            if (currentDim == null) {
                amg.setState(State.STOPPED);
                return;
            }

            BlockPos playerPos = amg.mc.player.blockPosition();
            amg.foundPositions.clear();
            amg.wholeBoxPriority = false;
            amg.mixedBoxMode = false;
            int stillNeeded = amg.targetNeededTotal - amg.currentlyGathered;
            int boxThreshold = Configs.BaritoneSettings.SHULKER_BOX_PRIORITY_THRESHOLD.getIntegerValue();
            if (stillNeeded > boxThreshold) {
                for (BlockPos pos : chestTracker.searchShulkerBoxWithItem(
                        amg.currentTargetItem, playerPos, effectiveRange)) {
                    // 排除表里的容器（已确认对本物品无货）不再入选，防止失效缓存造成死循环。
                    if (!amg.exhaustedPositions.contains(pos)) {
                        amg.foundPositions.add(pos);
                    }
                }
                amg.wholeBoxPriority = !amg.foundPositions.isEmpty();
            }

            // 散装容器照常追加在整盒容器后面；已经作为整盒来源的容器不重复排。
            for (BlockPos pos : chestTracker.searchItem(amg.currentTargetItem, playerPos, effectiveRange)) {
                if (!amg.foundPositions.contains(pos) && !amg.exhaustedPositions.contains(pos)) {
                    amg.foundPositions.add(pos);
                }
            }

            // 缺口没超过整盒阈值时，材料常常整盒躺在容器里——散装搜索一个都搜不到，
            // 而 searchItem() 只认散装物品堆，不会因为"盒里有"而返回这个容器。
            // 所以这里再按"装着该材料的潜影盒"查一遍，命中就转成"取盒→开盒取物→归还"。
            if (amg.foundPositions.isEmpty()
                    && stillNeeded <= boxThreshold
                    && Configs.BaritoneSettings.RECOGNIZE_MIXED_SHULKER_BOX.getBooleanValue()) {
                for (BlockPos pos : chestTracker.searchShulkerBoxWithItem(
                        amg.currentTargetItem, playerPos, effectiveRange)) {
                    if (!amg.exhaustedPositions.contains(pos)) {
                        amg.foundPositions.add(pos);
                    }
                }
                amg.mixedBoxMode = !amg.foundPositions.isEmpty();
            }

            if (amg.foundPositions.isEmpty()) {
                String itemName = BuiltInRegistries.ITEM.getKey(amg.currentTargetItem).toString();
                MessageUtil.sendActionBar(amg.mc,
                        "playercontrolpp.message.baritone.item_missing", itemName);
                // 顶层物品：这里可能会转入"追溯原材料并合成"，而不是直接跳过。
                //amg.onCurrentTargetUnavailable();
                return;
            }

            amg.currentPosIndex = 0;
            amg.chestRetryCount = 0;
            amg.adjacentContainerTargets = null;
            amg.adjacentTryIndex = 0;
            amg.adjacentOriginTarget = null;
            navigateToContainer(amg.foundPositions.get(0), amg);

        } catch (Exception e) {
            Playercontrolpp.LOGGER.warn("ChestTracker search failed for {}", amg.currentTargetItem, e);
            MessageUtil.sendActionBar(amg.mc,
                    "playercontrolpp.message.baritone.search_error", String.valueOf(e));
            //amg.onCurrentTargetUnavailable();
            amg.setState(State.NEXT_ITEM);
        }

    }
}
