package com.alonediamond.playercontrolpp.feature.automaterial.container;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.feature.automaterial.AutoMaterialGatherer.State;
import com.alonediamond.playercontrolpp.feature.automaterial.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.feature.automaterial.GatherContext;
import com.alonediamond.playercontrolpp.feature.automaterial.navigation.BaritonePathingController;
import com.alonediamond.playercontrolpp.util.PlayerUtil;

import net.minecraft.core.BlockPos;

/** 从缓存建立候选队列；实际满盒判定由打开后的取物选择器完成。 */
public final class ContainerSearcher {
    private final CachedContainerSource source = new CachedContainerSource();

    public void search(GatherContext ctx, AutoMaterialGatherer task, ContainerOpener opener, BaritonePathingController pathing) {
        BlockPos bound = ctx.boundLooseSource();
        if (bound != null) {
            if (ctx.countTargetHeld() >= ctx.targetTotal()) {
                task.onCurrentTargetSatisfied();
            } else if (ctx.boundLooseGap() == 0 || ctx.exhaustedPositions.contains(bound)
                    || !isWithinCacheRange(ctx, bound)) {
                task.onCurrentTargetUnavailable();
            } else {
                ctx.foundPositions.clear();
                ctx.foundPositions.add(bound);
                navigate(ctx, task, opener, pathing);
            }
            return;
        }
        if (PlayerUtil.isInventoryFull(ctx.client.player)) {
            task.onInventoryFull();
            return;
        }
        CachedContainerSource.Scope scope = source.scope(0);
        if (scope.error() != null) {
            task.setState(State.STOPPED, scope.error());
            return;
        }
        int gap = ctx.targetTotal() - ctx.countHeld(ctx.targetItem());
        if (gap <= 0) {
            task.onCurrentTargetSatisfied();
            return;
        }
        boolean fullFirst = gap > Configs.BaritoneSettings.SHULKER_BOX_PRIORITY_THRESHOLD.getIntegerValue();
        boolean includeBoxes = fullFirst || Configs.BaritoneSettings.RECOGNIZE_MIXED_SHULKER_BOX.getBooleanValue();
        ctx.foundPositions.clear();
        for (BlockPos pos : source.find(ctx.targetItem(), ctx.client.player.blockPosition(), scope.range(), fullFirst, includeBoxes)) {
            if (!ctx.exhaustedPositions.contains(pos)) ctx.foundPositions.add(pos);
        }
        if (ctx.foundPositions.isEmpty()) {
            task.onCurrentTargetUnavailable();
            return;
        }
        navigate(ctx, task, opener, pathing);
    }

    private boolean isWithinCacheRange(GatherContext ctx, BlockPos pos) {
        CachedContainerSource.Scope scope = source.scope(0);
        return scope.error() == null
                && pos.distSqr(ctx.client.player.blockPosition()) <= (long) scope.range() * scope.range();
    }

    private void navigate(GatherContext ctx, AutoMaterialGatherer task, ContainerOpener opener,
                          BaritonePathingController pathing) {
        ctx.currentPosIndex = 0;
        ctx.chestRetryCount = 0;
        ctx.adjacentContainerTargets = null;
        ctx.adjacentTryIndex = 0;
        ctx.adjacentOriginTarget = null;
        pathing.navigateTo(ctx.foundPositions.get(0), ctx, task, opener);
    }
}
