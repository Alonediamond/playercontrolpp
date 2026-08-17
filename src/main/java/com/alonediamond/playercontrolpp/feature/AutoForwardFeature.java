package com.alonediamond.playercontrolpp.feature;

import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.minecraft.client.Minecraft;

/**
 * 一直按住前进键直到再次关闭。键是由 {@code ClientEventHandler} 经 {@code SimulatedInput} 按下的，
 * 不在这里按。
 */
public class AutoForwardFeature {

    private static boolean enabled;

    /** 注册进 {@link FeatureRegistry}，见 {@code InitHandler}。 */
    public static final ClientFeature FEATURE = new ClientFeature() {
        @Override public void onWorldChange() { AutoForwardFeature.onWorldChange(); }
        @Override public boolean isActive() { return enabled; }
    };

    private AutoForwardFeature() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void toggle(Minecraft client) {
        enabled = !enabled;
        MessageUtil.sendActionBar(client, enabled
                ? "playercontrolpp.message.auto_forward.on"
                : "playercontrolpp.message.auto_forward.off");
    }

    public static void onWorldChange() {
        if (enabled) {
            enabled = false;
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                MessageUtil.sendActionBar(client, "playercontrolpp.message.auto_forward.world_change");
            }
        }
    }
}
