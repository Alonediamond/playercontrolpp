package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.compat.ScreenCompat;
import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer.State;
import com.alonediamond.playercontrolpp.input.SimulatedInput;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Opens containers: aiming, retries, and falling back to adjacent blocks when the target
 * turns out to be the wrong half of a double chest.
 */
public class ContainerOpener {

    /** Attempts at the same block before moving on to its neighbours. */
    private static final int MAX_OPEN_ATTEMPTS = 3;
    /** On which attempt to try jumping first, in case the line of sight is blocked. */
    private static final int JUMP_ON_ATTEMPT = 2;
    /** Distinct blocks tried before giving up on this container position entirely. */
    private static final int MAX_CHEST_RETRIES = 3;
    /** Ticks to wait for the container screen after clicking. */
    private static final int OPEN_WAIT_TICKS = 10;
    /** Shorter wait used by the right-click fallback, which reacts faster. */
    private static final int FALLBACK_WAIT_TICKS = 6;

    /** Open the container at {@code target}. Entry point from the OPENING_CONTAINER transition. */
    public void openContainerAt(BlockPos target, GatherContext ctx) {
        openContainerWithRetry(target, false, 0, ctx);
    }

    /**
     * Opens a container by sending an explicit BlockHitResult through {@code useItemOn()},
     * bypassing client-side raycasting so an adjacent container cannot steal the click.
     */
    public void openContainerWithRetry(BlockPos target, boolean jumpBeforeClick, int attemptNumber, GatherContext ctx) {
        ctx.currentContainerTarget = target;
        ctx.openAttemptCount = attemptNumber;

        // Any key held by a previous attempt's fallback path is stale now.
        releaseKeys();

        if (jumpBeforeClick && ctx.client.player != null) {
            ctx.client.player.jumpFromGround();
        }

        try {
            Vec3 playerEye = ctx.client.player.getEyePosition();
            double dx = target.getX() + 0.5 - playerEye.x;
            double dy = target.getY() + 0.5 - playerEye.y;
            double dz = target.getZ() + 0.5 - playerEye.z;
            double distH = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) Math.toDegrees(-Math.atan2(dy, distH));
            ctx.client.player.setYRot(yaw);
            ctx.client.player.setYHeadRot(yaw);
            ctx.client.player.setXRot(pitch);

            Direction face = getNearestContainerFace(target, ctx);
            Vec3 hitPos = new Vec3(
                    target.getX() + 0.5 + face.getStepX() * 0.5,
                    target.getY() + 0.5 + face.getStepY() * 0.5,
                    target.getZ() + 0.5 + face.getStepZ() * 0.5
            );

            BlockHitResult hitResult = new BlockHitResult(hitPos, face, target, false);
            ctx.client.gameMode.useItemOn(ctx.client.player, InteractionHand.MAIN_HAND, hitResult);

            ctx.transferCooldown = OPEN_WAIT_TICKS;
            ctx.containerJustOpened = true;

        } catch (Exception e) {
            // useItemOn failed outright — fall back to letting vanilla's own raycast do it by
            // holding right-click. The hold is registered with SimulatedInput so it is released
            // when this opener stops, instead of leaving right-click stuck on.
            SimulatedInput.hold(ctx.client.options.keyUse, this);
            ctx.transferCooldown = FALLBACK_WAIT_TICKS;
            ctx.containerJustOpened = true;
        }
    }

    /** Drop every key this opener is holding. Called from the state machine's terminal states. */
    public void releaseKeys() {
        SimulatedInput.releaseAll(this);
    }

    /**
     * Runs when the open cooldown expires: did a container screen appear, and does it hold
     * anything we still need?
     */
    public void checkOpenResult(GatherContext ctx, TaskStateMachine tsm, BaritonePathingController pathing) {
        Minecraft mc = ctx.client;

        // The click has resolved one way or the other; stop holding right-click either way.
        releaseKeys();

        if (ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen<?>) {
            if (containerHasAnyMissingItem(ctx)) {
                tsm.setState(State.TRANSFERRING_ITEM);
                ctx.transferCooldown = 4;
                ctx.openAttemptCount = 0;
            } else {
                mc.player.closeContainer();
                ctx.transferCooldown = 8;
                retryAdjacentOrFail(ctx, tsm, pathing);
            }
        } else {
            ctx.openAttemptCount++;
            if (ctx.openAttemptCount < MAX_OPEN_ATTEMPTS) {
                boolean jump = (ctx.openAttemptCount == JUMP_ON_ATTEMPT);
                openContainerWithRetry(ctx.currentContainerTarget, jump, ctx.openAttemptCount, ctx);
            } else {
                retryAdjacentOrFail(ctx, tsm, pathing);
            }
        }
    }

    /** Called when the current target will not open, or opened but held nothing useful. */
    public void retryAdjacentOrFail(GatherContext ctx, TaskStateMachine tsm,
                                     BaritonePathingController pathing) {
        if (ctx.adjacentContainerTargets == null) {
            ctx.adjacentContainerTargets = getAdjacentContainerTargets(ctx.currentContainerTarget);
            ctx.adjacentTryIndex = 0;
        }

        if (ctx.adjacentTryIndex < ctx.adjacentContainerTargets.size()) {
            BlockPos adjPos = ctx.adjacentContainerTargets.get(ctx.adjacentTryIndex);
            ctx.adjacentTryIndex++;
            ctx.openAttemptCount = 0;
            openContainerAt(adjPos, ctx);
            return;
        }

        ctx.adjacentContainerTargets = null;
        ctx.adjacentTryIndex = 0;
        ctx.openAttemptCount = 0;
        ctx.chestRetryCount++;
        if (ctx.chestRetryCount >= MAX_CHEST_RETRIES) {
            ctx.chestRetryCount = 0;
            ctx.currentPosIndex++;
            if (ctx.currentPosIndex >= ctx.foundPositions.size()) {
                tsm.skipCurrentItem();
            } else {
                navigateToNextContainer(ctx.foundPositions.get(ctx.currentPosIndex), ctx, tsm, pathing);
            }
        } else {
            tsm.setState(State.SEARCHING);
        }
    }

    private void navigateToNextContainer(BlockPos pos, GatherContext ctx, TaskStateMachine tsm,
                                         BaritonePathingController pathing) {
        if (ctx.client.player == null) return;
        if (ctx.client.player.blockPosition().distSqr(pos) <= PlayerUtil.blockReachSq(ctx.client.player)) {
            tsm.setState(State.OPENING_CONTAINER);
            openContainerAt(pos, ctx);
        } else {
            tsm.setState(State.PATHING);
            pathing.startPathing(pos, ctx);
        }
    }

    /** Close whatever container screen is open. */
    public void closeAnyContainer(Minecraft mc) {
        if (mc.player != null && ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen) {
            mc.player.closeContainer();
        }
    }

    private Direction getNearestContainerFace(BlockPos target, GatherContext ctx) {
        if (ctx.client.player == null) return Direction.UP;
        return nearestFace(ctx.client.player.getEyePosition(), target);
    }

    /** @return the face of {@code target} pointing most directly at {@code eye}. */
    static Direction nearestFace(Vec3 eye, BlockPos target) {
        Vec3 center = Vec3.atCenterOf(target);
        double dx = eye.x - center.x;
        double dy = eye.y - center.y;
        double dz = eye.z - center.z;

        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);

        if (ax >= ay && ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
        if (ay >= ax && ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** The target itself plus its six neighbours — covers double chests and slight mis-aims. */
    private List<BlockPos> getAdjacentContainerTargets(BlockPos target) {
        List<BlockPos> adj = new ArrayList<>(7);
        adj.add(target);
        adj.add(target.west());
        adj.add(target.east());
        adj.add(target.north());
        adj.add(target.south());
        adj.add(target.above());
        adj.add(target.below());
        return adj;
    }

    private boolean containerHasAnyMissingItem(GatherContext ctx) {
        if (ctx.client.player == null || ctx.client.player.containerMenu == null) return false;
        List<Slot> slots = ctx.client.player.containerMenu.slots;
        for (Slot slot : slots) {
            if (slot.container == ctx.client.player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            for (MaterialItemEntry entry : ctx.missingItems) {
                if (ItemUtil.is(stack, entry.item)) return true;
            }
            if (ItemUtil.isShulkerBox(stack) && shulkerBoxContainsAnyMissingItem(stack, ctx)) return true;
        }
        return false;
    }

    public boolean shulkerBoxContainsAnyMissingItem(ItemStack shulkerBox, GatherContext ctx) {
        for (ItemStack inner : ItemUtil.contentsOf(shulkerBox)) {
            for (MaterialItemEntry entry : ctx.missingItems) {
                if (ItemUtil.is(inner, entry.item)) return true;
            }
        }
        return false;
    }
}
