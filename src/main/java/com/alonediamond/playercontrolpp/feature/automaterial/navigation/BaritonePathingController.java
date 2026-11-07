package com.alonediamond.playercontrolpp.feature.automaterial.navigation;

import com.alonediamond.playercontrolpp.feature.automaterial.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.feature.automaterial.GatherContext;
import com.alonediamond.playercontrolpp.feature.automaterial.container.ContainerOpener;
import com.alonediamond.playercontrolpp.feature.automaterial.AutoMaterialGatherer.State;

import com.alonediamond.playercontrolpp.integration.BaritoneIntegration;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 用 Baritone 走到容器位置，并判断是「到了」、「卡住了」还是「根本没开始走」。
 */
public final class BaritonePathingController {

    /** 开始判断进度之前的宽限 tick 数。 */
    private static final int SETTLE_TICKS = 5;
    /** 寻路中连续多少 tick 没移动就放弃。 */
    private static final int STUCK_LIMIT_TICKS = 100;
    /** 等 Baritone 开始寻路的最长 tick 数，超了就放弃。 */
    private static final int START_TIMEOUT_TICKS = 40;
    /** 小于这个距离平方就算「没动过」（0.2 格）。 */
    private static final double MOVED_EPSILON_SQ = 0.04;

    private final BaritoneIntegration baritone;
    private Vec3 lastPlayerPos = Vec3.ZERO;
    private int stuckTicks;
    private int pathingTicks;
    private boolean pathingWasActive;
    private BlockPos currentPathTarget;

    public BaritonePathingController(BaritoneIntegration baritone) {
        this.baritone = baritone;
    }

    public void navigateTo(BlockPos pos, GatherContext ctx, AutoMaterialGatherer task, ContainerOpener opener) {
        if (canReach(ctx, pos)) {
            task.setState(State.OPENING_CONTAINER);
            opener.openContainerAt(pos, ctx);
        } else {
            task.setState(State.PATHING);
            startPathing(pos, ctx);
        }
    }

    private boolean canReach(GatherContext ctx, BlockPos pos) {
        return pos != null && ctx.client.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= PlayerUtil.blockReachSq(ctx.client.player);
    }

    public void startPathing(BlockPos target, GatherContext ctx) {
        currentPathTarget = target;
        pathingTicks = 0;
        stuckTicks = 0;
        pathingWasActive = false;
        lastPlayerPos = ctx.client.player != null ? ctx.client.player.position() : Vec3.ZERO;

        baritone.pathTo(target);
    }

    public void cancelPathing() {
        baritone.cancelPathing();
    }

    /** PATHING 状态下每 tick 调用；到达时转入 OPENING_CONTAINER。 */
    public void checkProgress(GatherContext ctx, AutoMaterialGatherer tsm, ContainerOpener opener) {
        if (ctx.client.player == null) return;

        pathingTicks++;
        if (canReach(ctx, currentPathTarget)) {
            cancelPathing();
            tsm.setState(State.OPENING_CONTAINER);
            opener.openContainerAt(currentPathTarget, ctx);
            return;
        }

        if (isInventoryFull(ctx.client)) {
            tsm.onInventoryFull();
            return;
        }

        if (!pathingWasActive && baritone.isPathing()) {
            pathingWasActive = true;
            stuckTicks = 0;
            lastPlayerPos = ctx.client.player.position();
        }

        if (pathingWasActive && pathingTicks > SETTLE_TICKS) {
            Vec3 currentPos = ctx.client.player.position();
            if (currentPos.distanceToSqr(lastPlayerPos) < MOVED_EPSILON_SQ) {
                stuckTicks++;
                if (stuckTicks >= STUCK_LIMIT_TICKS) {
                    tsm.setState(State.STOPPED, "playercontrolpp.message.baritone.pathing_stuck");
                    return;
                }
            } else {
                stuckTicks = 0;
            }
            lastPlayerPos = currentPos;
        }

        // 已开始过的寻路结束；上面已经检查过距离，这里处理未到达的情况。
        if (pathingWasActive && pathingTicks > SETTLE_TICKS && !baritone.isPathing()) {
            stuckTicks = 0;
            lastPlayerPos = Vec3.ZERO;
            pathingTicks = 0;
            pathingWasActive = false;

            // 寻路结束不代表到达，距离仍然超限就跳过当前容器。
            ctx.exhaustedPositions.add(currentPathTarget);
            tsm.setState(State.SEARCHING);
            return;
        }

        if (!pathingWasActive && pathingTicks > START_TIMEOUT_TICKS) {
            tsm.setState(State.FAILED, "playercontrolpp.message.baritone.pathing_not_started");
        }
    }

    private boolean isInventoryFull(Minecraft mc) {
        return PlayerUtil.isInventoryFull(mc.player);
    }
}
