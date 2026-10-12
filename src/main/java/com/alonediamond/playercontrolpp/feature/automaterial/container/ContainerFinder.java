package com.alonediamond.playercontrolpp.feature.automaterial.container;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.feature.automaterial.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.feature.automaterial.AutoMaterialGatherer.State;
import com.alonediamond.playercontrolpp.feature.automaterial.GatherContext;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialItemEntry;
import com.alonediamond.playercontrolpp.feature.automaterial.util.InteractionUtil;
import com.alonediamond.playercontrolpp.integration.ChestTrackerIntegration;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

public class ContainerFinder {
    private final ChestTrackerIntegration chestTracker;
    private final GatherContext ctx;
    public ContainerFinder(ChestTrackerIntegration chestTracker, GatherContext ctx) {
        this.chestTracker = chestTracker;
        this.ctx = ctx;
    }

    public void search(AutoMaterialGatherer amg) {
        try {
            if (PlayerUtil.isInventoryFull(amg.mc.player)) {
                amg.onInventoryFull();
                return;
            }

            if (ctx.missingItems.isEmpty()) {
                MessageUtil.sendActionBar(amg.mc, "playercontrolpp.message.baritone.all_materials_ready");
                amg.setState(State.COMPLETED);
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
            ctx.foundBoxPositions.clear();
            ctx.foundItemPositions.clear();

            MaterialItemEntry currItem = ctx.missingItems.getFirst();

            //盒装先
            for (BlockPos pos : chestTracker.searchShulkerBoxWithItem(
                    currItem.item, playerPos, effectiveRange)) {
                ctx.foundBoxPositions.add(pos);
            }

            //散装
            for (BlockPos pos : chestTracker.searchItem(currItem.item, playerPos, effectiveRange)) {
                ctx.foundItemPositions.add(pos);
            }


            boolean isNoBox = ctx.foundBoxPositions.isEmpty();
            if (ctx.foundItemPositions.isEmpty() && isNoBox) {
                String itemName = BuiltInRegistries.ITEM.getKey(currItem.item).toString();
                MessageUtil.sendActionBar(amg.mc,
                        "playercontrolpp.message.baritone.item_missing", itemName);
                // 顶层物品：这里可能会转入"追溯原材料并合成"，而不是直接跳过。
                //amg.onCurrentTargetUnavailable();
                return;
            }

            int stillNeeded = currItem.neededCount;
            int boxThreshold = Configs.BaritoneSettings.SHULKER_BOX_PRIORITY_THRESHOLD.getIntegerValue();

            if (stillNeeded > boxThreshold) {
                if (isNoBox){
                    if (!ctx.foundItemPositions.isEmpty()){
                        navigateToContainer(ctx.foundItemPositions.getFirst(), amg);
                        ctx.currentContainerPos = ctx.foundItemPositions.getFirst();
                        return;
                    }
                } else {
                    navigateToContainer(ctx.foundBoxPositions.getFirst(), amg);
                    ctx.currentContainerPos = ctx.foundBoxPositions.getFirst();
                    return;
                }
                return;
            }
            if (!ctx.foundItemPositions.isEmpty()){
                navigateToContainer(ctx.foundItemPositions.getFirst(), amg);
                ctx.currentContainerPos = ctx.foundItemPositions.getFirst();
                return;
            }else {
                if (!isNoBox && Configs.BaritoneSettings.RECOGNIZE_MIXED_SHULKER_BOX.getBooleanValue()){
                    navigateToContainer(ctx.foundBoxPositions.getFirst(), amg);
                    return;
                }
            }
            ctx.missingItems.removeFirst();
        } catch (Exception e) {
            MessageUtil.sendActionBar(amg.mc,
                    "playercontrolpp.message.baritone.search_error", String.valueOf(e));
            //amg.onCurrentTargetUnavailable();
            ctx.missingItems.removeFirst();
            ctx.currentContainerPos = null;
            amg.setState(State.FAILED);
            amg.stop();
        }
    }

    private void navigateToContainer(BlockPos pos, AutoMaterialGatherer amg) {
        if (amg.mc.player == null) return;
        if (amg.mc.player.blockPosition().distSqr(pos) <= PlayerUtil.blockReachSq(amg.mc.player)) {
            InteractionUtil.openContainerAt(pos,amg.mc);
            amg.setState(State.OPENING_CONTAINER);
        } else {
            amg.startPathing(pos);
            amg.setState(State.PATHING);
        }
    }
}
