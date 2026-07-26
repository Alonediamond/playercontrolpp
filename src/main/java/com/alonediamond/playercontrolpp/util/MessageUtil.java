package com.alonediamond.playercontrolpp.util;

import com.alonediamond.playercontrolpp.compat.PlayerCompat;

import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.Minecraft;

/**
 * Action bar feedback. Every user-facing status message in this mod goes through here.
 */
public final class MessageUtil {

    private MessageUtil() {}

    /**
     * Show a translated message above the hotbar.
     *
     * @param translationKey lang key
     * @param args           format arguments for keys containing {@code %s}
     */
    public static void sendActionBar(Minecraft client, String translationKey, Object... args) {
        if (client.player != null) {
            PlayerCompat.sendOverlayMessage(client.player,
                    StringUtils.translateAsText(translationKey, args));
        }
    }
}
