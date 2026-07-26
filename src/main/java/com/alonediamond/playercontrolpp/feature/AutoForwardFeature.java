package com.alonediamond.playercontrolpp.feature;

import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.minecraft.client.Minecraft;

/**
 * Holds the forward key down until toggled off. The key itself is pressed by
 * {@code ClientEventHandler} through {@code SimulatedInput}, not from here.
 */
public class AutoForwardFeature {

    private static boolean enabled;

    /** Registered with {@link FeatureRegistry}; see {@code InitHandler}. */
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
