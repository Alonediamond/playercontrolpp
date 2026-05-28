package com.alonediamond.playercontrolpp.integration;

import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Litematica integration via reflection.
 * Directly manipulates single-layer value for reliability.
 */
public class LitematicaIntegration {

    private static boolean enabled = true;
    private static boolean showActionBar = true;

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean enabled) { LitematicaIntegration.enabled = enabled; }

    public static boolean isShowActionBar() { return showActionBar; }
    public static void setShowActionBar(boolean show) { showActionBar = show; }

    public static boolean incrementLayer(int amount) {
        if (!enabled || amount == 0) return false;

        try {
            Class<?> dmClass = Class.forName("fi.dy.masa.litematica.data.DataManager");
            Object range = dmClass.getMethod("getRenderLayerRange").invoke(null);
            if (range == null) return false;

            // Ensure SINGLE_LAYER mode
            Object mode = range.getClass().getMethod("getLayerMode").invoke(range);
            String modeName = ((Enum<?>) mode).name();
            if (!"SINGLE_LAYER".equals(modeName)) return false;

            // Directly get/set layerSingle for reliability
            int current = (Integer) range.getClass().getMethod("getLayerSingle").invoke(range);
            range.getClass().getMethod("setLayerSingle", int.class).invoke(range, current + amount);

            if (showActionBar) {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client.player != null) {
                    String layerStr = (String) range.getClass()
                            .getMethod("getCurrentLayerString").invoke(range);
                    String msg = StringUtils.translate("playercontrolpp.message.litematica.layer", layerStr);
                    client.player.sendMessage(Text.of(msg), true);
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isAvailable() {
        try {
            Class.forName("fi.dy.masa.litematica.data.DataManager");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
