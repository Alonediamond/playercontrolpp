package com.alonediamond.playercontrolpp.util;

import com.alonediamond.playercontrolpp.compat.PlayerCompat;

import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.Minecraft;

/**
 * ActionBar 反馈。模组里所有面向用户的状态提示都走这里。
 */
public final class MessageUtil {

    private MessageUtil() {}

    /** 生命周期停止共用模板，原因先按当前语言翻译，不能把语言键直接显示给玩家。 */
    public static void sendStopped(Minecraft client, String reasonKey) {
        sendActionBar(client, "playercontrolpp.message.feature_stopped", StringUtils.translate(reasonKey));
    }

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
