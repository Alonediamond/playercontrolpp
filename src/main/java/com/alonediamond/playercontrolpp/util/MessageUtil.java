package com.alonediamond.playercontrolpp.util;

import com.alonediamond.playercontrolpp.compat.PlayerCompat;

import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class MessageUtil {

    public static void sendActionBar(Minecraft client, String translationKey) {
        if (client.player != null) {
            PlayerCompat.sendOverlayMessage(client.player, Component.nullToEmpty(StringUtils.translate(translationKey)));
        }
    }
}
