package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer.State;
import com.alonediamond.playercontrolpp.integration.BaritoneIntegration;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.Vec3;

/**
 * 用 Baritone 走到容器位置，并判断是「到了」、「卡住了」还是「根本没开始走」。
 */
public class BaritonePathingController {

    /** 开始判断进度之前的宽限 tick 数。 */
    private static final int SETTLE_TICKS = 5;
    /** 寻路中连续多少 tick 没移动就放弃。 */
    private static final int STUCK_LIMIT_TICKS = 100;
    /** 等 Baritone 开始寻路的最长 tick 数，超了就放弃。 */
    private static final int START_TIMEOUT_TICKS = 40;
    /** 小于这个距离平方就算「没动过」（0.2 格）。 */
    private static final double MOVED_EPSILON_SQ = 0.04;

    private final BaritoneIntegration baritone;

    public BaritonePathingController(BaritoneIntegration baritone) {
        this.baritone = baritone;
    }

    public void startPathing(BlockPos target, GatherContext ctx) {
        ctx.currentPathTarget = target;
        ctx.pathingTicks = 0;
        ctx.stuckTicks = 0;
        ctx.pathingWasActive = false;
        ctx.lastPlayerPos = ctx.client.player != null ? ctx.client.player.position() : Vec3.ZERO;

        baritone.pathTo(target);
    }

    public void cancelPathing() {
        baritone.cancelPathing();
    }

    /** PATHING 状态下每 tick 调用；到达时转入 OPENING_CONTAINER。 */
    public void checkProgress(GatherContext ctx, TaskStateMachine tsm, ContainerOpener opener) {
        if (ctx.client.player == null) return;

        ctx.pathingTicks++;

        if (isInventoryFull(ctx.client)) {
            tsm.onInventoryFull();
            return;
        }

        if (!ctx.pathingWasActive && baritone.isPathing()) {
            ctx.pathingWasActive = true;
            ctx.stuckTicks = 0;
            ctx.lastPlayerPos = ctx.client.player.position();
        }

        if (ctx.pathingWasActive && ctx.pathingTicks > SETTLE_TICKS) {
            Vec3 currentPos = ctx.client.player.position();
            if (currentPos.distanceToSqr(ctx.lastPlayerPos) < MOVED_EPSILON_SQ) {
                ctx.stuckTicks++;
                if (ctx.stuckTicks >= STUCK_LIMIT_TICKS) {
                    tsm.setState(State.FAILED, "playercontrolpp.message.baritone.pathing_stuck");
                    return;
                }
            } else {
                ctx.stuckTicks = 0;
            }
            ctx.lastPlayerPos = currentPos;
        }

        // Baritone 确实开始过、现在又停了：说明已经到了。
        if (ctx.pathingWasActive && ctx.pathingTicks > SETTLE_TICKS && !baritone.isPathing()) {
            ctx.stuckTicks = 0;
            ctx.lastPlayerPos = Vec3.ZERO;
            ctx.pathingTicks = 0;
            ctx.pathingWasActive = false;

            if (ctx.currentPosIndex < ctx.foundPositions.size()) {
                tsm.setState(State.OPENING_CONTAINER);
                opener.openContainerAt(ctx.foundPositions.get(ctx.currentPosIndex), ctx);
            } else {
                tsm.skipCurrentItem();
            }
            return;
        }

        if (!ctx.pathingWasActive && ctx.pathingTicks > START_TIMEOUT_TICKS) {
            tsm.setState(State.FAILED, "playercontrolpp.message.baritone.pathing_not_started");
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
