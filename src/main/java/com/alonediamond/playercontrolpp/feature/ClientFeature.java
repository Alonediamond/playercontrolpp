package com.alonediamond.playercontrolpp.feature;

import net.minecraft.client.Minecraft;

/**
 * One client-side feature's lifecycle.
 *
 * <p>Registering with {@link FeatureRegistry} is what makes a feature tick and what makes it
 * get told about world changes. Adding a feature no longer means editing
 * {@code ClientEventHandler}, and the world-change broadcast can no longer miss one.
 *
 * <p>Every method has a default, so a feature implements only the parts it needs.
 */
public interface ClientFeature {

    /** Called once per client tick while a player exists. */
    default void onClientTick(Minecraft mc) {}

    /** Called before the client level is swapped: dimension change, disconnect, or world load. */
    default void onWorldChange() {}

    /** @return whether this feature is currently doing something the user would call "running". */
    default boolean isActive() {
        return false;
    }
}
