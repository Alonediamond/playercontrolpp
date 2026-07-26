package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.compat.ContainerContentsCompat;

import com.alonediamond.playercontrolpp.compat.ScreenCompat;

import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer.State;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages container interaction: opening, retry logic, adjacent position fallback.
 */
public class ContainerOpener {

    /**
     * Open container at the given position. Called from OPENING_CONTAINER state transition.
     */
    public void openContainerAt(BlockPos target, GatherContext ctx) {
        openContainerWithRetry(target, false, 0, ctx);
    }

    /**
     * Opens a container by sending an explicit BlockHitResult via useItemOn().
     * This bypasses client-side raycasting, so adjacent containers do not interfere.
     */
    public void openContainerWithRetry(BlockPos target, boolean jumpBeforeClick, int attemptNumber, GatherContext ctx) {
        ctx.currentContainerTarget = target;
        ctx.openAttemptCount = attemptNumber;

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

            ctx.transferCooldown = 10;
            ctx.containerJustOpened = true;

        } catch (Exception e) {
            try {
                ctx.client.options.keyUse.setDown(true);
                ctx.transferCooldown = 6;
                ctx.containerJustOpened = true;
            } catch (Exception ignored) {}
        }
    }

    /**
     * Called when the transfer cooldown expires after attempting to open a container.
     * Checks if the container opened successfully and contains matching items.
     */
    public void checkOpenResult(GatherContext ctx, TaskStateMachine tsm, BaritonePathingController pathing) {
        Minecraft mc = ctx.client;

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
            if (ctx.openAttemptCount < 3) {
                boolean jump = (ctx.openAttemptCount == 2);
                openContainerWithRetry(ctx.currentContainerTarget, jump, ctx.openAttemptCount, ctx);
            } else {
                retryAdjacentOrFail(ctx, tsm, pathing);
            }
        }
    }

    /**
     * Called when the current container target fails to open or has wrong contents.
     */
    public void retryAdjacentOrFail(GatherContext ctx, TaskStateMachine tsm,
                                     BaritonePathingController pathing) {
        if (ctx.adjacentContainerTargets == null) {
            ctx.adjacentContainerTargets = getAdjacentContainerTargets(ctx.currentContainerTarget);
            ctx.adjacentTryIndex = 0;
        }

        while (ctx.adjacentTryIndex < ctx.adjacentContainerTargets.size()) {
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
        if (ctx.chestRetryCount >= 3) {
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
        if (ctx.client.player.blockPosition().distSqr(pos) <= 25.0) {
            tsm.setState(State.OPENING_CONTAINER);
            openContainerAt(pos, ctx);
        } else {
            tsm.setState(State.PATHING);
            pathing.startPathing(pos, ctx);
        }
    }

    /**
     * Close any currently open container screen.
     */
    public void closeAnyContainer(Minecraft mc) {
        if (mc.player != null && ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen) {
            mc.player.closeContainer();
        }
    }

    private Direction getNearestContainerFace(BlockPos target, GatherContext ctx) {
        if (ctx.client.player == null) return Direction.UP;
        Vec3 playerEye = ctx.client.player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(target);

        double dx = playerEye.x - center.x;
        double dy = playerEye.y - center.y;
        double dz = playerEye.z - center.z;

        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);

        if (ax >= ay && ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
        if (ay >= ax && ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private List<BlockPos> getAdjacentContainerTargets(BlockPos target) {
        List<BlockPos> adj = new ArrayList<>();
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
                if (itemsMatch(stack, entry.item)) return true;
            }
            if (isShulkerBox(stack) && shulkerBoxContainsAnyMissingItem(stack, ctx)) return true;
        }
        return false;
    }

    public boolean shulkerBoxContainsAnyMissingItem(ItemStack shulkerBox, GatherContext ctx) {
        ItemContainerContents container = shulkerBox.getComponents().get(DataComponents.CONTAINER);
        if (container == null) return false;
        for (ItemStack inner : ContainerContentsCompat.nonEmptyItems(container)) {
            for (MaterialItemEntry entry : ctx.missingItems) {
                if (itemsMatch(inner, entry.item)) return true;
            }
        }
        return false;
    }

    private boolean itemsMatch(ItemStack stack, net.minecraft.world.item.Item targetItem) {
        if (stack.getItem() == targetItem) return true;
        net.minecraft.resources.Identifier stackId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        net.minecraft.resources.Identifier targetId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(targetItem);
        return stackId.equals(targetId);
    }

    private boolean isShulkerBox(ItemStack stack) {
        net.minecraft.resources.Identifier id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id.getPath().contains("shulker_box");
    }
}
