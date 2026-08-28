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
 * 打开容器：瞄准、重试，以及在目标其实是双箱另一半时回退去试相邻方块。
 */
public class ContainerOpener {

    /** 同一个方块尝试几次后才去试它的邻居。 */
    private static final int MAX_OPEN_ATTEMPTS = 3;
    /** 第几次尝试时先跳一下，以防视线被挡。 */
    private static final int JUMP_ON_ATTEMPT = 2;
    /** 一个容器坐标上总共试过几个不同方块后彻底放弃。 */
    private static final int MAX_CHEST_RETRIES = 3;
    /** 点击后等容器界面的 tick 数。 */
    private static final int OPEN_WAIT_TICKS = 10;
    /** 右键回退路径用的较短等待，它反应更快。 */
    private static final int FALLBACK_WAIT_TICKS = 6;

    /** 打开 {@code target} 处的容器。OPENING_CONTAINER 转换的入口。 */
    public void openContainerAt(BlockPos target, GatherContext ctx) {
        openContainerWithRetry(target, false, 0, ctx);
    }

    /**
     * 通过 {@code useItemOn()} 发送一个明确的 BlockHitResult 来开容器，绕过客户端射线检测，
     * 相邻的容器就抢不走这次点击。
     */
    public void openContainerWithRetry(BlockPos target, boolean jumpBeforeClick, int attemptNumber, GatherContext ctx) {
        ctx.currentContainerTarget = target;
        ctx.openAttemptCount = attemptNumber;

        // 上一次尝试的回退路径按下的键现在已经过期了。
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
            // useItemOn 直接失败了——退回让原版自己的射线检测来做，办法是按住右键。
            // 这个按住登记在 SimulatedInput 上，opener 停止时会被释放，不会把右键按死。
            SimulatedInput.hold(ctx.client.options.keyUse, this);
            ctx.transferCooldown = FALLBACK_WAIT_TICKS;
            ctx.containerJustOpened = true;
        }
    }

    /** 松开本 opener 按住的所有键。由状态机的终态调用。 */
    public void releaseKeys() {
        SimulatedInput.releaseAll(this);
    }

    /**
     * 开容器冷却结束时运行：容器界面出现了吗？里面有我们还需要的东西吗？
     */
    public void checkOpenResult(GatherContext ctx, TaskStateMachine tsm, BaritonePathingController pathing) {
        Minecraft mc = ctx.client;

        // 点击已经有了结果（成或不成），两种情况都不再按住右键。
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

    /** 当前目标打不开、或打开了但没有有用的东西时调用。 */
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
        // 这个坐标（连同试过的邻居）对本物品确认无货，记入排除表；
        // 不记的话 SEARCHING 重建列表后还会回到它，开箱-关箱循环永不前进。
        if (ctx.currentContainerTarget != null) {
            ctx.exhaustedPositions.add(ctx.currentContainerTarget);
        }
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

    /** 关掉当前打开的容器界面。 */
    public void closeAnyContainer(Minecraft mc) {
        if (mc.player != null && ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen) {
            mc.player.closeContainer();
        }
    }

    private Direction getNearestContainerFace(BlockPos target, GatherContext ctx) {
        if (ctx.client.player == null) return Direction.UP;
        return nearestFace(ctx.client.player.getEyePosition(), target);
    }

    /** @return {@code target} 上最正对 {@code eye} 的那个面。 */
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

    /** 目标本身加它的六个邻居——覆盖双箱和轻微瞄偏。 */
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
