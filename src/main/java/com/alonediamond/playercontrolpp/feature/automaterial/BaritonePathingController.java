package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer.State;
import com.alonediamond.playercontrolpp.integration.BaritoneIntegration;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Encapsulates Baritone pathing logic for navigating to container positions.
 */
public class BaritonePathingController {

    private final BaritoneIntegration baritone;

    public BaritonePathingController(BaritoneIntegration baritone) {
        this.baritone = baritone;
    }

    public void startPathing(BlockPos target, GatherContext ctx) {
        ctx.currentPathTarget = target;
        ctx.pathingTicks = 0;
        ctx.stuckTicks = 0;
        ctx.pathingWasActive = false;
        ctx.lastPlayerPos = ctx.client.player != null ? new Vec3(ctx.client.player.getX(), ctx.client.player.getY(), ctx.client.player.getZ()) : Vec3.ZERO;

        baritone.pathTo(target);
    }

    public void cancelPathing() {
        baritone.cancelPathing();
    }

    /**
     * Check pathing progress — called each tick during PATHING state.
     * When pathing completes, transitions to OPENING_CONTAINER.
     */
    public void checkProgress(GatherContext ctx, TaskStateMachine tsm, ContainerOpener opener) {
        if (ctx.client.player == null) return;

        ctx.pathingTicks++;

        if (isInventoryFull(ctx.client)) {
            tsm.onInventoryFull();
            return;
        }

        if (!ctx.pathingWasActive) {
            if (baritone.isPathing()) {
                ctx.pathingWasActive = true;
                ctx.stuckTicks = 0;
                ctx.lastPlayerPos = new Vec3(ctx.client.player.getX(), ctx.client.player.getY(), ctx.client.player.getZ());
            }
        }

        // Stuck detection: player not moved for 5 seconds
        if (ctx.pathingWasActive && ctx.pathingTicks > 5) {
            Vec3 currentPos = new Vec3(ctx.client.player.getX(), ctx.client.player.getY(), ctx.client.player.getZ());
            double moved = currentPos.distanceToSqr(ctx.lastPlayerPos);
            if (moved < 0.04) {
                ctx.stuckTicks++;
                if (ctx.stuckTicks >= 100) {
                    tsm.setState(State.FAILED);
                    return;
                }
            } else {
                ctx.stuckTicks = 0;
            }
            ctx.lastPlayerPos = currentPos;
        }

        // Check if Baritone has reached destination
        if (ctx.pathingWasActive && ctx.pathingTicks > 5 && !baritone.isPathing()) {
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
        }

        // Timeout: if pathing never started after 40 ticks
        if (!ctx.pathingWasActive && ctx.pathingTicks > 40) {
            tsm.setState(State.FAILED);
        }
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
