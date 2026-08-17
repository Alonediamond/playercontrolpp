package com.alonediamond.playercontrolpp.action;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/** 瞬间把玩家转过固定角度。 */
public final class RotateAction {

    private RotateAction() {}

    /**
     * 给玩家 yaw 加上 {@code angleDegrees}，这一帧就到位。
     *
     * <p>{@code yRotO} 必须和 {@code yRot} 一起设：相机会在一 tick 的若干帧之间对这两个值插值，
     * 只设后者就会把中间每个角度都扫过去——默认 180° 时那是看得见的原地转圈，而不是想要的瞬间转身。
     */
    public static void apply(Minecraft client, int angleDegrees) {
        LocalPlayer player = client.player;
        if (player == null) return;

        float newYaw = Mth.wrapDegrees(player.getYRot() + angleDegrees);
        player.setYRot(newYaw);
        player.setYHeadRot(newYaw);
        player.yRotO = newYaw;
        player.yHeadRotO = newYaw;
    }
}
