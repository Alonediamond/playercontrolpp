package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.compat.PlayerCompat;

import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer.State;
import com.alonediamond.playercontrolpp.feature.ItemTransferStrategy;
import com.alonediamond.playercontrolpp.integration.ChestTrackerIntegration;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;

/**
 * Encapsulates ChestTracker query logic for finding container positions.
 * Also handles navigation routing (nearby → open, far → path).
 */
public class ContainerSearcher {

    private final ChestTrackerIntegration chestTracker;

    public ContainerSearcher(ChestTrackerIntegration chestTracker) {
        this.chestTracker = chestTracker;
    }

    /**
     * Perform ChestTracker search for the current target item.
     * On success, routes to navigation via the TaskStateMachine.
     */
    public void search(GatherContext ctx, TaskStateMachine tsm,
                        ContainerOpener opener, BaritonePathingController pathing) {
        try {
            if (isInventoryFull(ctx.client)) {
                tsm.onInventoryFull();
                return;
            }

            int searchRange = chestTracker.getSearchRange();
            if (searchRange == Integer.MAX_VALUE) {
                MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.range_infinite");
                tsm.setState(State.STOPPED);
                return;
            }
            int listRange = chestTracker.getListRange();
            if (listRange == Integer.MAX_VALUE) {
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
            ctx.foundPositions.addAll(chestTracker.searchItem(ctx.currentTargetItem, playerPos, effectiveRange));

            if (ctx.foundPositions.isEmpty()) {
                String itemName = BuiltInRegistries.ITEM.getKey(ctx.currentTargetItem).toString();
                String msg = StringUtils.translate("playercontrolpp.message.baritone.item_missing", itemName);
                PlayerCompat.sendOverlayMessage(ctx.client.player, Component.nullToEmpty(msg));
                tsm.skipCurrentItem();
                return;
            }

            ctx.currentPosIndex = 0;
            ctx.chestRetryCount = 0;
            ctx.adjacentContainerTargets = null;
            ctx.adjacentTryIndex = 0;
            navigateToContainer(ctx.foundPositions.get(0), ctx, tsm, opener, pathing);

        } catch (Exception e) {
            String msg = StringUtils.translate("playercontrolpp.message.baritone.search_error", e.getMessage());
            PlayerCompat.sendOverlayMessage(ctx.client.player, Component.nullToEmpty(msg));
            tsm.skipCurrentItem();
        }
    }

    private void navigateToContainer(BlockPos pos, GatherContext ctx, TaskStateMachine tsm,
                                     ContainerOpener opener, BaritonePathingController pathing) {
        if (isPlayerNearPosition(pos, 5.0, ctx)) {
            tsm.setState(State.OPENING_CONTAINER);
            opener.openContainerAt(pos, ctx);
        } else {
            tsm.setState(State.PATHING);
            pathing.startPathing(pos, ctx);
        }
    }

    private boolean isPlayerNearPosition(BlockPos pos, double maxDist, GatherContext ctx) {
        if (ctx.client.player == null) return false;
        return ctx.client.player.blockPosition().distSqr(pos) <= maxDist * maxDist;
    }

    private boolean isInventoryFull(net.minecraft.client.Minecraft mc) {
        if (mc.player == null) return true;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
