package com.alonediamond.playercontrolpp.integration;

import fi.dy.masa.malilib.util.StringUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.Collections;
import java.util.Set;

/**
 * Litematica integration via reflection.
 * Calls moveLayer() exactly as Litematica's PageUp/PageDown hotkeys do.
 */
public class LitematicaIntegration implements ModIntegration {

    private static final LitematicaIntegration INSTANCE = new LitematicaIntegration();
    private boolean loaded;
    private boolean showActionBar = true;

    private LitematicaIntegration() {}

    public static LitematicaIntegration getInstance() { return INSTANCE; }

    @Override
    public boolean isLoaded() { return loaded; }

    @Override
    public void initialize() {
        loaded = FabricLoader.getInstance().isModLoaded("litematica");
    }

    public static boolean isShowActionBar() { return INSTANCE.showActionBar; }
    public static void setShowActionBar(boolean show) { INSTANCE.showActionBar = show; }

    /**
     * Check if any Litematica schematic placement is currently loaded.
     */
    public boolean isSchematicLoaded() {
        try {
            Class<?> dmClass = Class.forName("fi.dy.masa.litematica.data.DataManager");
            // All DataManager methods are static — no getInstance()
            Object spm = dmClass.getMethod("getSchematicPlacementManager").invoke(null);
            if (spm == null) return false;
            java.util.List<?> placements = getAllPlacementsViaReflection(spm);
            return placements != null && !placements.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Get the schematic world (WorldView) for querying schematic block states.
     * Returns null if no schematic is loaded.
     */
    public Object getSchematicWorld() {
        try {
            Class<?> swhClass = Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler");
            return swhClass.getMethod("getSchematicWorld").invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get all schematic placements. Returns the List of SchematicPlacement objects, or empty list on failure.
     */
    @SuppressWarnings("unchecked")
    public java.util.List<?> getAllPlacements() {
        try {
            Class<?> dmClass = Class.forName("fi.dy.masa.litematica.data.DataManager");
            Object spm = dmClass.getMethod("getSchematicPlacementManager").invoke(null);
            if (spm == null) return java.util.Collections.emptyList();
            return getAllPlacementsViaReflection(spm);
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Try multiple known method names to get placement list from the placement manager.
     */
    @SuppressWarnings("unchecked")
    private java.util.List<?> getAllPlacementsViaReflection(Object spm) {
        String[] methodNames = {
            "getAllSchematicPlacements",
            "getAllSchematicsPlacements",
            "getSchematicPlacements",
            "getLoadedSchematicPlacements"
        };
        for (String name : methodNames) {
            try {
                Object result = spm.getClass().getMethod(name).invoke(spm);
                if (result instanceof java.util.List) {
                    return (java.util.List<?>) result;
                }
            } catch (Exception ignored) {}
        }
        return java.util.Collections.emptyList();
    }

    /**
     * Get Litematica's MaterialList via reflection.
     */
    public Object getMaterialList() {
        try {
            Class<?> dmClass = Class.forName("fi.dy.masa.litematica.data.DataManager");
            return dmClass.getMethod("getMaterialList").invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get Litematica's internal ignored-entries set via reflection.
     */
    @SuppressWarnings("unchecked")
    public Set<Object> getIgnoredSet(Object materialList) {
        try {
            return (Set<Object>) materialList.getClass().getField("ignored").get(materialList);
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    public boolean incrementLayer(int amount) {
        if (amount == 0) return false;

        try {
            Class<?> dmClass = Class.forName("fi.dy.masa.litematica.data.DataManager");
            Object range = dmClass.getMethod("getRenderLayerRange").invoke(null);
            if (range == null) return false;

            Object mode = range.getClass().getMethod("getLayerMode").invoke(range);
            if (!"SINGLE_LAYER".equals(((Enum<?>) mode).name())) return false;

            boolean ok = (Boolean) range.getClass()
                    .getMethod("moveLayer", int.class).invoke(range, amount);
            if (!ok) return false;

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
}
