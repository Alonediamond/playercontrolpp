package com.alonediamond.playercontrolpp.action;

import net.minecraft.client.MinecraftClient;

public class RotateAction {

    public static void apply(MinecraftClient client, int angleDegrees) {
        if (client.player != null) {
            float newYaw = client.player.getYaw() + angleDegrees;
            newYaw = ((newYaw % 360) + 360) % 360;
            client.player.setYaw(newYaw);
            client.player.setHeadYaw(newYaw);
        }
    }
}
