package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer.State;
import com.alonediamond.playercontrolpp.integration.ChestTrackerIntegration;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;

/**
 * 问箱子追踪当前目标物品在哪，然后根据它是否已在手长范围内，决定直接开容器还是把坐标交给 Baritone。
 */
public class ContainerSearcher {

    private final ChestTrackerIntegration chestTracker;

    public ContainerSearcher(ChestTrackerIntegration chestTracker) {
        this.chestTracker = chestTracker;
    }

    /** 为当前目标跑一次箱子追踪查询，并前往第一个结果。 */
    public void search(GatherContext ctx, TaskStateMachine tsm,
                        ContainerOpener opener, BaritonePathingController pathing) {
        try {
            if (isInventoryFull(ctx.client)) {
                tsm.onInventoryFull();
                return;
            }

            // 范围设为无限会让箱子追踪把整个记忆库走一遍；宁可拒绝，也不要卡死客户端。
            int searchRange = chestTracker.getSearchRange();
            int listRange = chestTracker.getListRange();
            if (searchRange == Integer.MAX_VALUE || listRange == Integer.MAX_VALUE) {
                MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.range_infinite");
                tsm.setState(State.STOPPED);
                return;
            }
            if (searchRange < 0 || listRange < 0) {
                MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.no_cache");
                tsm.setState(State.STOPPED);
                return;
            }
            int effectiveRange = Math.min(searchRange, listRange);

            Identifier currentDim = chestTracker.getCurrentDimensionKey();
            if (currentDim == null) {
                tsm.setState(State.STOPPED);
                return;
            }

            BlockPos playerPos = ctx.client.player.blockPosition();
            ctx.foundPositions.clear();
            ctx.foundPositions.addAll(
                    chestTracker.searchItem(ctx.currentTargetItem, playerPos, effectiveRange));

            if (ctx.foundPositions.isEmpty()) {
                String itemName = BuiltInRegistries.ITEM.getKey(ctx.currentTargetItem).toString();
                MessageUtil.sendActionBar(ctx.client,
                        "playercontrolpp.message.baritone.item_missing", itemName);
                tsm.skipCurrentItem();
                return;
            }

            ctx.currentPosIndex = 0;
            ctx.chestRetryCount = 0;
            ctx.adjacentContainerTargets = null;
            ctx.adjacentTryIndex = 0;
            navigateToContainer(ctx.foundPositions.get(0), ctx, tsm, opener, pathing);

        } catch (Exception e) {
            Playercontrolpp.LOGGER.warn("ChestTracker search failed for {}", ctx.currentTargetItem, e);
            MessageUtil.sendActionBar(ctx.client,
                    "playercontrolpp.message.baritone.search_error", String.valueOf(e));
            tsm.skipCurrentItem();
        }
    }

    private void navigateToContainer(BlockPos pos, GatherContext ctx, TaskStateMachine tsm,
                                     ContainerOpener opener, BaritonePathingController pathing) {
        if (ctx.client.player == null) return;
        if (ctx.client.player.blockPosition().distSqr(pos) <= PlayerUtil.blockReachSq(ctx.client.player)) {
            tsm.setState(State.OPENING_CONTAINER);
            opener.openContainerAt(pos, ctx);
        } else {
            tsm.setState(State.PATHING);
            pathing.startPathing(pos, ctx);
        }
    }

    private boolean isInventoryFull(Minecraft mc) {
        if (mc.player == null) return true;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
