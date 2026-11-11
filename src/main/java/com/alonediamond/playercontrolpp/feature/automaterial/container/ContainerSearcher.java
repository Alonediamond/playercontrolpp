package com.alonediamond.playercontrolpp.feature.automaterial.container;

import com.alonediamond.playercontrolpp.feature.automaterial.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.feature.automaterial.GatherContext;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialItemEntry;
import com.alonediamond.playercontrolpp.feature.automaterial.navigation.BaritonePathingController;
import com.alonediamond.playercontrolpp.feature.automaterial.AutoMaterialGatherer.State;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从缓存建立候选容器队列。
 *
 * <p>候选不是"只有当前这一件材料的箱子"，而是<b>所有缺失材料</b>的箱子合并去重：
 * 一个箱子里同时装着石头和泥土时只会被访问一次，开箱后
 * {@link ContainerMaterialCollector} 会把里面所有还需要的东西按缺口一次拿走。
 * 这与「一个一个材料取」的旧行为相比，省掉了同一个箱子被反复开合的往返。
 *
 * <p>实际满盒判定仍由打开后的取物选择器完成——缓存只说明"这里可能有什么"。
 */
public final class ContainerSearcher {
    private final CachedContainerSource source = new CachedContainerSource();

    public void search(GatherContext ctx, AutoMaterialGatherer task, ContainerOpener opener, BaritonePathingController pathing) {
        if (PlayerUtil.isInventoryFull(ctx.client.player)) { task.onInventoryFull(); return; }
        var scope = source.scope(0);
        if (scope.error() != null) { task.setState(State.STOPPED, scope.error()); return; }

        // 还有哪几件东西真的缺：当前目标 + 清单上所有仍有缺口的条目。
        List<MaterialItemEntry> wanted = new ArrayList<>();
        for (MaterialItemEntry entry : ctx.wantedEntries()) {
            int gap = entry.neededCount - ctx.countHeld(entry.item);
            if (gap > 0) wanted.add(entry);
        }
        if (wanted.isEmpty()) { task.onCurrentTargetSatisfied(); return; }

        BlockPos origin = ctx.client.player.blockPosition();
        Set<BlockPos> positions = new LinkedHashSet<>();
        for (MaterialItemEntry entry : wanted) {
            // 每种材料按自己的缺口决定是否把满盒排在前面。
            boolean fullFirst = entry.neededCount - ctx.countHeld(entry.item)
                    > Configs.BaritoneSettings.SHULKER_BOX_PRIORITY_THRESHOLD.getIntegerValue();
            boolean includeBoxes = fullFirst
                    || Configs.BaritoneSettings.RECOGNIZE_MIXED_SHULKER_BOX.getBooleanValue();
            positions.addAll(source.find(entry.item, origin, scope.range(), fullFirst, includeBoxes));
        }

        ctx.foundPositions.clear();
        for (BlockPos pos : positions) {
            // drained：本轮已经把它取空了；exhausted：这一件物品在它里面确认没货。
            if (ctx.drainedContainers.contains(pos) || ctx.exhaustedPositions.contains(pos)) continue;
            ctx.foundPositions.add(pos);
        }
        // 合并之后顺序不再是"按某一件材料由近到远"，重新按距离排一遍，先处理顺路的箱子。
        ctx.foundPositions.sort(Comparator.comparingDouble(pos -> pos.distSqr(origin)));

        if (ctx.foundPositions.isEmpty()) { task.onCurrentTargetUnavailable(); return; }
        ctx.currentPosIndex = 0;
        ctx.chestRetryCount = 0;
        ctx.adjacentContainerTargets = null;
        ctx.adjacentTryIndex = 0;
        ctx.adjacentOriginTarget = null;
        pathing.navigateTo(ctx.foundPositions.get(0), ctx, task, opener);
    }
}
