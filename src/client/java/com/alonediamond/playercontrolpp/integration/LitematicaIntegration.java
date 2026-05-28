package com.alonediamond.playercontrolpp.integration;

import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Litematica integration via reflection to avoid compile-time dependency.
 * Only works in SINGLE_LAYER mode.
 */
public class LitematicaIntegration {

    private static boolean enabled = true;
    private static boolean showActionBar = true;

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean enabled) { LitematicaIntegration.enabled = enabled; }

    public static boolean isShowActionBar() { return showActionBar; }
    public static void setShowActionBar(boolean show) { showActionBar = show; }

    /**
     * Increment (or decrement) the Litematica SINGLE_LAYER render layer.
     * @param amount positive to go up, negative to go down
     */
    public static boolean incrementLayer(int amount) {
        if (!enabled || amount == 0) return false;

        try {
            Class<?> dmClass = Class.forName("fi.dy.masa.litematica.data.DataManager");
            Object range = dmClass.getMethod("getRenderLayerRange").invoke(null);
            if (range == null) return false;

            // Only work in SINGLE_LAYER mode (ordinal 1)
            Object mode = range.getClass().getMethod("getLayerMode").invoke(range);
            if (mode == null) return false;
            String modeName = ((Enum<?>) mode).name();
            if (!"SINGLE_LAYER".equals(modeName)) return false;

            // moveLayer handles positive and negative values
            range.getClass().getMethod("moveLayer", int.class).invoke(range, amount);

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
