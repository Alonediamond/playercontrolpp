package com.alonediamond.playercontrolpp.feature.automaterial.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class InteractionUtil {
    private InteractionUtil() {}

    public static void openContainerAt(BlockPos target, Minecraft mc) {
        try {
            Vec3 playerEye = mc.player.getEyePosition();
            double dx = target.getX() + 0.5 - playerEye.x;
            double dy = target.getY() + 0.5 - playerEye.y;
            double dz = target.getZ() + 0.5 - playerEye.z;
            double distH = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) Math.toDegrees(-Math.atan2(dy, distH));
            mc.player.setYRot(yaw);
            mc.player.setYHeadRot(yaw);
            mc.player.setXRot(pitch);

            Direction face = getNearestContainerFace(target,mc);
            Vec3 hitPos = new Vec3(
                    target.getX() + 0.5 + face.getStepX() * 0.5,
                    target.getY() + 0.5 + face.getStepY() * 0.5,
                    target.getZ() + 0.5 + face.getStepZ() * 0.5
            );

            BlockHitResult hitResult = new BlockHitResult(hitPos, face, target, false);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);


        } catch (Exception ignore) {
        }
    }

    private static Direction getNearestContainerFace(BlockPos target, Minecraft mc) {
        if (mc.player == null) return Direction.UP;

        Vec3 center = Vec3.atCenterOf(target);
        Vec3 eye = mc.player.getEyePosition();

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
}
