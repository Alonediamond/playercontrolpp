package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.config.Configs;
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
            //
            // 用 searchRange：箱子追踪自己的搜索用的就是这个设置。早先取两者较小值，
            // 会出现"箱子追踪界面能高亮出这个容器、本模组却报找不到"的落差
            // （itemListRange 管的是它的物品列表，通常配得比搜索范围大）。
            // searchRange 不可用时才退回 itemListRange。
            int searchRange = chestTracker.getSearchRange();
            int listRange = chestTracker.getListRange();
            if (searchRange < 0 && listRange < 0) {
                MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.no_cache");
                tsm.setState(State.STOPPED);
                return;
            }
            int effectiveRange = searchRange >= 0 ? searchRange : listRange;
            if (effectiveRange == Integer.MAX_VALUE) {
                MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.range_infinite");
                tsm.setState(State.STOPPED);
                return;
            }

            Identifier currentDim = chestTracker.getCurrentDimensionKey();
            if (currentDim == null) {
                tsm.setState(State.STOPPED);
                return;
            }

            BlockPos playerPos = ctx.client.player.blockPosition();

            // 缺口超过阈值时先查缓存里有没有装着该材料的整盒；有就优先去这些容器搬整盒。
            // 没有整盒时 wholeBoxPriority 保持 false，走原来的纯散装路线。
            ctx.foundPositions.clear();
            ctx.wholeBoxPriority = false;
            ctx.mixedBoxMode = false;
            int stillNeeded = ctx.targetNeededTotal - ctx.currentlyGathered;
            int boxThreshold = Configs.BaritoneSettings.SHULKER_BOX_PRIORITY_THRESHOLD.getIntegerValue();
            if (stillNeeded > boxThreshold) {
                for (BlockPos pos : chestTracker.searchShulkerBoxWithItem(
                        ctx.currentTargetItem, playerPos, effectiveRange)) {
                    // 排除表里的容器（已确认对本物品无货）不再入选，防止失效缓存造成死循环。
                    if (!ctx.exhaustedPositions.contains(pos)) {
                        ctx.foundPositions.add(pos);
                    }
                }
                ctx.wholeBoxPriority = !ctx.foundPositions.isEmpty();
            }

            // 散装容器照常追加在整盒容器后面；已经作为整盒来源的容器不重复排。
            for (BlockPos pos : chestTracker.searchItem(ctx.currentTargetItem, playerPos, effectiveRange)) {
                if (!ctx.foundPositions.contains(pos) && !ctx.exhaustedPositions.contains(pos)) {
                    ctx.foundPositions.add(pos);
                }
            }

            // 缺口没超过整盒阈值时，材料常常整盒躺在容器里——散装搜索一个都搜不到，
            // 而 searchItem() 只认散装物品堆，不会因为"盒里有"而返回这个容器。
            // 所以这里再按"装着该材料的潜影盒"查一遍，命中就转成"取盒→开盒取物→归还"。
            if (ctx.foundPositions.isEmpty()
                    && stillNeeded <= boxThreshold
                    && Configs.BaritoneSettings.RECOGNIZE_MIXED_SHULKER_BOX.getBooleanValue()) {
                for (BlockPos pos : chestTracker.searchShulkerBoxWithItem(
                        ctx.currentTargetItem, playerPos, effectiveRange)) {
                    if (!ctx.exhaustedPositions.contains(pos)) {
                        ctx.foundPositions.add(pos);
                    }
                }
                ctx.mixedBoxMode = !ctx.foundPositions.isEmpty();
            }

            if (ctx.foundPositions.isEmpty()) {
                String itemName = BuiltInRegistries.ITEM.getKey(ctx.currentTargetItem).toString();
                MessageUtil.sendActionBar(ctx.client,
                        "playercontrolpp.message.baritone.item_missing", itemName);
                // 顶层物品：这里可能会转入"追溯原材料并合成"，而不是直接跳过。
                tsm.onCurrentTargetUnavailable();
                return;
            }

            ctx.currentPosIndex = 0;
            ctx.chestRetryCount = 0;
            ctx.adjacentContainerTargets = null;
            ctx.adjacentTryIndex = 0;
            ctx.adjacentOriginTarget = null;
            navigateToContainer(ctx.foundPositions.get(0), ctx, tsm, opener, pathing);

        } catch (Exception e) {
            Playercontrolpp.LOGGER.warn("ChestTracker search failed for {}", ctx.currentTargetItem, e);
            MessageUtil.sendActionBar(ctx.client,
                    "playercontrolpp.message.baritone.search_error", String.valueOf(e));
            tsm.onCurrentTargetUnavailable();
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
        return PlayerUtil.isInventoryFull(mc.player);
    }
}
