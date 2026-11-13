package com.alonediamond.playercontrolpp.interaction;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** 方块交互的几何约定；重试、距离阈值和操作间隔由功能各自决定。 */
public final class BlockInteraction {
    private BlockInteraction() {}

    /** 对准方块中心；不修改上一帧角度，保留容器操作原有的视角插值。 */
    public static void lookAt(Player player, BlockPos target) {
        Vec3 eye = player.getEyePosition();
        double dx = target.getX() + 0.5 - eye.x;
        double dy = target.getY() + 0.5 - eye.y;
        double dz = target.getZ() + 0.5 - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontal));
        player.setYRot(yaw);
        player.setYHeadRot(yaw);
        player.setXRot(pitch);
    }

    /** 明确指定点击面，避免客户端准星命中相邻容器。 */
    public static void useNearestFace(Minecraft mc, BlockPos target) {
        Direction face = nearestFace(mc.player.getEyePosition(), target);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit(target, face));
    }

    public static BlockHitResult hit(BlockPos target, Direction face) {
        return new BlockHitResult(faceCenter(target, face), face, target, false);
    }

    public static Vec3 faceCenter(BlockPos target, Direction face) {
        return Vec3.atCenterOf(target).add(
                face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
    }

    /** 分量相等时沿用 X、Y、Z 的选择顺序，使方块边角的点击结果稳定。 */
    public static Direction nearestFace(Vec3 eye, BlockPos target) {
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

    /** 仅提供距离计算；各功能仍保留自己的到达阈值。 */
    public static double distanceToBlockSq(Vec3 eye, BlockPos target) {
        double dx = Math.max(Math.abs(eye.x - (target.getX() + 0.5)) - 0.5, 0.0);
        double dy = Math.max(Math.abs(eye.y - (target.getY() + 0.5)) - 0.5, 0.0);
        double dz = Math.max(Math.abs(eye.z - (target.getZ() + 0.5)) - 0.5, 0.0);
        return dx * dx + dy * dy + dz * dz;
    }
}
