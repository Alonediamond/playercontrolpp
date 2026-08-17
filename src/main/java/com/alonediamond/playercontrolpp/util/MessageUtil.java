package com.alonediamond.playercontrolpp.util;

import com.alonediamond.playercontrolpp.compat.PlayerCompat;

import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.Minecraft;

/**
 * ActionBar 反馈。模组里所有面向用户的状态提示都走这里。
 */
public final class MessageUtil {

    private MessageUtil() {}

    /**
     * 在快捷栏上方显示一条翻译文本。
     *
     * @param translationKey 语言键
     * @param args           含 {@code %s} 的键所需的格式参数
     */
    public static void sendActionBar(Minecraft client, String translationKey, Object... args) {
        if (client.player != null) {
            PlayerCompat.sendOverlayMessage(client.player,
                    StringUtils.translateAsText(translationKey, args));
        }
    }
}
