package com.alonediamond.playercontrolpp.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * 方块交互共用的几何计算。
 */
public final class BlockUtil {

    private BlockUtil() {}

    /**
     * @return {@code target} 上最正对 {@code eye} 的那个面。
     *
     * <p>放置、开箱都要"点哪个面"：算出来直接塞进 {@code BlockHitResult}，
     * 就不必依赖客户端自己的射线检测（双箱、瞄偏都会让它选中隔壁方块）。
     */
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
}
