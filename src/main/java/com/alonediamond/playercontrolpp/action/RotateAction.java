package com.alonediamond.playercontrolpp.action;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/** Turns the player by a fixed angle, instantly. */
public final class RotateAction {

    private RotateAction() {}

    /**
     * Add {@code angleDegrees} to the player's yaw and land there this frame.
     *
     * <p>{@code yRotO} is set as well as {@code yRot}: the camera interpolates between the two
     * across the frames of a tick, so setting only {@code yRot} would sweep through every angle in
     * between — for the 180&deg; default that is a visible spin rather than the intended flip.
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
