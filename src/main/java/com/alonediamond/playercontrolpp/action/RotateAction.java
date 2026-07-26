package com.alonediamond.playercontrolpp.action;

import net.minecraft.client.Minecraft;

public class RotateAction {

    public static void apply(Minecraft client, int angleDegrees) {
        if (client.player != null) {
            float newYaw = client.player.getYRot() + angleDegrees;
            newYaw = ((newYaw % 360) + 360) % 360;
            client.player.setYRot(newYaw);
            client.player.setYHeadRot(newYaw);
        }
    }
}
