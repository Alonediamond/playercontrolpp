package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer.State;
import com.alonediamond.playercontrolpp.integration.BaritoneIntegration;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.Vec3;

/**
 * Navigates to container positions with Baritone, and decides when it has arrived, stalled, or
 * never got going at all.
 */
public class BaritonePathingController {

    /** Grace period before progress is judged at all, in ticks. */
    private static final int SETTLE_TICKS = 5;
    /** Ticks of no movement while pathing before giving up. */
    private static final int STUCK_LIMIT_TICKS = 100;
    /** Ticks to wait for Baritone to start pathing at all before giving up. */
    private static final int START_TIMEOUT_TICKS = 40;
    /** Squared distance under which the player counts as not having moved (0.2 blocks). */
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

    /** Called every tick in the PATHING state; transitions to OPENING_CONTAINER on arrival. */
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

        // Baritone stopped pathing after actually having started: we have arrived.
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
