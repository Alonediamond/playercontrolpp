package com.alonediamond.playercontrolpp.feature;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * The list of {@link ClientFeature}s, in tick order.
 *
 * <p>Registration order is the tick order and the world-change notification order, so it is
 * meaningful — see {@code InitHandler} for the order and why.
 *
 * <p>A feature that throws is logged and skipped rather than being allowed to kill the tick
 * handler, which would silently disable every feature after it.
 */
public final class FeatureRegistry {

    private static final List<ClientFeature> FEATURES = new ArrayList<>();

    private FeatureRegistry() {}

    public static void register(ClientFeature feature) {
        FEATURES.add(feature);
    }

    public static void tickAll(Minecraft mc) {
        for (ClientFeature feature : FEATURES) {
            try {
                feature.onClientTick(mc);
            } catch (Exception e) {
                Playercontrolpp.LOGGER.error("Feature {} threw during client tick",
                        feature.getClass().getName(), e);
            }
        }
    }

    public static void notifyWorldChange() {
        for (ClientFeature feature : FEATURES) {
            try {
                feature.onWorldChange();
            } catch (Exception e) {
                Playercontrolpp.LOGGER.error("Feature {} threw during world change",
                        feature.getClass().getName(), e);
            }
        }
    }

    /** @return whether any registered feature reports itself active. */
    public static boolean anyActive() {
        for (ClientFeature feature : FEATURES) {
            if (feature.isActive()) return true;
        }
        return false;
    }
}
